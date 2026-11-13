package io.github.romanvht.byedpi.vpn

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object SingBoxRunner {
    private const val TAG = "SingBoxRunner"

    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 10808

    private val alive = AtomicBoolean(false)
    private var process: Process? = null

    val logs = CopyOnWriteArrayList<String>()

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")

    fun configFile(context: Context): File =
        File(context.filesDir, "singbox/config.json").also { it.parentFile?.mkdirs() }

    fun isRunning(): Boolean = alive.get() && process?.let { isAlive(it) } == true

    fun writeConfig(context: Context, json: String) {
        configFile(context).writeText(json)
    }

    fun start(context: Context): Result<Unit> {
        if (isRunning()) return Result.success(Unit)

        val bin = binary(context)
        if (!bin.exists()) {
            return Result.failure(IllegalStateException("нет libsingbox.so в ${bin.parent}"))
        }
        val config = configFile(context)
        if (!config.exists()) {
            return Result.failure(IllegalStateException("нет config.json — сначала сохрани ключи"))
        }

        logs.clear()

        return try {
            val builder = ProcessBuilder(bin.absolutePath, "run", "-c", config.absolutePath)
                .redirectErrorStream(true)
            builder.environment()["HOME"] = context.filesDir.absolutePath
            builder.environment()["TMPDIR"] = context.cacheDir.absolutePath

            val p = builder.start()
            process = p
            alive.set(true)

            Thread({
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (logs.size > 400) logs.removeAt(0)
                        logs.add(line)
                        listener?.invoke(line)
                        Log.d(TAG, line)
                    }
                } catch (_: Exception) {
                }
            }, "singbox-log").apply { isDaemon = true }.start()

            Result.success(Unit)
        } catch (e: Exception) {
            alive.set(false)
            Result.failure(e)
        }
    }

    fun stop() {
        val p = process
        alive.set(false)
        process = null
        if (p == null) return

        try {
            p.destroy()
            var waited = 0
            while (isAlive(p) && waited < 3000) {
                Thread.sleep(100)
                waited += 100
            }
            if (isAlive(p) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.destroyForcibly()
            }
        } catch (_: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly()
        }
    }

    private fun isAlive(p: Process): Boolean = try {
        p.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    } catch (_: Exception) {
        false
    }

    fun portOpen(port: Int = SOCKS_PORT, host: String = SOCKS_HOST, timeoutMs: Int = 500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    fun waitPort(port: Int = SOCKS_PORT, totalMs: Int = 5000): Boolean {
        val until = System.currentTimeMillis() + totalMs
        while (System.currentTimeMillis() < until) {
            if (portOpen(port)) return true
            Thread.sleep(150)
        }
        return false
    }
}
