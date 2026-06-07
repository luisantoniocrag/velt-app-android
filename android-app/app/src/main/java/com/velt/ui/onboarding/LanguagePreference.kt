package com.velt.ui.onboarding

import android.content.Context
import java.util.Locale

object LanguagePreference {
    private const val PREFS = "velt_prefs"
    private const val KEY = "onboarding_lang"

    fun load(context: Context): Lang {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY, null)) {
            Lang.ES.name -> Lang.ES
            Lang.EN.name -> Lang.EN
            else -> systemDefault()
        }
    }

    fun save(context: Context, lang: Lang) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, lang.name)
            .apply()
    }

    private fun systemDefault(): Lang =
        if (Locale.getDefault().language == "es") Lang.ES else Lang.EN
}
