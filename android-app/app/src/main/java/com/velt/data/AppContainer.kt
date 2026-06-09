package com.velt.data

import android.content.Context
import com.velt.BuildConfig
import com.velt.data.auth.TokenStore
import com.velt.data.remote.ApiClient
import com.velt.data.repository.AuthRepository

/** Punto único de cableado de dependencias (service locator simple). */
class AppContainer(context: Context) {
    val tokenStore: TokenStore = TokenStore(context)

    private val api = ApiClient.create(
        baseUrl = BuildConfig.API_BASE_URL,
        tokenStore = tokenStore,
        debug = BuildConfig.DEBUG
    )

    val authRepository: AuthRepository = AuthRepository(api, tokenStore)
}
