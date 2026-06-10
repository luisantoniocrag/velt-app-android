package com.velt.data.remote

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Capa de webservice: todos los endpoints REST del backend de Velt en un solo lugar. */
interface VeltApi {

    @POST("api/v1/auth/phone/otp")
    suspend fun sendOtp(@Body body: OtpRequest): Response<Unit>

    @POST("api/v1/auth/verify")
    suspend fun verify(@Body body: AuthRequest): Response<VerifyResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<RefreshResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    @GET("api/v1/auth/me")
    suspend fun me(): Response<MeResponse>

    @POST("api/v1/auth/link")
    suspend fun link(@Body body: AuthRequest): Response<LinkResponse>

    @GET("api/v1/merchants")
    suspend fun merchants(): Response<List<Merchant>>

    @POST("api/v1/merchants")
    suspend fun createMerchant(@Body body: CreateMerchantRequest): Response<Merchant>

    @GET("api/v1/merchants/{id}")
    suspend fun merchant(@Path("id") id: String): Response<Merchant>

    @POST("api/v1/payments/initiate")
    suspend fun initiatePayment(@Body body: InitiatePaymentRequest): Response<InitiatePaymentResponse>

    @POST("api/v1/payments/authorize")
    suspend fun authorizePayment(@Body body: AuthorizePaymentRequest): Response<Unit>

    @POST("api/v1/payments/{id}/confirm")
    suspend fun confirmPayment(@Path("id") id: String): Response<ConfirmPaymentResponse>

    @GET("api/v1/payments/{id}")
    suspend fun payment(@Path("id") id: String): Response<PaymentStatus>
}

/** Refresh síncrono usado por el [okhttp3.Authenticator] (no puede ser suspend). */
interface VeltRefreshApi {
    @POST("api/v1/auth/refresh")
    fun refresh(@Body body: RefreshRequest): Call<RefreshResponse>
}
