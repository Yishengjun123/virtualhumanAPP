package com.example.virhuman.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.virhuman.R
import com.example.virhuman.data.SessionStore
import com.example.virhuman.databinding.ActivityLoginBinding
import com.example.virhuman.ui.main.MainActivity

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val sessionStore by lazy { SessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindSavedAccount()

        binding.btnLogin.setOnClickListener {
            val account = binding.etAccount.text?.toString()?.trim().orEmpty()
            val password = binding.etPassword.text?.toString()?.trim().orEmpty()
            if (account.isBlank() || password.isBlank()) {
                Toast.makeText(this, getString(R.string.login_empty_error), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sessionStore.saveAccount(account, password, binding.cbRememberPassword.isChecked)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun bindSavedAccount() {
        binding.cbRememberPassword.isChecked = sessionStore.isRememberPassword()
        binding.etAccount.setText(sessionStore.getAccount())
        if (sessionStore.isRememberPassword()) {
            binding.etPassword.setText(sessionStore.getPassword())
        }
    }
}

