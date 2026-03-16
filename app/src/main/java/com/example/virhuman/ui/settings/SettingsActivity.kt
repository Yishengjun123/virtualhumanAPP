package com.example.virhuman.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.virhuman.BuildConfig
import com.example.virhuman.R
import com.example.virhuman.data.MMKVHelper
import com.example.virhuman.databinding.ActivitySettingsBinding
import com.example.virhuman.ui.login.LoginActivity
import com.example.virhuman.util.AppUpdateManager

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var internalChanging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val deviceCode = MMKVHelper.getDeviceCode()
        binding.tvDeviceCode.text = getString(R.string.device_code_label, deviceCode)
        binding.tvVersion.text = getString(R.string.current_version_label, BuildConfig.VERSION_NAME)

        binding.switchContinuousDialog.isChecked = MMKVHelper.isContinuousDialogEnabled()
        binding.switchFaceDetect.isChecked = MMKVHelper.isFaceDetectEnabled()
        binding.switchFaceAutoDialog.isChecked = MMKVHelper.isFaceAutoDialogEnabled()
        binding.switchFaceAutoGreet.isChecked = MMKVHelper.isFaceAutoGreetEnabled()
        refreshFaceExtraVisibility()

        binding.btnBack.setOnClickListener { finish() }

        binding.switchContinuousDialog.setOnCheckedChangeListener { _, isChecked ->
            MMKVHelper.setContinuousDialogEnabled(isChecked)
        }

        binding.btnCheckUpdate.setOnClickListener {
            doCheckUpdate()
        }

        binding.switchFaceDetect.setOnCheckedChangeListener { _, isChecked ->
            MMKVHelper.setFaceDetectEnabled(isChecked)
            if (!isChecked) {
                internalChanging = true
                MMKVHelper.setFaceAutoDialogEnabled(false)
                MMKVHelper.setFaceAutoGreetEnabled(false)
                binding.switchFaceAutoDialog.isChecked = false
                binding.switchFaceAutoGreet.isChecked = false
                internalChanging = false
            }
            refreshFaceExtraVisibility()
        }

        binding.switchFaceAutoDialog.setOnCheckedChangeListener { _, isChecked ->
            if (internalChanging) return@setOnCheckedChangeListener
            MMKVHelper.setFaceAutoDialogEnabled(isChecked)
            if (isChecked) {
                internalChanging = true
                binding.switchFaceAutoGreet.isChecked = false
                internalChanging = false
            }
        }

        binding.switchFaceAutoGreet.setOnCheckedChangeListener { _, isChecked ->
            if (internalChanging) return@setOnCheckedChangeListener
            MMKVHelper.setFaceAutoGreetEnabled(isChecked)
            if (isChecked) {
                internalChanging = true
                binding.switchFaceAutoDialog.isChecked = false
                internalChanging = false
            }
        }

        binding.btnRelogin.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }

        binding.btnExit.setOnClickListener {
            finishAffinity()
        }
    }

    private fun doCheckUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        Toast.makeText(this, R.string.checking_update, Toast.LENGTH_SHORT).show()
        AppUpdateManager.checkUpdate(
            onLatest = {
                binding.btnCheckUpdate.isEnabled = true
                Toast.makeText(this, R.string.already_latest, Toast.LENGTH_SHORT).show()
            },
            onHasUpdate = { info ->
                binding.btnCheckUpdate.isEnabled = true
                val message = getString(
                    R.string.update_dialog_message,
                    info.latestVersionName,
                    info.latestVersionCode,
                    info.updateLog.ifBlank { getString(R.string.update_log_empty) }
                )
                AlertDialog.Builder(this)
                    .setTitle(R.string.update_dialog_title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setNegativeButton(R.string.update_later, null)
                    .setPositiveButton(R.string.update_now) { _, _ ->
                        showDownloadProgressDialog(info)
                    }
                    .show()
            },
            onError = { err ->
                binding.btnCheckUpdate.isEnabled = true
                Toast.makeText(this, getString(R.string.update_check_failed, err), Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showDownloadProgressDialog(info: AppUpdateManager.UpdateInfo) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 40, 56, 24)
        }
        val tv = TextView(this).apply {
            text = "${getString(R.string.update_downloading)} 0%"
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val progressParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 18
        }
        container.addView(tv)
        container.addView(progressBar, progressParams)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_downloading)
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()

        binding.btnCheckUpdate.isEnabled = false
        AppUpdateManager.downloadApk(
            context = this,
            info = info,
            onProgress = { p ->
                progressBar.progress = p
                tv.text = "${getString(R.string.update_downloading)} $p%"
            },
            onSuccess = { file ->
                binding.btnCheckUpdate.isEnabled = true
                dialog.dismiss()
                val started = AppUpdateManager.requestInstall(this, file)
                if (!started) {
                    Toast.makeText(this, R.string.update_enable_install_permission, Toast.LENGTH_LONG).show()
                }
            },
            onError = { err ->
                binding.btnCheckUpdate.isEnabled = true
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.update_download_failed, err), Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun refreshFaceExtraVisibility() {
        binding.faceExtraOptions.visibility = if (binding.switchFaceDetect.isChecked) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
}
