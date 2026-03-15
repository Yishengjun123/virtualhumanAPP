package com.example.virhuman.ui.login

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.virhuman.R
import com.example.virhuman.data.MMKVHelper
import com.example.virhuman.data.ResourceParser
import com.example.virhuman.databinding.ActivityLoginBinding
import com.example.virhuman.ui.download.DownloadActivity
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val tag = "LoginAuth"
    private val mockBaseUrl = "http://127.0.0.1:4523/m1/6421130-6118447-default"
    private var localDeviceCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindSavedAccount()
        MMKVHelper.saveDeviceCode(Build.DEVICE.orEmpty())
        localDeviceCode = MMKVHelper.getDeviceCode()
        binding.tvDeviceCode.text = getString(R.string.device_code_label, localDeviceCode)

        binding.btnLogin.setOnClickListener {
            val account = binding.etAccount.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString()?.trim().orEmpty()
            if (account.isBlank() || password.isBlank()) {
                Toast.makeText(this, getString(R.string.login_empty_error), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            doLogin(account, password)
        }
    }

    private fun bindSavedAccount() {
        binding.cbRememberPassword.isChecked = MMKVHelper.isRememberPassword()
        binding.etAccount.setText(MMKVHelper.getAccount())
        if (MMKVHelper.isRememberPassword()) {
            binding.etPassword.setText(MMKVHelper.getPassword())
        }
    }

    private fun doLogin(account: String, password: String) {
        binding.btnLogin.isEnabled = false
        thread(start = true, name = "login-request") {
            var errorMessage: String? = null
            var showRequestError = false
            try {
                val loginJson = requestLogin(account, password)
                val bizCode = loginJson.optInt("code", -1)
                if (bizCode != 0) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.login_invalid_account, Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                val data = loginJson.optJSONObject("data")
                val deviceArray = data?.optJSONArray("device")
                var hasDevice = false
                if (deviceArray != null) {
                    val target = localDeviceCode.trim().uppercase()
                    for (i in 0 until deviceArray.length()) {
                        val remote = deviceArray.optString(i).trim().uppercase()
                        if (remote == target) {
                            hasDevice = true
                            break
                        }
                    }
                }
                if (!hasDevice) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.login_no_device, Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                val resourceText = requestResources(account, localDeviceCode)
                val resourceParsed = ResourceParser.parseResponse(resourceText)
                if (resourceParsed.code != 0 || resourceParsed.characters.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.resource_empty, Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                runOnUiThread {
                    MMKVHelper.saveAccount(account, password, binding.cbRememberPassword.isChecked)
                    MMKVHelper.saveResourceJson(resourceText)
                    startActivity(Intent(this, DownloadActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                Log.e(tag, "login flow failed: ${e.message}", e)
                errorMessage = e.message ?: "unknown"
                showRequestError = true
            } finally {
                runOnUiThread {
                    binding.btnLogin.isEnabled = true
                    if (showRequestError && !errorMessage.isNullOrBlank()) {
                        Toast.makeText(
                            this,
                            getString(R.string.login_request_failed, errorMessage),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun requestLogin(account: String, password: String): JSONObject {
        val url = URL("$mockBaseUrl/api/auth/login")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        val body = JSONObject().apply {
            put("username", account)
            put("password", password)
        }.toString()

        BufferedWriter(OutputStreamWriter(connection.outputStream, Charsets.UTF_8)).use {
            it.write(body)
            it.flush()
        }

        val httpCode = connection.responseCode
        val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        Log.d(tag, "login response: $responseText")
        return JSONObject(responseText)
    }

    private fun requestResources(account: String, deviceCode: String): String {
        val query = "username=${URLEncoder.encode(account, "UTF-8")}&device=${URLEncoder.encode(deviceCode, "UTF-8")}"
        val url = URL("$mockBaseUrl/api/resources?$query")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 15000
            doInput = true
        }
        val httpCode = connection.responseCode
        val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        Log.d(tag, "resources response: $responseText")
        return responseText
    }
}
