package com.velt.data.remote

import com.google.gson.Gson
import retrofit2.Response
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    /** Error con respuesta del servidor. Decidir por [code] (estable), no por [message]. */
    data class Failure(val httpStatus: Int, val code: String?, val message: String?) : ApiResult<Nothing>

    /** Sin respuesta del servidor (sin red, timeout, etc.). */
    data class NetworkError(val throwable: Throwable) : ApiResult<Nothing>
}

private val errorGson = Gson()

suspend fun <T> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = block()
        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            val body = (response.body() ?: Unit) as T
            ApiResult.Success(body)
        } else {
            val parsed = runCatching {
                errorGson.fromJson(response.errorBody()?.string(), ApiErrorBody::class.java)
            }.getOrNull()
            ApiResult.Failure(response.code(), parsed?.code, parsed?.message)
        }
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    } catch (e: Exception) {
        ApiResult.NetworkError(e)
    }
}
