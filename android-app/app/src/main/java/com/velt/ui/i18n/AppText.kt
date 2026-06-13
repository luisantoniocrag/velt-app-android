package com.velt.ui.i18n

/**
 * Idioma de la app fuera del onboarding (el onboarding usa su propio toggle vía recursos XML).
 * Inglés por default; cada string conserva su versión en español inline para reactivar la
 * localización más adelante sin volver a traducir.
 */
enum class AppLang { EN, ES }

object AppLocale {
    // DECISIÓN: inglés por default. Cambiar a ES alterna cada `tr(...)` al español.
    var lang: AppLang = AppLang.EN
}

/** Devuelve la versión en inglés (default) o español del texto según [AppLocale.lang]. */
fun tr(en: String, es: String): String = if (AppLocale.lang == AppLang.ES) es else en
