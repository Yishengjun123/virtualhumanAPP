package com.example.virhuman.ui.splash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.virhuman.R
import com.example.virhuman.ai.asr.GlobalAsrManager
import com.example.virhuman.ui.login.LoginActivity
import kotlin.concurrent.thread

class SplashActivity : AppCompatActivity() {
    @Volatile
    private var permissionReady = false

    @Volatile
    private var warmupReady = false

    @Volatile
    private var routed = false

    private val startedAt = SystemClock.elapsedRealtime()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionReady = true
        val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true
        val locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!audioGranted) {
            Toast.makeText(this, R.string.permission_audio_tip, Toast.LENGTH_SHORT).show()
        }
        if (!locationGranted) {
            Toast.makeText(this, R.string.permission_location_tip, Toast.LENGTH_SHORT).show()
        }
        tryRouteNext()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        startWarmup()
        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val required = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionReady = true
            tryRouteNext()
            return
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startWarmup() {
        thread(start = true, name = "splash-warmup") {
            val prepared = GlobalAsrManager.ensurePreparedBlocking(applicationContext)
            warmupReady = true
            runOnUiThread {
                if (!prepared) {
                    Toast.makeText(this, R.string.asr_not_available, Toast.LENGTH_SHORT).show()
                }
                tryRouteNext()
            }
        }
    }

    private fun tryRouteNext() {
        if (routed) return
        if (!permissionReady || !warmupReady) return

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val remain = (500L - elapsed).coerceAtLeast(0L)
        window.decorView.postDelayed({
            if (routed || isFinishing) return@postDelayed
            routed = true
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }, remain)
    }
}
