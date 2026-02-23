package com.example.nirduino_android_app_v2.auth.repository

import com.example.nirduino_android_app_v2.auth.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.example.nirduino_android_app_v2.auth.utils.Result
import com.google.firebase.auth.GoogleAuthProvider

class AuthRepository {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    fun login(
        email: String,
        password: String,
        callback: (Result<FirebaseUser>) -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                val user = auth.currentUser!!
                if (user.isEmailVerified) {
                    callback(Result.Success(user))
                } else {
                    callback(Result.Error("Email not verified"))
                }
            }
            .addOnFailureListener {
                callback(Result.Error(it.message ?: "Login failed"))
            }
    }

    fun register(
        name: String,
        email: String,
        password: String,
        callback: (Result<Unit>) -> Unit
    ) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                val firebaseUser = auth.currentUser!!
                firebaseUser.sendEmailVerification()

                val user = User(
                    uid = firebaseUser.uid,
                    name = name,
                    email = email,
                    isEmailVerified = false
                )

                db.collection("users")
                    .document(firebaseUser.uid)
                    .set(user)
                    .addOnSuccessListener { callback(Result.Success(Unit)) }
            }
            .addOnFailureListener {
                callback(Result.Error(it.message ?: "Registration failed"))
            }
    }

    private fun saveGoogleUserIfNeeded(user: FirebaseUser) {
        val ref = db.collection("users").document(user.uid)
        ref.get().addOnSuccessListener {
            if (!it.exists()) {
                ref.set(
                    User(
                        uid = user.uid,
                        name = user.displayName ?: "",
                        email = user.email ?: "",
                        isEmailVerified = true
                    )
                )
            }
        }
    }

    fun googleLogin(
        idToken: String,
        callback: (Result<FirebaseUser>) -> Unit
    ) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)

        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                val user = auth.currentUser!!
                saveGoogleUserIfNeeded(user)
                callback(Result.Success(user))
            }
            .addOnFailureListener {
                callback(Result.Error(it.message ?: "Google sign-in failed"))
            }
    }

    private fun saveGoogleUser(user: FirebaseUser) {
        val data = User(
            uid = user.uid,
            name = user.displayName ?: "",
            email = user.email ?: "",
            isEmailVerified = true
        )
        db.collection("users").document(user.uid).set(data)
    }

    fun forgotPassword(email: String, callback: (Result<Unit>) -> Unit) {
        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener { callback(Result.Success(Unit)) }
            .addOnFailureListener {
                callback(Result.Error(it.message ?: "Reset failed"))
            }
    }

    fun logout() = auth.signOut()
}
