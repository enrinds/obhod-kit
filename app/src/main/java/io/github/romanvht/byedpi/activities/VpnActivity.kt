package io.github.romanvht.byedpi.activities

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.START_ACTION
import io.github.romanvht.byedpi.data.STOP_ACTION
import io.github.romanvht.byedpi.services.ByeDpiVpnService
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.vpn.SingBoxRunner
import io.github.romanvht.byedpi.vpn.VpnKeys

class VpnActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var keysView: EditText
    private lateinit var logsView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pending: VpnKeys.Built? = null

    private val ticker = object : Runnable {
        override fun run() {
            render()
            watchdog()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn)

        statusView = findViewById(R.id.vpnStatus)
        keysView = findViewById(R.id.vpnKeys)
        logsView = findViewById(R.id.vpnLogs)

        keysView.setText(loadKeys())

        findViewById<Button>(R.id.vpnPaste).setOnClickListener {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isBlank()) {
                toast("в буфере пусто")
            } else {
                keysView.setText(text.trim())
                toast("вставил")
            }
        }

        findViewById<Button>(R.id.vpnSave).setOnClickListener { saveKeys() }
        findViewById<Button>(R.id.vpnConnect).setOnClickListener { connect() }
        findViewById<Button>(R.id.vpnDisconnect).setOnClickListener { disconnect() }

        SingBoxRunner.listener = { line ->
            runOnUiThread {
                logsView.append(line + "\n")
                if (logsView.lineCount > 300) logsView.text = logsView.text.takeLast(12000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        SingBoxRunner.listener = null
        super.onDestroy()
    }

    private fun watchdog() {
        if (!vpnRunning() || SingBoxRunner.isRunning()) return
        Thread {
            disconnect()
            toast("sing-box упал, VPN выключен")
        }.start()
    }

    private fun render() {
        val socks = if (SingBoxRunner.portOpen(SingBoxRunner.SOCKS_PORT, timeoutMs = 60)) "открыт" else "закрыт"
        val engine = if (SingBoxRunner.isRunning()) "работает" else "не запущен"
        val tun = if (vpnRunning()) "поднят" else "лежит"
        statusView.text = "sing-box: $engine  ·  socks ${SingBoxRunner.SOCKS_PORT}: $socks  ·  TUN: $tun"
    }

    private fun vpnRunning(): Boolean = getPreferences().getBoolean("vpn_user_started", false)

    private fun connect() {
        val raw = keysView.text.toString()
        saveKeys(raw)

        val built = try {
            VpnKeys.build(raw)
        } catch (e: Exception) {
            toast(e.message ?: "ключи не разобрал")
            return
        }

        SingBoxRunner.writeConfig(this, built.json)
        logsView.text = built.skipped.joinToString("\n") { "пропустил: $it" }.let {
            if (it.isBlank()) "" else it + "\n"
        }
        if (built.skipped.isNotEmpty()) {
            toast("часть ключей не подошла — смотри лог")
        }
        pending = built

        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            toast("разреши VPN")
            @Suppress("DEPRECATION")
            startActivityForResult(prepare, REQ_PREPARE)
        } else {
            startVpn(built)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PREPARE) return
        val built = pending ?: return
        if (resultCode == RESULT_OK) startVpn(built) else toast("без разрешения VPN не поднять")
    }

    private fun startVpn(built: VpnKeys.Built) {
        val started = SingBoxRunner.start(this)
        if (started.isFailure) {
            toast("sing-box не стартовал: ${started.exceptionOrNull()?.message}")
            return
        }

        savePrefs()

        Thread {
            val ok = SingBoxRunner.waitPort(SingBoxRunner.SOCKS_PORT, 5000)
            runOnUiThread {
                if (!ok) {
                    SingBoxRunner.stop()
                    restorePrefs()
                    toast("socks не поднялся, смотри лог")
                    return@runOnUiThread
                }
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ByeDpiVpnService::class.java).setAction(START_ACTION)
                )
                getPreferences().edit().putBoolean("vpn_user_started", true).apply()
                toast("поднимаю VPN: ${built.names.first()}")
            }
        }.start()
    }

    private fun disconnect() {
        try {
            startService(Intent(this, ByeDpiVpnService::class.java).setAction(STOP_ACTION))
        } catch (_: Exception) {
        }
        SingBoxRunner.stop()
        restorePrefs()
        getPreferences().edit().putBoolean("vpn_user_started", false).apply()
        toast("выключил")
    }

    private fun savePrefs() {
        val prefs = getPreferences()
        val e = prefs.edit()
        if (!prefs.getBoolean("vpn_have_old", false)) {
            e.putString("vpn_old_ip", prefs.getString("byedpi_proxy_ip", null))
            e.putString("vpn_old_port", prefs.getString("byedpi_proxy_port", null))
            e.putBoolean("vpn_have_old", true)
        }
        e.putString("byedpi_proxy_ip", SingBoxRunner.SOCKS_HOST)
        e.putString("byedpi_proxy_port", SingBoxRunner.SOCKS_PORT.toString())
        e.apply()
    }

    private fun restorePrefs() {
        val prefs = getPreferences()
        if (!prefs.getBoolean("vpn_have_old", false)) return
        val e = prefs.edit().putBoolean("vpn_have_old", false)
        val ip = prefs.getString("vpn_old_ip", null)
        val port = prefs.getString("vpn_old_port", null)
        if (ip == null) e.remove("byedpi_proxy_ip") else e.putString("byedpi_proxy_ip", ip)
        if (port == null) e.remove("byedpi_proxy_port") else e.putString("byedpi_proxy_port", port)
        e.remove("vpn_old_ip").remove("vpn_old_port").apply()
    }

    private fun loadKeys(): String {
        val saved = getPreferences().getString("vpn_keys", null)
        if (!saved.isNullOrBlank()) return saved
        return try {
            assets.open("vpn/keys.txt").bufferedReader().readText()
                .lines()
                .filterNot { it.trim().startsWith("#") }
                .joinToString("\n")
                .trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun saveKeys(raw: String = keysView.text.toString()) {
        getPreferences().edit().putString("vpn_keys", raw).apply()
        toast("ключи сохранены")
    }

    private fun toast(msg: String) {
        val show = { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        if (Looper.myLooper() == Looper.getMainLooper()) show() else runOnUiThread { show() }
    }

    companion object {
        private const val REQ_PREPARE = 101
    }
}
