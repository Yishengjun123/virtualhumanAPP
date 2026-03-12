package com.example.virhuman.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import java.util.Locale

object WifiMonitor {
    private const val CHECK_INTERVAL = 1300L
    private var isMonitoring = false
    private var updateCallback: ((String, String, String, String) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0
    private var lastTime: Long = 0

    private val runnable = object : Runnable {
        override fun run() {
            checkNetworkStatus()
            if (isMonitoring) {
                handler.postDelayed(this, CHECK_INTERVAL)
            }
        }
    }

    fun init(
        context: Context,
        callback: (String, String, String, String) -> Unit
    ) {
        appContext = context.applicationContext
        updateCallback = callback
    }

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        lastRxBytes = getUidRxBytes()
        lastTxBytes = getUidTxBytes()
        lastTime = System.currentTimeMillis()
        handler.post(runnable)
    }

    fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacks(runnable)
    }

    private fun checkNetworkStatus() {
        val context = appContext ?: return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val connected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val status = if (connected) "网络状态：已连接" else "网络状态：未连接"

        val networkName = when {
            caps == null -> "网络：--"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> readWifiName(context)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "网络：移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "网络：有线网络"
            else -> "网络：已连接"
        }

        val (downloadSpeed, uploadSpeed) = calculateAppNetworkSpeed(connected)
        updateCallback?.invoke(status, networkName, downloadSpeed, uploadSpeed)
    }

    private fun readWifiName(context: Context): String {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            return "Wi-Fi：缺少定位权限"
        }

        if (!isLocationEnabled(context)) {
            return "Wi-Fi：定位未开启"
        }

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid?.replace("\"", "").orEmpty()
        return if (ssid.isNotBlank() && ssid != "<unknown ssid>") {
            "Wi-Fi：$ssid"
        } else {
            "Wi-Fi：已连接"
        }
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun calculateAppNetworkSpeed(connected: Boolean): Pair<String, String> {
        if (!connected) {
            lastTime = System.currentTimeMillis()
            lastRxBytes = getUidRxBytes().coerceAtLeast(0)
            lastTxBytes = getUidTxBytes().coerceAtLeast(0)
            return Pair("下行：0.00 KB/s", "上行：0.00 KB/s")
        }

        val currentRxBytes = getUidRxBytes()
        val currentTxBytes = getUidTxBytes()
        val currentTime = System.currentTimeMillis()

        if (currentRxBytes < 0 || currentTxBytes < 0) {
            return Pair("下行：-- KB/s", "上行：-- KB/s")
        }

        if (lastTime == 0L) {
            lastRxBytes = currentRxBytes
            lastTxBytes = currentTxBytes
            lastTime = currentTime
            return Pair("下行：-- KB/s", "上行：-- KB/s")
        }

        val timeDiff = currentTime - lastTime
        if (timeDiff <= 0) return Pair("下行：-- KB/s", "上行：-- KB/s")

        val rxSpeed = ((currentRxBytes - lastRxBytes) * 1000.0) / timeDiff
        val txSpeed = ((currentTxBytes - lastTxBytes) * 1000.0) / timeDiff

        val rxSpeedKb = (rxSpeed / 1024.0).coerceAtLeast(0.0)
        val txSpeedKb = (txSpeed / 1024.0).coerceAtLeast(0.0)

        val down = String.format(Locale.US, "下行：%.2f KB/s", rxSpeedKb)
        val up = String.format(Locale.US, "上行：%.2f KB/s", txSpeedKb)

        lastRxBytes = currentRxBytes
        lastTxBytes = currentTxBytes
        lastTime = currentTime

        return Pair(down, up)
    }

    private fun getUidRxBytes(): Long = TrafficStats.getUidRxBytes(Process.myUid())

    private fun getUidTxBytes(): Long = TrafficStats.getUidTxBytes(Process.myUid())
}
