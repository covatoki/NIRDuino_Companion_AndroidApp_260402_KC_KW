package com.example.nirduino_android_app_v2.auth.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.nirduino_android_app_v2.auth.repository.AuthRepository
import com.example.nirduino_android_app_v2.auth.utils.Result

class AuthViewModel : ViewModel() {

    private val repo = AuthRepository()

    private val _authState = MutableLiveData<Result<Any>>()
    val authState: LiveData<Result<Any>> = _authState

    fun login(email: String, password: String) {
        _authState.value = Result.Loading
        repo.login(email, password) {
            _authState.postValue(it)
        }
    }

    fun register(name: String, email: String, password: String) {
        _authState.value = Result.Loading
        repo.register(name, email, password) {
            _authState.postValue(it)
        }
    }

    fun googleLogin(idToken: String) {
        _authState.value = Result.Loading
        repo.googleLogin(idToken) {
            _authState.postValue(it)
        }
    }

    fun forgotPassword(email: String) {
        _authState.value = Result.Loading
        repo.forgotPassword(email) {
            _authState.postValue(it)
        }
    }
}
