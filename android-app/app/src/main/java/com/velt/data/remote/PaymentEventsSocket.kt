package com.velt.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Cliente de los WebSockets de eventos (`/ws/payments/:id`, `/ws/withdrawals/:id`). Emite los
 * eventos del backend y completa el flow cuando el socket se cierra o falla — el consumidor
 * decide el fallback (poll al GET de estado), por eso un fallo NO se propaga como excepción.
 */
class PaymentEventsSocket(
    private val httpClient: OkHttpClient,
    httpBaseUrl: String
) {
    private val gson = Gson()
    private val wsBaseUrl = httpBaseUrl
        .removeSuffix("/")
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

    fun events(wsPath: String): Flow<PaymentEvent> =
        eventFlow(wsPath, PaymentEvent::class.java) { it.type }

    fun withdrawalEvents(wsPath: String): Flow<WithdrawalEvent> =
        eventFlow(wsPath, WithdrawalEvent::class.java) { it.type }

    private fun <T : Any> eventFlow(
        wsPath: String,
        clazz: Class<T>,
        typeOf: (T) -> String?
    ): Flow<T> = callbackFlow {
        val request = Request.Builder().url(wsBaseUrl + wsPath).build()
        val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { gson.fromJson(text, clazz) }.getOrNull()
                if (event != null && typeOf(event) != null) trySend(event)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close()
            }
        })
        awaitClose { socket.cancel() }
    }
}
