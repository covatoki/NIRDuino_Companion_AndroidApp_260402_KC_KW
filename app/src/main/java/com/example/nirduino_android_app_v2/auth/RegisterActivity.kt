package com.example.nirduino_android_app_v2.auth

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.auth.utils.Result
import com.example.nirduino_android_app_v2.auth.utils.ValidationUtils
import com.example.nirduino_android_app_v2.auth.viewmodel.AuthViewModel
import com.example.nirduino_android_app_v2.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {
    private lateinit var viewModel: AuthViewModel
    private var loadingDialog: Dialog? = null
    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[AuthViewModel::class.java]

        observeAuthState()

        binding.btnRegister.setOnClickListener {

            val name = binding.etName.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            val confirmPassword = binding.etConfirmPassword.text.toString()

            when {
                name.length < 2 -> {
                    binding.etName.error = "Enter valid name"
                }

                !ValidationUtils.isValidEmail(email) -> {
                    binding.etEmail.error = "Enter valid email"
                }

                !ValidationUtils.isValidPassword(password) -> {
                    binding.etPassword.error =
                        "Password must be 8+ chars with letters & numbers"
                }

                password != confirmPassword -> {
                    binding.etConfirmPassword.error = "Passwords do not match"
                }

                else -> {
                    viewModel.register(name, email, password)
                }
            }
        }

    }

    private fun observeAuthState() {
        viewModel.authState.observe(this) {
            when (it) {
                is Result.Loading -> {
                    showLoading()
                    Log.d("RegisterScreen", "Loading")
                }

                is Result.Success -> {
                    hideLoading()
                    goToVerification()
                    Log.d("RegisterScreen", "Success")
                }

                is Result.Error -> Log.d("RegisterScreen", it.message)
            }
        }
    }

    private fun showLoading() {
        if (loadingDialog?.isShowing == true) return

        loadingDialog = Dialog(this).apply {
            setContentView(R.layout.loading_dialog)
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            show()
        }
    }

    private fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun goToVerification() {
        startActivity(Intent(this, VerificationActivity::class.java))
        finish()
    }
}