package com.example.nirduino_android_app_v2.auth.utils

object ValidationUtils {

    fun isValidEmail(email: String): Boolean {
        return email.isNotBlank() &&
                android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    fun isValidPassword(password: String): Boolean {
        if (password.length < 8) return false
        if (!password.any { it.isLetter() }) return false
        if (!password.any { it.isDigit() }) return false
        return true
    }
}
