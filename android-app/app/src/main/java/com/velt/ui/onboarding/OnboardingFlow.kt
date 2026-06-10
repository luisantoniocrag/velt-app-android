package com.velt.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.velt.VeltApp
import com.velt.ui.theme.Velt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DESIGN_WIDTH = 300f
private const val STEP_LOADING_MS = 1600L

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
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as VeltApp
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(app.container.authRepository))

    var step by rememberSaveable { mutableStateOf(ObStep.SPLASH) }
    var phoneNumber by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var lang by rememberSaveable { mutableStateOf(LanguagePreference.load(context)) }
    val strings = stringsFor(lang, context)
    val onLang: (Lang) -> Unit = { selected ->
        lang = selected
        LanguagePreference.save(context, selected)
    }

    // Ejecuta una acción (llamada al backend) mostrando el loading; navega a [next] solo si tuvo éxito.
    fun proceed(next: ObStep, action: (suspend () -> Boolean)? = null) {
        if (loading) return
        scope.launch {
            loading = true
            val ok = action?.invoke() ?: run { delay(STEP_LOADING_MS); true }
            loading = false
            if (ok) step = next
        }
    }

    fun back(prev: ObStep) {
        vm.clearError()
        step = prev
    }

    BackHandler(enabled = step != ObStep.SPLASH && !loading) {
        vm.clearError()
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
        Box(modifier = Modifier.fillMaxSize()) {
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
                    ObStep.SPLASH -> SplashScreen(
                        strings, lang, onLang,
                        onCreate = { vm.clearError(); proceed(ObStep.PHONE) },
                        onLogin = { vm.clearError(); proceed(ObStep.PHONE) }
                    )
                    ObStep.PHONE -> PhoneScreen(
                        strings, lang, onLang,
                        error = vm.errorMessage,
                        onBack = { back(ObStep.SPLASH) },
                        onNext = { display, e164 ->
                            phoneNumber = display
                            vm.phoneE164 = e164
                            vm.phoneDisplay = display
                            proceed(ObStep.OTP) { vm.sendOtp() }
                        }
                    )
                    ObStep.OTP -> OtpScreen(
                        strings, lang, onLang,
                        phoneNumber = phoneNumber,
                        error = vm.errorMessage,
                        onResend = { scope.launch { vm.sendOtp() } },
                        onBack = { back(ObStep.PHONE) },
                        onNext = { code ->
                            if (!loading) scope.launch {
                                loading = true
                                val outcome = vm.verifyCode(code)
                                loading = false
                                if (outcome is VerifyOutcome.Ok) {
                                    // Cuenta nueva → sigue el onboarding; existente → directo a Home.
                                    if (outcome.userCreated) step = ObStep.PROFILE else onFinish()
                                }
                            }
                        }
                    )
                    ObStep.PROFILE -> ProfileScreen(strings, lang, onLang, onBack = { back(ObStep.OTP) }, onNext = { proceed(ObStep.KYC) })
                    ObStep.KYC -> KycScreen(strings, lang, onLang, onBack = { back(ObStep.PROFILE) }, onNext = { proceed(ObStep.PALM) })
                    ObStep.PALM -> PalmScreen(strings, lang, onLang, onBack = { back(ObStep.KYC) }, onNext = { proceed(ObStep.WELCOME) })
                    ObStep.WELCOME -> WelcomeScreen(strings, onFinish = onFinish)
                }
            }

            if (loading) LoadingOverlay()
        }
    }
}

@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Velt.Bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = Velt.Cyan,
            strokeWidth = 4.dp,
            modifier = Modifier.padding(0.dp)
        )
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
        Box(modifier = Modifier.fillMaxSize().padding(top = 28.dp)) {
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
