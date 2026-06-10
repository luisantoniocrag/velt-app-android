package com.velt.data.remote

import com.velt.data.auth.AuthInterceptor
import com.velt.data.auth.TokenAuthenticator
import com.velt.data.auth.TokenStore
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Resultado del cableado HTTP: la API Retrofit más el cliente OkHttp (reusado por el WebSocket). */
class VeltBackend(val api: VeltApi, val httpClient: OkHttpClient, val baseUrl: String)

object ApiClient {

    fun create(baseUrl: String, tokenStore: TokenStore, debug: Boolean): VeltBackend {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val logging = HttpLoggingInterceptor().apply {
            level = if (debug) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
            // Nunca loguear el header Authorization.
            redactHeader("Authorization")
        }

        // Cliente para el refresh: sin authenticator (evita recursión) ni bearer.
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val refreshApi = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(refreshClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VeltRefreshApi::class.java)

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(tokenStore) { refreshApi })
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val api = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VeltApi::class.java)

        return VeltBackend(api, client, normalizedUrl)
    }
}
