package com.example.virhuman

import android.app.Application
import com.tencent.mmkv.MMKV

class VirHumanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
    }
}
