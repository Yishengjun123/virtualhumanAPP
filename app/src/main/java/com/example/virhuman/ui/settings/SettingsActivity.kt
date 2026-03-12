package com.example.virhuman.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.virhuman.BuildConfig
import com.example.virhuman.R
import com.example.virhuman.data.MMKVHelper
import com.example.virhuman.databinding.ActivitySettingsBinding
import com.example.virhuman.ui.login.LoginActivity

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

        binding.switchFaceDetect.isChecked = MMKVHelper.isFaceDetectEnabled()
        binding.switchFaceAutoDialog.isChecked = MMKVHelper.isFaceAutoDialogEnabled()
        binding.switchFaceAutoGreet.isChecked = MMKVHelper.isFaceAutoGreetEnabled()
        refreshFaceExtraVisibility()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCheckUpdate.setOnClickListener {
            Toast.makeText(this, R.string.already_latest, Toast.LENGTH_SHORT).show()
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

    private fun refreshFaceExtraVisibility() {
        binding.faceExtraOptions.visibility = if (binding.switchFaceDetect.isChecked) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
}
