package com.velt.ui.payments

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.toArgb
import com.velt.BuildConfig
import com.velt.ui.theme.Velt
import org.json.JSONObject

/** Abre la página de funding de Blink (servida por el backend) en un Chrome Custom Tab. */
fun openFundingPage(context: Context, personId: String) {
    val url = Uri.parse(BuildConfig.API_BASE_URL)
        .buildUpon()
        .path("fund")
        .appendQueryParameter("personId", personId)
        .build()
    val colors = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(Velt.Bg.toArgb())
        .build()
    CustomTabsIntent.Builder()
        .setDefaultColorSchemeParams(colors)
        .setShowTitle(true)
        .build()
        .launchUrl(context, url)
}

/** Resultado del deep link de retorno `velt://deposit?status=ok|error&transferId=...`. */
data class DepositResult(val ok: Boolean, val transferId: String?)

fun parseDepositDeepLink(intent: Intent?): DepositResult? {
    val uri = intent?.data ?: return null
    if (uri.scheme != "velt" || uri.host != "deposit") return null
    return DepositResult(
        ok = uri.getQueryParameter("status") == "ok",
        transferId = uri.getQueryParameter("transferId")
    )
}

/** Extrae el personId de la respuesta del bioserver (`personId` o `subjectId`). */
fun extractPersonId(bioserverBody: String): String? = runCatching {
    val json = JSONObject(bioserverBody)
    val personId = json.optString("personId", "")
    val subjectId = json.optString("subjectId", "")
    personId.ifEmpty { subjectId }.ifEmpty { null }
}.getOrNull()
