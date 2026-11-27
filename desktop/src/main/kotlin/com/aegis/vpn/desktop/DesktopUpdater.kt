package com.aegis.vpn.desktop

import com.google.gson.Gson
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object DesktopUpdater {
    const val APP_VERSION = "1.1.9"
    const val APP_VERSION_CODE = 11
    const val LATEST_JSON_URL = "https://raw.githubusercontent.com/Tonda88/aegis-vpn-releases/main/latest-desktop.json"

    data class ReleaseInfo(
        val version: String = "",
        val versionCode: Int = 0,
        val notes: String = "",
        val url: String = "",
        val sha256: String = ""
    )

    data class UpdateAvailable(val info: ReleaseInfo)

    private val gson = Gson()

    fun checkForUpdate(): Result<UpdateAvailable?> = runCatching {
        val connection = (URL(LATEST_JSON_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AegisVPN-Desktop/$APP_VERSION")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val info = gson.fromJson(body, ReleaseInfo::class.java) ?: error("Empty response")
            if (info.versionCode <= APP_VERSION_CODE || info.url.isBlank()) return@runCatching null
            UpdateAvailable(info.copy(sha256 = info.sha256.trim().lowercase()))
        } finally {
            connection.disconnect()
        }
    }

    fun downloadUpdate(
        info: ReleaseInfo,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = runCatching {
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AegisVPN-Desktop/updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        val fileName = info.url.substringAfterLast('/').ifBlank { "AegisVPN-Desktop-${info.version}.zip" }
        val outFile = File(dir, fileName)
        val connection = (URL(info.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "AegisVPN-Desktop/$APP_VERSION")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        if (read == 0) continue
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
        if (info.sha256.isNotBlank()) {
            val actual = sha256(outFile)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                outFile.delete()
                error("Checksum mismatch")
            }
        }
        outFile
    }

    fun openDownloaded(file: File) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file.parentFile ?: file)
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) {
                if (read > 0) md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

