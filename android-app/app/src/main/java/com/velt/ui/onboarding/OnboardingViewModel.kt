package com.velt.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.velt.data.remote.ApiResult
import com.velt.data.repository.AuthRepository

enum class AuthMode { REGISTER, LOGIN }

class OnboardingViewModel(private val repo: AuthRepository) : ViewModel() {

    var authMode: AuthMode = AuthMode.REGISTER
    var phoneE164: String = ""
    var phoneDisplay: String = ""

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        errorMessage = null
    }

    suspend fun sendOtp(): Boolean {
        clearError()
        return when (val r = repo.sendOtp(phoneE164)) {
            is ApiResult.Success -> true
            is ApiResult.Failure -> {
                errorMessage = when (r.code) {
                    "invalid_phone", "validation_error" -> "Número de teléfono inválido."
                    else -> r.message ?: "No se pudo enviar el código."
                }
                false
            }
            is ApiResult.NetworkError -> {
                errorMessage = "Sin conexión. Revisa tu internet e inténtalo de nuevo."
                false
            }
        }
    }

    /** Verifica el OTP haciendo register o login según la intención del usuario. */
    suspend fun verifyCode(code: String): Boolean {
        clearError()
        val result = if (authMode == AuthMode.REGISTER) {
            repo.register(phoneE164, code)
        } else {
            repo.loginWithPhone(phoneE164, code)
        }
        return when (result) {
            is ApiResult.Success -> true
            is ApiResult.Failure -> {
                errorMessage = verifyError(result.code, result.message)
                false
            }
            is ApiResult.NetworkError -> {
                errorMessage = "Sin conexión. Revisa tu internet e inténtalo de nuevo."
                false
            }
        }
    }

    private fun verifyError(code: String?, message: String?): String = when (code) {
        "auth_failed" -> "Código incorrecto. Revísalo e inténtalo de nuevo."
        "identity_already_registered" -> "Ya tienes una cuenta con este número. Inicia sesión."
        "unknown_identity" -> "Este número no está registrado. Crea una cuenta gratis."
        "validation_error" -> "Datos inválidos."
        else -> message ?: "No se pudo verificar el código."
    }

    companion object {
        fun factory(repo: AuthRepository) = viewModelFactory {
            initializer { OnboardingViewModel(repo) }
        }
    }
}
