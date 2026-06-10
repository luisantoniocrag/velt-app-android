package com.velt.data.repository

import com.velt.data.auth.TokenStore
import com.velt.data.remote.ApiResult
import com.velt.data.remote.AuthRequest
import com.velt.data.remote.Credentials
import com.velt.data.remote.LinkResponse
import com.velt.data.remote.LogoutRequest
import com.velt.data.remote.MeResponse
import com.velt.data.remote.OtpRequest
import com.velt.data.remote.VeltApi
import com.velt.data.remote.safeApiCall

class AuthRepository(
    private val api: VeltApi,
    private val tokenStore: TokenStore
) {
    val isLoggedIn: Boolean get() = tokenStore.isLoggedIn

    suspend fun sendOtp(phoneE164: String, channel: String = "whatsapp"): ApiResult<Unit> =
        safeApiCall { api.sendOtp(OtpRequest(phoneE164, channel)) }

    /** Verifica OTP (login-or-create). Devuelve `userCreated` (true = cuenta nueva). */
    suspend fun verifyPhone(phoneE164: String, code: String): ApiResult<Boolean> =
        verify(AuthRequest("phone", Credentials(phone = phoneE164, code = code)))

    /** Verifica con palma (login-or-create). Devuelve `userCreated`. */
    suspend fun verifyPalm(template: String): ApiResult<Boolean> =
        verify(AuthRequest("palm", Credentials(template = template)))

    private suspend fun verify(request: AuthRequest): ApiResult<Boolean> {
        val res = safeApiCall { api.verify(request) }
        return when (res) {
            is ApiResult.Success -> {
                tokenStore.saveSession(res.data.accessToken, res.data.refreshToken, res.data.userId)
                ApiResult.Success(res.data.userCreated)
            }
            is ApiResult.Failure -> res
            is ApiResult.NetworkError -> res
        }
    }

    suspend fun linkPalm(template: String): ApiResult<LinkResponse> =
        safeApiCall { api.link(AuthRequest("palm", Credentials(template = template))) }

    suspend fun me(): ApiResult<MeResponse> = safeApiCall { api.me() }

    suspend fun logout() {
        val refresh = tokenStore.refreshToken
        if (refresh != null) {
            safeApiCall { api.logout(LogoutRequest(refresh)) }
        }
        tokenStore.clear()
    }
}
