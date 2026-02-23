package com.example.nirduino_android_app_v2.auth

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.nirduino_android_app_v2.MainActivity
import com.example.nirduino_android_app_v2.databinding.ActivityEmailVerificationBinding
import com.google.firebase.auth.FirebaseAuth

class VerificationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEmailVerificationBinding

    private val auth = FirebaseAuth.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 2000L // 2 seconds

    private val verificationChecker = object : Runnable {
        override fun run() {
            checkVerification()
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnResend.setOnClickListener {
            FirebaseAuth.getInstance().currentUser
                ?.sendEmailVerification()
                ?.addOnSuccessListener {
                    toast("Verification email sent")
                }
        }

        binding.tvLogout.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    fun toast(message: String) {
        Toast.makeText(this@VerificationActivity, message, Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        handler.post(verificationChecker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(verificationChecker)
    }

    private fun checkVerification() {
        val user = auth.currentUser ?: return

        user.reload()
            .addOnSuccessListener {
                if (user.isEmailVerified) {
                    goToMain()
                }
            }
    }

    private fun goToMain() {
        handler.removeCallbacks(verificationChecker)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

}