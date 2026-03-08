package com.example.virhuman.data

import com.tencent.mmkv.MMKV

object MMKVHelper {
    private val mmkv by lazy { MMKV.defaultMMKV() }

    private const val KEY_ACCOUNT = "account"
    private const val KEY_PASSWORD = "password"
    private const val KEY_REMEMBER = "remember"
    private const val KEY_DEVICE_CODE = "device_code"

    fun saveAccount(account: String, password: String, remember: Boolean) {
        mmkv.encode(KEY_ACCOUNT, account)
        mmkv.encode(KEY_REMEMBER, remember)
        if (remember) {
            mmkv.encode(KEY_PASSWORD, password)
        } else {
            mmkv.removeValueForKey(KEY_PASSWORD)
        }
    }

    fun getAccount(): String = mmkv.decodeString(KEY_ACCOUNT, "").orEmpty()

    fun getPassword(): String = mmkv.decodeString(KEY_PASSWORD, "").orEmpty()

    fun isRememberPassword(): Boolean = mmkv.decodeBool(KEY_REMEMBER, false)

    fun saveDeviceCode(code: String) {
        mmkv.encode(KEY_DEVICE_CODE, code)
    }

    fun getDeviceCode(): String = mmkv.decodeString(KEY_DEVICE_CODE, "").orEmpty()
}
