package com.velt.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.velt.data.remote.ApiResult
import com.velt.data.repository.AuthRepository
import com.velt.ui.i18n.tr

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
                    "invalid_phone", "validation_error" ->
                        tr("Invalid phone number.", "Número de teléfono inválido.")
                    else -> r.message ?: tr("Couldn't send the code.", "No se pudo enviar el código.")
                }
                false
            }
            is ApiResult.NetworkError -> {
                errorMessage = tr(
                    "No connection. Check your internet and try again.",
                    "Sin conexión. Revisa tu internet e inténtalo de nuevo."
                )
                false
            }
        }
    }

    /** Login por palma (login-or-create). Devuelve `userCreated`. */
    suspend fun loginWithPalm(template: String): VerifyOutcome {
        clearError()
        return when (val result = repo.verifyPalm(template)) {
            is ApiResult.Success -> VerifyOutcome.Ok(result.data)
            is ApiResult.Failure -> {
                errorMessage = when (result.code) {
                    "auth_failed" -> tr(
                        "Palm not recognized. Make sure you're enrolled and try again.",
                        "Palma no reconocida. Asegúrate de estar registrado e inténtalo de nuevo."
                    )
                    else -> result.message ?: tr("Couldn't verify your palm.", "No se pudo verificar tu palma.")
                }
                VerifyOutcome.Error
            }
            is ApiResult.NetworkError -> {
                errorMessage = tr(
                    "No connection. Check your internet and try again.",
                    "Sin conexión. Revisa tu internet e inténtalo de nuevo."
                )
                VerifyOutcome.Error
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
                errorMessage = tr(
                    "No connection. Check your internet and try again.",
                    "Sin conexión. Revisa tu internet e inténtalo de nuevo."
                )
                VerifyOutcome.Error
            }
        }
    }

    /** Vincula el teléfono como recuperación (la cuenta ya está logueada por palma). */
    suspend fun linkPhoneRecovery(code: String): Boolean {
        clearError()
        return when (val result = repo.linkPhone(phoneE164, code)) {
            is ApiResult.Success -> true
            is ApiResult.Failure -> {
                errorMessage = verifyError(result.code, result.message)
                false
            }
            is ApiResult.NetworkError -> {
                errorMessage = tr(
                    "No connection. Check your internet and try again.",
                    "Sin conexión. Revisa tu internet e inténtalo de nuevo."
                )
                false
            }
        }
    }

    private fun verifyError(code: String?, message: String?): String = when (code) {
        "auth_failed" -> tr(
            "Incorrect or expired code. Request a new one and try again.",
            "Código incorrecto o expirado. Pide uno nuevo e inténtalo de nuevo."
        )
        "validation_error" -> tr("Invalid data.", "Datos inválidos.")
        else -> message ?: tr("Couldn't verify the code.", "No se pudo verificar el código.")
    }

    companion object {
        fun factory(repo: AuthRepository) = viewModelFactory {
            initializer { OnboardingViewModel(repo) }
        }
    }
}
