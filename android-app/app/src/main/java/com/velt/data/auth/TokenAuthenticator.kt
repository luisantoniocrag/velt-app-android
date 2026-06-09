package com.velt.data.auth

import com.velt.data.remote.RefreshRequest
import com.velt.data.remote.VeltRefreshApi
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Ante un 401 de un endpoint protegido, intenta refrescar el token una sola vez y reintenta.
 * Usa [VeltRefreshApi] (síncrono, sin este authenticator) para evitar recursión.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val refreshApi: () -> VeltRefreshApi
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // No reintentar más de una vez.
        if (responseCount(response) >= 2) return null

        val currentRefresh = tokenStore.refreshToken ?: return null

        synchronized(this) {
            // Otro hilo pudo haber refrescado ya; si el access cambió, reintenta con el nuevo.
            val latestAccess = tokenStore.accessToken
            val sentAccess = response.request.header("Authorization")?.removePrefix("Bearer ")
            if (latestAccess != null && latestAccess != sentAccess) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $latestAccess")
                    .build()
            }

            val refreshed = runCatching {
                refreshApi().refresh(RefreshRequest(currentRefresh)).execute()
            }.getOrNull()

            val newTokens = refreshed?.takeIf { it.isSuccessful }?.body()
            if (newTokens == null) {
                tokenStore.clear()
                return null
            }

            tokenStore.updateTokens(newTokens.accessToken, newTokens.refreshToken)
            return response.request.newBuilder()
                .header("Authorization", "Bearer ${newTokens.accessToken}")
                .build()
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
