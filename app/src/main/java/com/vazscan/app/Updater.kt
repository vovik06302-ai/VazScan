package com.vazscan.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val REPO = "vovik06302-ai/VazScan"

data class UpdateInfo(val tag: String, val code: Int, val apiUrl: String)

object Updater {
    private fun open(url: String, token: String?, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "VazScan")
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        c.connectTimeout = 15000
        c.readTimeout = 60000
        return c
    }

    /** Возвращает последний релиз или null, если версия не распознана. Бросает исключение при ошибке сети/доступа. */
    fun latest(token: String?): UpdateInfo? {
        val c = open("https://api.github.com/repos/$REPO/releases/latest", token, "application/vnd.github+json")
        if (c.responseCode != 200) throw IllegalStateException("GitHub ответил кодом ${c.responseCode}")
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val tag = o.getString("tag_name")
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
        val assets = o.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return UpdateInfo(tag, code, a.getString("url"))
            }
        }
        return null
    }

    fun download(info: UpdateInfo, token: String?, cacheDir: File, onProgress: (Float) -> Unit): File {
        var c = open(info.apiUrl, token, "application/octet-stream")
        c.instanceFollowRedirects = false
        if (c.responseCode in 300..399) {
            // GitHub отдаёт файл по временной ссылке на другом домене: открываем её без токена
            val loc = c.getHeaderField("Location")
            c.disconnect()
            c = URL(loc).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
        }
        if (c.responseCode != 200) throw IllegalStateException("Не удалось скачать APK: код ${c.responseCode}")
        val total = c.contentLengthLong
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "VazScan-${info.tag}.apk")
        c.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        return file
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
