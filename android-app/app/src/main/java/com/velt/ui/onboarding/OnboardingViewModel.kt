package com.velt.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.velt.data.remote.ApiResult
import com.velt.data.repository.AuthRepository

sealed interface VerifyOutcome {
    /** OTP correcto. [userCreated]=true → cuenta nueva (sigue onboarding); false → ya existía (a Home). */
    data class Ok(val userCreated: Boolean) : VerifyOutcome
    data object Error : VerifyOutcome
}

class OnboardingViewModel(private val repo: AuthRepository) : ViewModel() {

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

    /** Verifica el OTP con el endpoint unificado (login-or-create). */
    suspend fun verifyCode(code: String): VerifyOutcome {
        clearError()
        return when (val result = repo.verifyPhone(phoneE164, code)) {
            is ApiResult.Success -> VerifyOutcome.Ok(result.data)
            is ApiResult.Failure -> {
                errorMessage = verifyError(result.code, result.message)
                VerifyOutcome.Error
            }
            is ApiResult.NetworkError -> {
                errorMessage = "Sin conexión. Revisa tu internet e inténtalo de nuevo."
                VerifyOutcome.Error
            }
        }
    }

    private fun verifyError(code: String?, message: String?): String = when (code) {
        "auth_failed" -> "Código incorrecto o expirado. Pide uno nuevo e inténtalo de nuevo."
        "validation_error" -> "Datos inválidos."
        else -> message ?: "No se pudo verificar el código."
    }

    companion object {
        fun factory(repo: AuthRepository) = viewModelFactory {
            initializer { OnboardingViewModel(repo) }
        }
    }
}
