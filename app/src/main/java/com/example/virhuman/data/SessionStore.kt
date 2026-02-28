package com.example.virhuman.data

import android.content.Context

class SessionStore(context: Context) {
    private val sp = context.getSharedPreferences("virhuman_prefs", Context.MODE_PRIVATE)

    fun saveAccount(account: String, password: String, remember: Boolean) {
        sp.edit()
            .putString(KEY_ACCOUNT, account)
            .putBoolean(KEY_REMEMBER, remember)
            .apply()
        if (remember) {
            sp.edit().putString(KEY_PASSWORD, password).apply()
        } else {
            sp.edit().remove(KEY_PASSWORD).apply()
        }
    }

    fun getAccount(): String = sp.getString(KEY_ACCOUNT, "").orEmpty()

    fun getPassword(): String = sp.getString(KEY_PASSWORD, "").orEmpty()

    fun isRememberPassword(): Boolean = sp.getBoolean(KEY_REMEMBER, false)

    companion object {
        private const val KEY_ACCOUNT = "account"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBER = "remember"
    }
}
