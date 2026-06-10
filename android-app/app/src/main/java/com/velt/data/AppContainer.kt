package com.velt.data

import android.content.Context
import com.velt.BuildConfig
import com.velt.data.auth.TokenStore
import com.velt.data.remote.ApiClient
import com.velt.data.remote.PaymentEventsSocket
import com.velt.data.repository.AuthRepository
import com.velt.data.repository.PaymentRepository

/** Punto único de cableado de dependencias (service locator simple). */
class AppContainer(context: Context) {
    val tokenStore: TokenStore = TokenStore(context)

    private val backend = ApiClient.create(
        baseUrl = BuildConfig.API_BASE_URL,
        tokenStore = tokenStore,
        debug = BuildConfig.DEBUG
    )

    val authRepository: AuthRepository = AuthRepository(backend.api, tokenStore)
    val paymentRepository: PaymentRepository = PaymentRepository(
        backend.api,
        PaymentEventsSocket(backend.httpClient, backend.baseUrl)
    )
}
