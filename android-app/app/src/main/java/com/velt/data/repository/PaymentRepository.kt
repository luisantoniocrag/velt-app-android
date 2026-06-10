package com.velt.data.repository

import com.velt.data.remote.ApiResult
import com.velt.data.remote.AuthorizePaymentRequest
import com.velt.data.remote.ConfirmPaymentResponse
import com.velt.data.remote.CreateMerchantRequest
import com.velt.data.remote.InitiatePaymentRequest
import com.velt.data.remote.InitiatePaymentResponse
import com.velt.data.remote.Merchant
import com.velt.data.remote.PaymentEvent
import com.velt.data.remote.PaymentEventsSocket
import com.velt.data.remote.PaymentStatus
import com.velt.data.remote.VeltApi
import com.velt.data.remote.safeApiCall
import kotlinx.coroutines.flow.Flow

class PaymentRepository(
    private val api: VeltApi,
    private val eventsSocket: PaymentEventsSocket
) {
    suspend fun merchants(): ApiResult<List<Merchant>> = safeApiCall { api.merchants() }

    suspend fun createMerchant(name: String): ApiResult<Merchant> =
        safeApiCall { api.createMerchant(CreateMerchantRequest(name)) }

    suspend fun merchant(id: String): ApiResult<Merchant> = safeApiCall { api.merchant(id) }

    suspend fun initiate(merchantId: String, amount: Double): ApiResult<InitiatePaymentResponse> =
        safeApiCall { api.initiatePayment(InitiatePaymentRequest(merchantId, amount)) }

    suspend fun authorize(paymentId: String, personId: String): ApiResult<Unit> =
        safeApiCall { api.authorizePayment(AuthorizePaymentRequest(paymentId, personId)) }

    suspend fun confirm(paymentId: String): ApiResult<ConfirmPaymentResponse> =
        safeApiCall { api.confirmPayment(paymentId) }

    suspend fun status(paymentId: String): ApiResult<PaymentStatus> =
        safeApiCall { api.payment(paymentId) }

    fun events(wsPath: String): Flow<PaymentEvent> = eventsSocket.events(wsPath)
}
