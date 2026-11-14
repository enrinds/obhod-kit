package io.github.romanvht.byedpi.vpn

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object VpnKeys {

    class BadKey(message: String) : Exception(message)

    data class Built(
        val json: String,
        val names: List<String>,
        val skipped: List<String> = emptyList()
    )

    fun build(raw: String): Built {
        var text = raw.trim()
        if (text.isEmpty()) throw BadKey("пусто: вставь ключи или JSON")

        decodeSubscription(text)?.let { if (it.contains("://")) text = it }

        if (text.startsWith("{") || text.startsWith("[")) {
            return fromJson(text)
        }

        val nodes = ArrayList<JSONObject>()
        val skipped = ArrayList<String>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("//")) continue
            if (!l.contains("://")) continue
            try {
                nodes.add(parseLink(l))
            } catch (e: Exception) {
                skipped.add("${l.take(30)}… — ${e.message ?: "не разобрал"}")
            }
        }
        if (nodes.isEmpty()) {
            throw BadKey(skipped.firstOrNull() ?: "не нашёл ни одной ссылки (vless/vmess/trojan/ss/hysteria2)")
        }
        return config(nodes, skipped)
    }

    private fun fromJson(text: String): Built {
        val parsed = try {
            JSONTokener(text).nextValue()
        } catch (e: Exception) {
            throw BadKey("JSON не читается: ${e.message}")
        }
        val obj = when (parsed) {
            is JSONObject -> parsed
            is JSONArray -> parsed.optJSONObject(0) ?: throw BadKey("в JSON-массиве нет объектов")
            else -> throw BadKey("это не JSON-объект")
        }
        val outbounds = obj.optJSONArray("outbounds") ?: throw BadKey("в JSON нет outbounds")
        val isXray = (0 until outbounds.length()).any { outbounds.optJSONObject(it)?.has("protocol") == true }

        if (!isXray) {
            return config(fromSingBox(outbounds), emptyList())
        }

        val nodes = ArrayList<JSONObject>()
        val skipped = ArrayList<String>()
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            try {
                fromXrayNode(ob)?.let { nodes.add(it) }
            } catch (e: Exception) {
                skipped.add("${ob.optString("tag", "outbound#$i")} — ${e.message ?: "не разобрал"}")
            }
        }
        if (nodes.isEmpty()) {
            throw BadKey(skipped.firstOrNull() ?: "в JSON нет серверных outbound (vless/vmess/trojan/ss)")
        }
        return config(nodes, skipped)
    }

    private fun fromSingBox(arr: JSONArray): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val ob = arr.optJSONObject(i) ?: continue
            if (!ob.has("server")) continue
            out.add(ob)
        }
        if (out.isEmpty()) throw BadKey("в sing-box JSON нет ни одного серверного outbound")
        return out
    }

    private fun fromXrayNode(ob: JSONObject): JSONObject? {
        val proto = ob.optString("protocol").lowercase()
        if (proto !in setOf("vless", "vmess", "trojan", "shadowsocks")) return null

        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        val node = JSONObject().put("type", proto)

        if (proto == "vless" || proto == "vmess") {
            val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
                ?: throw BadKey("нет vnext")
            val user = vnext.optJSONArray("users")?.optJSONObject(0)
                ?: throw BadKey("нет users")
            node.put("server", vnext.optString("address"))
            node.put("server_port", vnext.optInt("port"))
            node.put("uuid", user.optString("id"))
            if (proto == "vmess") {
                node.put("security", user.optString("security").ifBlank { "auto" })
                node.put("alter_id", user.optInt("alterId", 0))
            }
            user.optString("flow").takeIf { it.isNotBlank() }?.let { node.put("flow", it) }
        } else {
            val srv = settings.optJSONArray("servers")?.optJSONObject(0)
                ?: throw BadKey("нет servers")
            node.put("server", srv.optString("address"))
            node.put("server_port", srv.optInt("port"))
            if (proto == "trojan") {
                node.put("password", srv.optString("password"))
            } else {
                node.put("method", srv.optString("method"))
                node.put("password", srv.optString("password"))
            }
        }

        node.put("tag", ob.optString("tag").ifBlank { node.optString("server") })
        tlsFromXray(stream)?.let { node.put("tls", it) }
        transportFromXray(stream)?.let { node.put("transport", it) }
        return node
    }

    private fun tlsFromXray(stream: JSONObject): JSONObject? =
        when (stream.optString("security").lowercase()) {
            "tls" -> JSONObject().put("enabled", true).apply {
                val t = stream.optJSONObject("tlsSettings")
                t?.optString("serverName")?.takeIf { it.isNotBlank() }?.let { put("server_name", it) }
                t?.optString("fingerprint")?.takeIf { it.isNotBlank() }?.let {
                    put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
                }
                t?.optJSONArray("alpn")?.let { put("alpn", it) }
            }
            "reality" -> {
                val r = stream.optJSONObject("realitySettings")
                if (r == null) null else JSONObject().put("enabled", true).apply {
                    r.optString("serverName").takeIf { it.isNotBlank() }?.let { put("server_name", it) }
                    r.optString("fingerprint").takeIf { it.isNotBlank() }?.let {
                        put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
                    }
                    put("reality", JSONObject().put("enabled", true)
                        .put("public_key", r.optString("publicKey"))
                        .put("short_id", r.optString("shortId")))
                }
            }
            else -> null
        }

    private fun transportFromXray(stream: JSONObject): JSONObject? =
        when (stream.optString("network").lowercase()) {
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings")
                JSONObject().put("type", "ws").apply {
                    ws?.optString("path")?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                    ws?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() }?.let {
                        put("headers", JSONObject().put("Host", it))
                    }
                }
            }
            "grpc" -> {
                val g = stream.optJSONObject("grpcSettings")
                JSONObject().put("type", "grpc")
                    .put("service_name", g?.optString("serviceName").orEmpty())
            }
            "httpupgrade" -> {
                val h = stream.optJSONObject("httpupgradeSettings")
                JSONObject().put("type", "httpupgrade").apply {
                    h?.optString("path")?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                    h?.optString("host")?.takeIf { it.isNotBlank() }?.let {
                        put("headers", JSONObject().put("Host", it))
                    }
                }
            }
            "xhttp", "splithttp" -> throw BadKey(XHTTP_HINT)
            else -> null
        }

    private fun config(nodes: List<JSONObject>, skipped: List<String>): Built {
        val seen = HashMap<String, Int>()
        val names = ArrayList<String>()
        for (ob in nodes) {
            var tag = ob.optString("tag").ifBlank { "node" }
            val n = (seen[tag] ?: 0) + 1
            seen[tag] = n
            if (n > 1) tag = "$tag-$n"
            ob.put("tag", tag)
            names.add(tag)
        }

        val inbounds = JSONArray().put(
            JSONObject()
                .put("type", "socks")
                .put("tag", "in")
                .put("listen", SingBoxRunner.SOCKS_HOST)
                .put("listen_port", SingBoxRunner.SOCKS_PORT)
        )

        val outbounds = JSONArray()
        nodes.forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val root = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("route", JSONObject().put("final", names.first()))

        return Built(root.toString(2), names, skipped)
    }

    private fun parseLink(link: String): JSONObject {
        val scheme = link.substringBefore("://").lowercase()
        return when (scheme) {
            "vless" -> parseVless(link)
            "vmess" -> parseVmess(link)
            "trojan" -> parseTrojan(link)
            "ss" -> parseShadowsocks(link)
            "hysteria2", "hy2" -> parseHysteria2(link)
            else -> throw BadKey("не умею протокол $scheme")
        }
    }

    private fun parseVless(link: String): JSONObject {
        val uri = Uri.parse(link)
        val host = uri.host ?: throw BadKey("vless без сервера")
        val port = if (uri.port > 0) uri.port else 443
        val id = uri.userInfo?.takeIf { it.isNotBlank() } ?: throw BadKey("vless без UUID")
        val q = query(uri)

        val node = JSONObject()
            .put("type", "vless")
            .put("tag", name(uri, host, port))
            .put("server", host)
            .put("server_port", port)
            .put("uuid", id)

        q["flow"]?.takeIf { it.isNotBlank() }?.let { node.put("flow", it) }

        val security = q["security"].orEmpty().lowercase()
        if (security == "reality") {
            node.put("tls", JSONObject()
                .put("enabled", true)
                .put("server_name", q["sni"].orEmpty())
                .put("utls", JSONObject().put("enabled", true).put("fingerprint", q["fp"] ?: "chrome"))
                .put("reality", JSONObject()
                    .put("enabled", true)
                    .put("public_key", q["pbk"].orEmpty())
                    .put("short_id", q["sid"].orEmpty())))
        } else if (security == "tls") {
            node.put("tls", JSONObject()
                .put("enabled", true)
                .put("server_name", q["sni"].orEmpty())
                .put("utls", JSONObject().put("enabled", true).put("fingerprint", q["fp"] ?: "chrome")))
        }

        transport(q)?.let { node.put("transport", it) }
        return node
    }

    private fun parseVmess(link: String): JSONObject {
        val payload = link.substringAfter("://").substringBefore("#")
        val json = try {
            JSONObject(b64(payload))
        } catch (e: Exception) {
            throw BadKey("vmess: внутри не JSON")
        }
        val host = json.optString("add").ifBlank { throw BadKey("vmess без адреса") }
        val port = json.optInt("port", 443)
        val node = JSONObject()
            .put("type", "vmess")
            .put("tag", json.optString("ps").ifBlank { "$host:$port" })
            .put("server", host)
            .put("server_port", port)
            .put("uuid", json.optString("id"))
            .put("security", json.optString("scy").ifBlank { "auto" })
            .put("alter_id", json.optInt("aid", 0))

        val net = json.optString("net", "tcp").lowercase()
        if (net == "xhttp" || net == "splithttp") throw BadKey(XHTTP_HINT)
        if (net == "ws" || net == "grpc" || net == "httpupgrade") {
            val t = JSONObject().put("type", net)
            if (net == "ws") {
                t.put("path", json.optString("path").ifBlank { "/" })
                json.optString("host").takeIf { it.isNotBlank() }?.let {
                    t.put("headers", JSONObject().put("Host", it))
                }
            }
            if (net == "grpc") {
                t.put("service_name", json.optString("path"))
            }
            node.put("transport", t)
        }

        if (json.optString("tls").equals("tls", true)) {
            node.put("tls", JSONObject()
                .put("enabled", true)
                .put("server_name", json.optString("sni").ifBlank { json.optString("host").ifBlank { host } })
                .put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome")))
        }
        return node
    }

    private fun parseTrojan(link: String): JSONObject {
        val uri = Uri.parse(link)
        val host = uri.host ?: throw BadKey("trojan без сервера")
        val port = if (uri.port > 0) uri.port else 443
        val q = query(uri)
        val node = JSONObject()
            .put("type", "trojan")
            .put("tag", name(uri, host, port))
            .put("server", host)
            .put("server_port", port)
            .put("password", uri.userInfo ?: throw BadKey("trojan без пароля"))
            .put("tls", JSONObject()
                .put("enabled", true)
                .put("server_name", q["sni"].orEmpty())
                .put("utls", JSONObject().put("enabled", true).put("fingerprint", q["fp"] ?: "chrome")))

        transport(q)?.let { node.put("transport", it) }
        return node
    }

    private fun parseShadowsocks(link: String): JSONObject {
        val body = link.substringAfter("://").substringBefore("#")
        val q: Map<String, String> = if (body.contains("?")) parse(body.substringAfter("?")) else emptyMap()
        val core = body.substringBefore("?")

        val method: String
        val password: String
        val hostport: String

        if (core.contains("@")) {
            val userinfo = core.substringBeforeLast("@")
            hostport = core.substringAfterLast("@")
            val plain = if (userinfo.contains(":")) userinfo else b64(userinfo)
            method = plain.substringBefore(":")
            password = plain.substringAfter(":", "")
        } else {
            val plain = b64(core)
            val creds = plain.substringBeforeLast("@")
            hostport = plain.substringAfterLast("@")
            method = creds.substringBefore(":")
            password = creds.substringAfter(":", "")
        }

        val host = hostport.substringBeforeLast(":", hostport)
        val port = hostport.substringAfterLast(":").toIntOrNull()
            ?: q["port"]?.toIntOrNull() ?: 8388

        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "$host:$port")
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)
    }

    private fun parseHysteria2(link: String): JSONObject {
        val uri = Uri.parse(link)
        val host = uri.host ?: throw BadKey("hysteria2 без сервера")
        val port = if (uri.port > 0) uri.port else 443
        val q = query(uri)
        val password = (uri.userInfo ?: q["password"].orEmpty()).trim()
        if (password.isEmpty()) throw BadKey("hysteria2 без пароля")
        return JSONObject()
            .put("type", "hysteria2")
            .put("tag", name(uri, host, port))
            .put("server", host)
            .put("server_port", port)
            .put("password", password)
            .put("tls", JSONObject()
                .put("enabled", true)
                .put("server_name", q["sni"] ?: q["peer"].orEmpty())
                .put("insecure", q["insecure"] == "1"))
    }

    private fun transport(q: Map<String, String>): JSONObject? =
        when (q["type"]?.lowercase()) {
            "ws" -> JSONObject()
                .put("type", "ws")
                .put("path", q["path"] ?: "/")
                .apply { q["host"]?.let { put("headers", JSONObject().put("Host", it)) } }
            "grpc" -> JSONObject()
                .put("type", "grpc")
                .put("service_name", q["serviceName"].orEmpty())
            "httpupgrade" -> JSONObject()
                .put("type", "httpupgrade")
                .put("path", q["path"] ?: "/")
                .apply { q["host"]?.let { put("headers", JSONObject().put("Host", it)) } }
            "http", "h2" -> JSONObject()
                .put("type", "http")
                .put("path", q["path"] ?: "/")
                .apply { q["host"]?.let { put("host", JSONArray().put(it)) } }
            "xhttp", "splithttp" -> throw BadKey(XHTTP_HINT)
            else -> null
        }

    private fun query(uri: Uri): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            uri.queryParameterNames.forEach { key ->
                uri.getQueryParameter(key)?.let { out[key.lowercase()] = it }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun parse(s: String): Map<String, String> =
        s.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).lowercase() to Uri.decode(it.substring(i + 1))
        }.toMap()

    private fun name(uri: Uri, host: String, port: Int): String =
        Uri.decode(uri.fragment.orEmpty()).ifBlank { "$host:$port" }

    private fun b64(s: String): String {
        val clean = s.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            throw BadKey("не читается base64")
        }
    }

    private fun decodeSubscription(text: String): String? {
        val clean = text.filter { !it.isWhitespace() }
        if (clean.length < 16 || clean.contains("://")) return null
        return try {
            b64(clean).takeIf { it.contains("://") }
        } catch (_: Exception) {
            null
        }
    }

    private const val XHTTP_HINT =
        "транспорт xhttp ядро sing-box не поддерживает — этот ключ только для Xray-клиентов (Happ, v2RayTun, NekoBox, v2rayNG)"
}
