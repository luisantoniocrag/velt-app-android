package com.velt.sensor

import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object VeltSensorBioService {
    private const val TAG = "VeltSensorBioService"
    private val client = buildUnsafeClient()
    private val JSON = "application/json".toMediaType()

    // DEMO HACK (temporal): el bioserver está caído, así que devolvemos un personId fijo en vez de
    // identificar la palma. Poner DEMO_OVERRIDE=false para volver a llamar al bioserver real.
    private const val DEMO_OVERRIDE = true
    private const val DEMO_PERSON_ID = "94657fb3-33e3-402e-ae83-472be94347b3"

    suspend fun verifyUser(template: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (DEMO_OVERRIDE) {
            Log.d(TAG, "⚠️ DEMO_OVERRIDE: devolviendo personId fijo ($DEMO_PERSON_ID)")
            return@withContext 200 to """{"personId":"$DEMO_PERSON_ID"}"""
        }
        Log.d(TAG, "🌐 Verificando template con el bioserver (${template.length} chars)")

        val payload = listOf(
            mapOf(
                "biolocation" to "UnknownPalmVeinCapture",
                "templates" to listOf(
                    mapOf(
                        "type" to "FujitsuRFormat",
                        "template" to template
                    )
                )
            )
        )

        val timestamp = System.currentTimeMillis() / 1000
        val nonceBytes = ByteArray(36).also { SecureRandom().nextBytes(it) }
        val nonce = nonceBytes.toHex()

        val auth = buildAuthorization(payload, "post", "api/subject/identify", timestamp, nonce)

        val gson = GsonBuilder().disableHtmlEscaping().create()
        val jsonBody = gson.toJson(payload)

        val req = Request.Builder()
            .url("${VeltSensorConfig.ENDPOINT}api/subject/identify")
            .post(jsonBody.toRequestBody(JSON))
            .addHeader("Authorization", auth)
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            Log.d(TAG, "🚀 POST ${req.url}")
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.d(TAG, "📡 Respuesta bioserver: HTTP ${resp.code} (${body.length} chars)")
                resp.code to body
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error comunicando con el bioserver: ${e.message}", e)
            -1 to (e.message ?: "Error de red")
        }
    }

    /**
     * Enrola (da de alta) una palma en el bioserver bajo [personId]. POST /api/subject, firmado
     * con HMAC. Devuelve (HTTP code, body). El enrollment SÍ pega al bioserver real (no usa el
     * DEMO_OVERRIDE): si OpenPalm está caído, devolverá el error correspondiente.
     */
    suspend fun enrollUser(
        personId: String,
        template: String,
        firstName: String = "Velt",
        lastName: String = "User",
        hand: String = "UnknownPalmVeinCapture",
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "firstName" to firstName,
            "lastName" to lastName,
            "personId" to personId,
            "dob" to "1990-12-25T00:00:00.001Z",
            "templates" to listOf(
                mapOf(
                    "bioLocation" to hand,
                    "templates" to listOf(
                        mapOf("type" to "FujitsuRFormat", "template" to template)
                    )
                )
            )
        )
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = ByteArray(36).also { SecureRandom().nextBytes(it) }.toHex()
        val auth = buildAuthorization(payload, "post", "api/subject", timestamp, nonce)
        val jsonBody = GsonBuilder().disableHtmlEscaping().create().toJson(payload)

        val req = Request.Builder()
            .url("${VeltSensorConfig.ENDPOINT}api/subject")
            .post(jsonBody.toRequestBody(JSON))
            .addHeader("Authorization", auth)
            .addHeader("Content-Type", "application/json")
            .build()
        try {
            Log.d(TAG, "🚀 POST ${req.url} (enroll $personId)")
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.d(TAG, "📡 Enroll bioserver: HTTP ${resp.code} (${body.length} chars)")
                resp.code to body
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enrolando: ${e.message}", e)
            -1 to (e.message ?: "Error de red")
        }
    }

    private fun buildAuthorization(
        body: Any,
        method: String,
        path: String,
        timestamp: Long,
        nonce: String
    ): String {
        val bodyAuth = mapOf(
            "body" to body,
            "clientId" to VeltSensorConfig.CLIENT_ID,
            "method" to method,
            "nonce" to nonce,
            "path" to path,
            "query" to emptyMap<String, Any>(),
            "timestamp" to timestamp
        )

        val jsonPayload = GsonBuilder().disableHtmlEscaping().create().toJson(bodyAuth)

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(VeltSensorConfig.SHARED_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val signature = mac.doFinal(jsonPayload.toByteArray(Charsets.UTF_8))
        return "MAC ${signature.toHex()}, clientId=${VeltSensorConfig.CLIENT_ID}, nonce=$nonce, timestamp=$timestamp"
    }

    // Acepta cualquier certificado TLS. SOLO para desarrollo/sandbox.
    private fun buildUnsafeClient(): OkHttpClient {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL").apply {
            init(null, trustAll, SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
