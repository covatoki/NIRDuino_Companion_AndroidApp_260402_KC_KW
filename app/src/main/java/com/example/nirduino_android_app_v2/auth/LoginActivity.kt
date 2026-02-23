package com.example.nirduino_android_app_v2.auth

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.nirduino_android_app_v2.MainActivity
import com.example.nirduino_android_app_v2.R
import com.example.nirduino_android_app_v2.auth.utils.Result
import com.example.nirduino_android_app_v2.auth.utils.ValidationUtils
import com.example.nirduino_android_app_v2.auth.viewmodel.AuthViewModel
import com.example.nirduino_android_app_v2.databinding.ActivityLoginBinding
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var viewModel: AuthViewModel
    private lateinit var credentialManager: CredentialManager
    private var loadingDialog: Dialog? = null
    private lateinit var binding: ActivityLoginBinding

    enum class AuthAction {
        LOGIN, FORGOT_PASSWORD
    }

    var currentAuthAction: AuthAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkUserSession()

        viewModel = ViewModelProvider(this)[AuthViewModel::class.java]

        credentialManager = CredentialManager.create(this)

        binding.btnGoogle.setOnClickListener {
            currentAuthAction = AuthAction.LOGIN
            startGoogleSignIn()
        }

        binding.btnRegister.setOnClickListener {
            startActivity(Intent(this@LoginActivity, RegisterActivity::class.java))
        }

        binding.btnLogin.setOnClickListener {
            currentAuthAction = AuthAction.LOGIN

            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()

            when {
                !ValidationUtils.isValidEmail(email) -> {
                    binding.etEmail.error = "Enter valid email"
                }

                !ValidationUtils.isValidPassword(password) -> {
                    binding.etPassword.error =
                        "Password must be 8+ chars with letters & numbers"
                }

                else -> {
                    viewModel.login(email, password)
                }
            }
        }

        binding.btnForgotPassword.setOnClickListener {
            currentAuthAction = AuthAction.FORGOT_PASSWORD

            showForgotPasswordDialog()
        }

        observeAuthState()
    }

    private fun observeAuthState() {
        viewModel.authState.observe(this) {
            when (it) {
                is Result.Loading -> {
                    showLoading()
                    Log.d("LoginScreen", "Loading")
                }

                is Result.Success -> {
                    hideLoading()
                    if (currentAuthAction == AuthAction.LOGIN) {
                        goToMain()
                    } else if (currentAuthAction == AuthAction.FORGOT_PASSWORD) {
                        Toast.makeText(
                            this,
                            "Check your email—we’ve just sent you a password reset link.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    Log.d("LoginScreen", "Success")
                }

                is Result.Error -> {
                    if (currentAuthAction == AuthAction.LOGIN) {
                        if (it.message == "Email not verified") {
                            hideLoading()
                            goToVerification()
                        }
                    }
                    Toast.makeText(
                        this,
                        it.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startGoogleSignIn() {
        lifecycleScope.launch {
            try {
                // 1️⃣ Try existing Google account
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(getString(R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    context = this@LoginActivity,
                    request = request
                )

                handleSignIn(result.credential)

            } catch (e: NoCredentialException) {
                // 2️⃣ No Google account → show Google Sign-In UI
                launchGoogleSignInUi()

            } catch (e: GetCredentialException) {
                Log.e("AUTH", "Credential error", e)
            }
        }
    }

    private suspend fun launchGoogleSignInUi() {
        try {
            val signInOption = GetSignInWithGoogleOption.Builder(
                serverClientId = getString(R.string.default_web_client_id)
            ).build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val result = credentialManager.getCredential(
                context = this,
                request = request
            )

            handleSignIn(result.credential)

        } catch (e: Exception) {
            Log.e("AUTH", "Google Sign-In UI failed", e)
        }
    }

    private fun handleSignIn(credential: Credential) {
        if (
            credential is CustomCredential &&
            credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential =
                GoogleIdTokenCredential.createFrom(credential.data)

            viewModel.googleLogin(googleIdTokenCredential.idToken)

        } else {
            Log.w("AUTH", "Not a Google ID Token")
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

    private fun checkUserSession() {
        val user = FirebaseAuth.getInstance().currentUser

        if (user != null) {
            if (user.isEmailVerified) {
                goToMain()
            } else {
                goToVerification()
            }

            return
        }
    }

    private fun showForgotPasswordDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_forgot_password)
        dialog.setCancelable(true)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val etEmail = dialog.findViewById<EditText>(R.id.etEmail)
        val btnOk = dialog.findViewById<LinearLayout>(R.id.btnForgot)

        btnOk.setOnClickListener {
            val email = etEmail.text.toString().trim()

            if (email.isEmpty()) {
                etEmail.error = "Email required"
                return@setOnClickListener
            }

            // 🔐 Call Firebase / API reset password
            viewModel.forgotPassword(email)

            dialog.dismiss()
        }

        dialog.show()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun goToVerification() {
        startActivity(Intent(this, VerificationActivity::class.java))
        finish()
    }
}