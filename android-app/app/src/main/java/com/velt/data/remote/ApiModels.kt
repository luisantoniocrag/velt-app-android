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

data class CreateMerchantRequest(val name: String)

data class InitiatePaymentRequest(val merchantId: String, val amount: Double)

data class AuthorizePaymentRequest(val paymentId: String, val personId: String)

// ── Responses ──

/** Respuesta de `POST /auth/verify` (login-or-create). `userCreated`=true → cuenta nueva. */
data class VerifyResponse(
    val userId: String? = null,
    val userCreated: Boolean = false,
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
    val usdcBalance: String? = null,
    val ensName: String? = null
)

data class InitiatePaymentResponse(
    val paymentId: String,
    val status: String,
    val wsUrl: String
)

data class ConfirmPaymentResponse(val paymentId: String, val status: String)

/** Estado completo de un pago (`GET /payments/:id`) — fallback cuando el WS se cae. */
data class PaymentStatus(
    val paymentId: String,
    val status: String,
    val amount: Double? = null,
    val txHash: String? = null,
    val escrowTxHash: String? = null,
    val releaseTxHash: String? = null,
    val releaseAfter: String? = null,
    val payerPersonId: String? = null,
    val payerEnsName: String? = null
)

/** Depósito de funding vía Blink (`GET /deposits?personId=`). */
data class Deposit(
    val depositId: String,
    val personId: String,
    val transferId: String? = null,
    val amount: Double? = null,
    val chainId: Long? = null,
    val status: String? = null,
    val createdAt: String? = null
)

/** Evento del WS `/ws/payments/:id`: authorizing → held → settled | failed. */
data class PaymentEvent(
    val type: String,
    val paymentId: String? = null,
    val escrowTxHash: String? = null,
    val releaseAfter: String? = null,
    val txHash: String? = null,
    val payerPersonId: String? = null,
    val payerEnsName: String? = null,
    val reason: String? = null
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
