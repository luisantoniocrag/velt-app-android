package com.velt.data.remote

// ── Requests ──

data class OtpRequest(val phone: String, val channel: String = "whatsapp")

data class Credentials(
    val phone: String? = null,
    val code: String? = null,
    val template: String? = null
)

data class AuthRequest(val provider: String, val credentials: Credentials)

data class RefreshRequest(val refreshToken: String)

data class LogoutRequest(val refreshToken: String)

// ── Responses ──

data class LoginResponse(
    val userId: String?,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int? = null
)

data class UserRef(val id: String)

data class RegisterResponse(
    val user: UserRef?,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int? = null
)

data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int? = null
)

data class Identity(val provider: String)

data class Merchant(
    val id: String,
    val name: String,
    val smartAccountAddress: String? = null,
    val custodial: Boolean? = null,
    val usdcBalance: String? = null
)

data class MeResponse(
    val userId: String,
    val isNew: Boolean,
    val identities: List<Identity> = emptyList(),
    val merchants: List<Merchant> = emptyList()
)

data class LinkResponse(val linked: Boolean, val provider: String)

data class ApiErrorBody(
    val statusCode: Int? = null,
    val code: String? = null,
    val error: String? = null,
    val message: String? = null
)
