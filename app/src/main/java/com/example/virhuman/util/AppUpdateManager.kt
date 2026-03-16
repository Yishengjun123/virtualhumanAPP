package com.example.virhuman.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.example.virhuman.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object AppUpdateManager {
    private const val MOCK_BASE_URL = "http://127.0.0.1:4523/m1/6421130-6118447-default"
    private const val CHECK_PATH = "/api/app/update"

    data class UpdateInfo(
        val latestVersionCode: Int,
        val latestVersionName: String,
        val updateLog: String,
        val apkUrl: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkUpdate(
        onLatest: () -> Unit,
        onHasUpdate: (UpdateInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        thread(start = true, name = "check-update") {
            try {
                val url = URL("$MOCK_BASE_URL$CHECK_PATH")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 10000
                    doInput = true
                }
                val httpCode = conn.responseCode
                val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val root = JSONObject(text)
                val code = root.optInt("code", -1)
                if (code != 0) {
                    throw IllegalStateException(root.optString("message", "检查更新失败"))
                }
                val data = root.optJSONObject("data")
                    ?: throw IllegalStateException("更新信息为空")

                val latestCode = data.optInt("latestVersionCode", 0)
                val latestName = data.optString("latestVersionName", "")
                val updateLog = data.optString("updateLog", "")
                val apkUrl = data.optString("apkUrl", "")

                if (latestCode <= BuildConfig.VERSION_CODE) {
                    postMain(onLatest)
                    return@thread
                }
                if (apkUrl.isBlank()) {
                    throw IllegalStateException("未提供 apkUrl")
                }
                postMain {
                    onHasUpdate(UpdateInfo(latestCode, latestName, updateLog, apkUrl))
                }
            } catch (e: Exception) {
                postMain {
                    onError(e.message ?: "检查更新失败")
                }
            }
        }
    }

    fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        thread(start = true, name = "download-apk") {
            try {
                val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val fileName = "virhuman-update-${info.latestVersionCode}.apk"
                val apkFile = File(targetDir, fileName)

                val normalizedUrl = info.apkUrl.trim().replace(" ", "%20")
                val conn = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 30000
                    doInput = true
                    setRequestProperty("User-Agent", "VirHumanUpdate/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "*/*")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errText = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("下载失败，HTTP $code ${if (errText.isBlank()) "" else "- $errText"}")
                }

                val totalBytes = conn.contentLengthLong
                postMain { onProgress(0) }

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var downloaded = 0L
                        var lastProgress = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            if (totalBytes > 0) {
                                val progress = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    postMain { onProgress(progress) }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                postMain { onProgress(100) }
                postMain { onSuccess(apkFile) }
            } catch (e: Exception) {
                postMain { onError(e.message ?: "下载失败") }
            }
        }
    }

    fun requestInstall(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    private fun postMain(block: () -> Unit) {
        mainHandler.post(block)
    }
}
