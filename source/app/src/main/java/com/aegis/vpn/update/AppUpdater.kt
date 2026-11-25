package com.aegis.vpn.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.aegis.vpn.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdater {
    const val LATEST_JSON_URL =
        "https://raw.githubusercontent.com/Tonda88/aegis-vpn-releases/main/latest.json"

    data class ReleaseInfo(
        val versionCode: Int = 0,
        val versionName: String = "",
        val notes: String = "",
        val arm64Url: String = "",
        val arm64Sha256: String = "",
        val armeabiV7aUrl: String = "",
        val armeabiV7aSha256: String = ""
    )

    data class UpdateAvailable(
        val info: ReleaseInfo,
        val apkUrl: String,
        val sha256: String
    )

    private val gson = Gson()

    private const val KEY_PENDING_FILE = "pending_update_file"
    private const val KEY_PENDING_VERSION = "pending_update_version"

    suspend fun checkForUpdate(): Result<UpdateAvailable?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(LATEST_JSON_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AegisVPN/${BuildConfig.VERSION_NAME}")
                instanceFollowRedirects = true
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("HTTP $code")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val info = gson.fromJson(body, ReleaseInfo::class.java) ?: error("Empty response")
                if (info.versionCode <= BuildConfig.VERSION_CODE) return@runCatching null
                val abi = preferredAbi()
                val (url, sha) = when {
                    abi == "arm64-v8a" && info.arm64Url.isNotBlank() -> info.arm64Url to info.arm64Sha256
                    abi == "armeabi-v7a" && info.armeabiV7aUrl.isNotBlank() -> info.armeabiV7aUrl to info.armeabiV7aSha256
                    info.arm64Url.isNotBlank() -> info.arm64Url to info.arm64Sha256
                    info.armeabiV7aUrl.isNotBlank() -> info.armeabiV7aUrl to info.armeabiV7aSha256
                    else -> error("No APK URL for this device")
                }
                UpdateAvailable(info = info, apkUrl = url, sha256 = sha.trim().lowercase())
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun downloadApk(
        context: Context,
        update: UpdateAvailable,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val outFile = File(dir, "AegisVPN-${update.info.versionName}.apk")
            val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "AegisVPN/${BuildConfig.VERSION_NAME}")
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
            if (update.sha256.isNotBlank()) {
                val actual = sha256(outFile)
                if (!actual.equals(update.sha256, ignoreCase = true)) {
                    outFile.delete()
                    error("Checksum mismatch")
                }
            }
            outFile
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun unknownAppSourcesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun installIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Некоторые оболочки (MIUI, OneUI) не наследуют разрешение на URI от
        // флага. Выдаём его явно каждому установщику, который может открыть APK.
        runCatching {
            context.packageManager.queryIntentActivities(intent, 0).forEach { info ->
                info.activityInfo.packageName?.let { pkg ->
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        return intent
    }

    sealed interface InstallResult {
        object Started : InstallResult
        object NeedPermission : InstallResult
        object NoFile : InstallResult
        data class Failed(val message: String) : InstallResult
    }

    /**
     * Запоминаем скачанный APK, чтобы можно было повторить установку после
     * возврата из системных настроек «Установка неизвестных приложений».
     */
    fun rememberPending(context: Context, file: File, versionName: String) {
        updatePrefs(context).edit()
            .putString(KEY_PENDING_FILE, file.absolutePath)
            .putString(KEY_PENDING_VERSION, versionName)
            .apply()
    }

    fun clearPending(context: Context) {
        updatePrefs(context).edit()
            .remove(KEY_PENDING_FILE)
            .remove(KEY_PENDING_VERSION)
            .apply()
    }

    /** Пытается открыть установщик. Возвращает причину, если не получилось. */
    fun tryInstall(context: Context): InstallResult {
        val prefs = updatePrefs(context)
        val path = prefs.getString(KEY_PENDING_FILE, null) ?: return InstallResult.NoFile
        // Уже установлены — старый файл больше не нужен.
        if (prefs.getString(KEY_PENDING_VERSION, null) == BuildConfig.VERSION_NAME) {
            clearPending(context)
            return InstallResult.NoFile
        }
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) {
            clearPending(context)
            return InstallResult.NoFile
        }
        // Битый файл не открыть через установщик — сообщим об этом явно.
        if (runCatching { context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull() == null) {
            clearPending(context)
            return InstallResult.Failed("APK повреждён или несовместим с устройством")
        }
        if (!canRequestPackageInstalls(context)) return InstallResult.NeedPermission
        return runCatching {
            context.startActivity(installIntent(context, file))
            InstallResult.Started
        }.getOrElse { InstallResult.Failed(it.message ?: "неизвестная ошибка") }
    }

    private fun updatePrefs(context: Context) =
        context.getSharedPreferences("aegis_update", Context.MODE_PRIVATE)

    private fun preferredAbi(): String {
        val abis = Build.SUPPORTED_ABIS
        return when {
            abis.any { it == "arm64-v8a" } -> "arm64-v8a"
            abis.any { it == "armeabi-v7a" } -> "armeabi-v7a"
            else -> abis.firstOrNull().orEmpty()
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
