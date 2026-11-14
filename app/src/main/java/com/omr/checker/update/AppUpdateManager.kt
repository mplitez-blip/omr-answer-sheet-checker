package com.omr.checker.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val latestVersion: String,
    val releaseTitle: String,
    val changelog: String,
    val downloadUrl: String,
    val isNewer: Boolean
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {

    private const val GITHUB_OWNER = "mplitez-blip"
    private const val GITHUB_REPO = "omr-answer-sheet-checker"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /**
     * Checks GitHub Releases for the latest version and compares with current version.
     */
    suspend fun checkForUpdates(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(RELEASES_API_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "OMR-Sheet-Checker-App")
            }

            val responseCode = connection.responseCode
            if (responseCode == 404) {
                return@withContext UpdateCheckResult.UpToDate(currentVersion)
            }
            if (responseCode != 200) {
                return@withContext UpdateCheckResult.Error("Server returned code: $responseCode")
            }

            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JsonParser.parseString(jsonText).asJsonObject

            val tagName = root.get("tag_name")?.asString ?: "1.0.0"
            val releaseTitle = root.get("name")?.asString ?: "Release $tagName"
            val body = root.get("body")?.asString ?: "No changelog provided."

            var apkDownloadUrl = ""
            val assets = root.getAsJsonArray("assets")
            if (assets != null && assets.size() > 0) {
                for (assetElement in assets) {
                    val asset = assetElement.asJsonObject
                    val name = asset.get("name")?.asString ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = asset.get("browser_download_url")?.asString ?: ""
                        break
                    }
                }
            }

            // Fallback if no specific asset matched
            if (apkDownloadUrl.isBlank()) {
                apkDownloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/app-release.apk"
            }

            val isNewer = compareVersions(tagName, currentVersion) > 0

            val info = UpdateInfo(
                latestVersion = tagName,
                releaseTitle = releaseTitle,
                changelog = body,
                downloadUrl = apkDownloadUrl,
                isNewer = isNewer
            )

            if (isNewer) {
                UpdateCheckResult.UpdateAvailable(info)
            } else {
                UpdateCheckResult.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.localizedMessage ?: "Failed to check for updates")
        }
    }

    /**
     * Downloads the APK file from GitHub Release into internal cache directory.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        versionTag: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val cleanTag = versionTag.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
        val apkFile = File(updatesDir, "OMRChecker_$cleanTag.apk")

        val url = URL(downloadUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "OMR-Sheet-Checker-App")
        }

        connection.connect()

        val responseCode = connection.responseCode
        // Handle redirect if needed (GitHub redirects download URLs to AWS S3)
        val finalConnection = if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
            responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
            responseCode == 307 || responseCode == 308) {
            val newUrl = connection.getHeaderField("Location")
            (URL(newUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "OMR-Sheet-Checker-App")
                connect()
            }
        } else {
            connection
        }

        val totalLength = finalConnection.contentLength
        var downloadedBytes = 0

        finalConnection.inputStream.use { input ->
            FileOutputStream(apkFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalLength > 0) {
                        onProgress(downloadedBytes.toFloat() / totalLength)
                    }
                }
            }
        }

        apkFile
    }

    /**
     * Launches system Package Installer for the downloaded APK file.
     */
    fun installApk(context: Context, apkFile: File) {
        // Android 8.0+ Check for unknown app install permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }

    /**
     * Compares semantic versions like "v1.0.2" and "1.0.0".
     * Returns > 0 if v1 > v2, < 0 if v1 < v2, 0 if equal.
     */
    fun compareVersions(v1: String, v2: String): Int {
        val s1 = v1.removePrefix("v").removePrefix("V").split("-")[0].split(".")
        val s2 = v2.removePrefix("v").removePrefix("V").split("-")[0].split(".")

        val maxLen = maxOf(s1.size, s2.size)
        for (i in 0 until maxLen) {
            val num1 = s1.getOrNull(i)?.toIntOrNull() ?: 0
            val num2 = s2.getOrNull(i)?.toIntOrNull() ?: 0
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }
        return 0
    }
}
