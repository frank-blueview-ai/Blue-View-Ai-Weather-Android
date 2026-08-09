package ai.blueview.weather.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val RELEASES_API =
    "https://api.github.com/repos/frank-blueview-ai/Blue-View-Ai-Weather-Android/releases/latest"

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("assets")   val assets: List<GithubAsset>
)

@Serializable
data class GithubAsset(
    @SerialName("name")                 val name: String,
    @SerialName("browser_download_url") val downloadUrl: String
)

sealed class UpdateState {
    object Idle                                           : UpdateState()
    object Checking                                       : UpdateState()
    object UpToDate                                       : UpdateState()
    data class Available(val version: String, val url: String) : UpdateState()
    data class Downloading(val downloadId: Long)          : UpdateState()
    data class ReadyToInstall(val file: File)             : UpdateState()
    data class Error(val message: String)                 : UpdateState()
}

@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkLatest(currentVersion: String): UpdateState = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = okHttpClient.newCall(request).execute().use { it.body?.string() }
                ?: return@withContext UpdateState.Error("Empty response")
            val release = json.decodeFromString<GithubRelease>(body)
            val latest = release.tagName.trimStart('v')
            val current = currentVersion.trimStart('v')
            if (isNewer(latest, current)) {
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: return@withContext UpdateState.Error("No APK in release")
                UpdateState.Available(release.tagName, apkAsset.downloadUrl)
            } else {
                UpdateState.UpToDate
            }
        } catch (e: Exception) {
            UpdateState.Error(e.message ?: "Check failed")
        }
    }

    fun startDownload(url: String, version: String): Long {
        val destFile = File(context.externalCacheDir, "blueview-weather-$version.apk")
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Blue View Weather $version")
            .setDescription("Downloading update…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destFile))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun pollDownload(downloadId: Long): UpdateState? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val localUri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                    )
                    UpdateState.ReadyToInstall(File(Uri.parse(localUri).path!!))
                }
                DownloadManager.STATUS_FAILED -> UpdateState.Error("Download failed")
                else -> null // still in progress
            }
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }
}
