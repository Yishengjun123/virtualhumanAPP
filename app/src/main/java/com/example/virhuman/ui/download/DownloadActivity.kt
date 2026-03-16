package com.example.virhuman.ui.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.virhuman.R
import com.example.virhuman.data.MMKVHelper
import com.example.virhuman.data.ResourceParser
import com.example.virhuman.databinding.ActivityDownloadBinding
import com.example.virhuman.ui.main.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class DownloadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadBinding
    private val tag = "ResourceDownload"

    private data class DownloadTask(
        val characterId: String,
        val type: String,
        val url: String,
        val targetFile: File
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.progressBar.max = 100
        startDownloadFlow()
    }

    private fun startDownloadFlow() {
        thread(start = true, name = "download-flow") {
            try {
                val resourceJson = MMKVHelper.getResourceJson()
                if (resourceJson.isBlank()) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.resource_empty, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@thread
                }

                val parsed = ResourceParser.parseResponse(resourceJson)
                if (parsed.code != 0 || parsed.characters.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.resource_empty, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@thread
                }

                val baseDir = File(filesDir, "digital_human")
                if (!baseDir.exists()) baseDir.mkdirs()

                val tasks = mutableListOf<DownloadTask>()
                parsed.characters.forEach { character ->
                    val characterDir = File(baseDir, character.id)
                    if (!characterDir.exists()) characterDir.mkdirs()

                    tasks += DownloadTask(
                        characterId = character.id,
                        type = "leisure",
                        url = character.leisureUrl,
                        targetFile = File(characterDir, "leisure.mp4")
                    )
                    tasks += DownloadTask(
                        characterId = character.id,
                        type = "listening",
                        url = character.listeningUrl,
                        targetFile = File(characterDir, "listening.mp4")
                    )
                    tasks += DownloadTask(
                        characterId = character.id,
                        type = "speaking",
                        url = character.speakingUrl,
                        targetFile = File(characterDir, "speaking.mp4")
                    )

                    val pictureExt = guessExt(character.picture, ".jpg")
                    tasks += DownloadTask(
                        characterId = character.id,
                        type = "picture",
                        url = character.picture,
                        targetFile = File(characterDir, "picture$pictureExt")
                    )
                }

                val total = tasks.size.coerceAtLeast(1)
                var completed = 0

                for (task in tasks) {
                    runOnUiThread {
                        binding.tvStatus.text = getString(R.string.resource_downloading_item, task.characterId, task.type)
                    }
                    downloadFile(task.url, task.targetFile)
                    if (task.type == "picture") {
                        MMKVHelper.savePictureLocalPath(task.characterId, task.targetFile.absolutePath)
                    } else {
                        MMKVHelper.saveVideoLocalPath(task.characterId, task.type, task.targetFile.absolutePath)
                    }

                    completed += 1
                    val progress = (completed * 100 / total)
                    runOnUiThread {
                        binding.progressBar.progress = progress
                        binding.tvProgress.text = getString(R.string.resource_progress_percent, progress)
                    }
                }

                if (MMKVHelper.getCurrentCharacterId().isBlank()) {
                    MMKVHelper.saveCurrentCharacterId(parsed.characters.first().id)
                }

                runOnUiThread {
                    binding.tvStatus.text = getString(R.string.resource_download_done)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                Log.e(tag, "download failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.resource_download_failed, e.message), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun downloadFile(urlText: String, outputFile: File) {
        if (urlText.isBlank()) {
            throw IllegalArgumentException("empty url")
        }

        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
        }

        val tmp = File(outputFile.parentFile, outputFile.name + ".tmp")
        connection.inputStream.use { input ->
            FileOutputStream(tmp).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val len = input.read(buffer)
                    if (len <= 0) break
                    output.write(buffer, 0, len)
                }
                output.flush()
            }
        }

        if (outputFile.exists()) {
            outputFile.delete()
        }
        if (!tmp.renameTo(outputFile)) {
            throw IllegalStateException("rename failed: ${outputFile.absolutePath}")
        }
    }

    private fun guessExt(url: String, fallback: String): String {
        return try {
            val path = Uri.parse(url).lastPathSegment.orEmpty()
            val dot = path.lastIndexOf('.')
            if (dot >= 0) path.substring(dot) else fallback
        } catch (_: Exception) {
            fallback
        }
    }
}

