package com.example.nirduino_android_app_v2.auth.model

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val isEmailVerified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
