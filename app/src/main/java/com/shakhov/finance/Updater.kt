package com.shakhov.finance

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Проверка и установка новой версии с GitHub Releases. */
object Updater {
    private const val API = "https://api.github.com/repos/ShakhovAlexey/finance/releases/latest"

    data class Info(val version: Int, val name: String, val url: String)

    sealed class State {
        data object Idle : State()
        data class Available(val info: Info) : State()
        data class Downloading(val percent: Int) : State()
        data class Error(val msg: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)

    fun currentVersion(c: Context): Int = runCatching {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    /** Есть ли версия новее установленной. */
    suspend fun check(c: Context): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MoiFinansy")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")              // v1.0.7
            val ver = tag.substringAfterLast('.').toInt()
            val assets = json.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            if (url != null && ver > currentVersion(c)) Info(ver, tag.removePrefix("v"), url) else null
        }.getOrNull()
    }.also { info -> if (info != null && state.value !is State.Downloading) state.value = State.Available(info) }

    fun canInstall(c: Context) =
        Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        runCatching {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Скачать APK и открыть установщик. */
    suspend fun downloadAndInstall(c: Context, info: Info) {
        state.value = State.Downloading(0)
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(c.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "MoiFinansy-${info.version}.apk")
                var url = URL(info.url)
                var conn: HttpURLConnection
                var hops = 0
                while (true) {
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15_000; readTimeout = 30_000
                        setRequestProperty("User-Agent", "MoiFinansy")
                    }
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 5) {
                        url = URL(url, conn.getHeaderField("Location")); hops++; conn.disconnect()
                    } else break
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) state.value = State.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
                out
            }.getOrNull()
        }
        if (file == null) {
            state.value = State.Error("Не удалось скачать. Проверь интернет.")
            return
        }
        state.value = State.Available(info)
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", file)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
