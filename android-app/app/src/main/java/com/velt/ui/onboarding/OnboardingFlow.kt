package com.velt.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.velt.ui.theme.Velt

private const val DESIGN_WIDTH = 300f

enum class ObStep { SPLASH, PHONE, OTP, PROFILE, KYC, PALM, WELCOME }

@Composable
fun DesignScaled(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Velt.Bg)) {
        val base = LocalDensity.current
        val factor = maxWidth.value / DESIGN_WIDTH
        CompositionLocalProvider(
            LocalDensity provides Density(density = base.density * factor, fontScale = base.fontScale)
        ) {
            content()
        }
    }
}

@Composable
fun OnboardingFlow(onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(ObStep.SPLASH) }
    var lang by rememberSaveable { mutableStateOf(LanguagePreference.load(context)) }
    val strings = stringsFor(lang, context)
    val onLang: (Lang) -> Unit = { selected ->
        lang = selected
        LanguagePreference.save(context, selected)
    }

    fun go(next: ObStep) { step = next }
    fun back(prev: ObStep) { step = prev }

    BackHandler(enabled = step != ObStep.SPLASH) {
        step = when (step) {
            ObStep.PHONE -> ObStep.SPLASH
            ObStep.OTP -> ObStep.PHONE
            ObStep.PROFILE -> ObStep.OTP
            ObStep.KYC -> ObStep.PROFILE
            ObStep.PALM -> ObStep.KYC
            ObStep.WELCOME -> ObStep.PALM
            ObStep.SPLASH -> ObStep.SPLASH
        }
    }

    DesignScaled {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (fadeIn(androidx.compose.animation.core.tween(220)))
                    .togetherWith(fadeOut(androidx.compose.animation.core.tween(160)))
                    .using(SizeTransform(clip = false))
            },
            label = "ob-step"
        ) { current ->
            when (current) {
                ObStep.SPLASH -> SplashScreen(strings, lang, onLang, onCreate = { go(ObStep.PHONE) }, onLogin = { go(ObStep.PHONE) })
                ObStep.PHONE -> PhoneScreen(strings, lang, onLang, onBack = { back(ObStep.SPLASH) }, onNext = { go(ObStep.OTP) })
                ObStep.OTP -> OtpScreen(strings, lang, onLang, onBack = { back(ObStep.PHONE) }, onNext = { go(ObStep.PROFILE) })
                ObStep.PROFILE -> ProfileScreen(strings, lang, onLang, onBack = { back(ObStep.OTP) }, onNext = { go(ObStep.KYC) })
                ObStep.KYC -> KycScreen(strings, lang, onLang, onBack = { back(ObStep.PROFILE) }, onNext = { go(ObStep.PALM) })
                ObStep.PALM -> PalmScreen(strings, lang, onLang, onBack = { back(ObStep.KYC) }, onNext = { go(ObStep.WELCOME) })
                ObStep.WELCOME -> WelcomeScreen(strings, onFinish = onFinish)
            }
        }
    }
}

@Composable
fun ObScaffold(
    lang: Lang,
    onLang: (Lang) -> Unit,
    showLangPill: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Velt.Bg)) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            content()
        }
        if (showLangPill) {
            LanguagePill(
                lang = lang,
                onLang = onLang,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp)
            )
        }
    }
}
