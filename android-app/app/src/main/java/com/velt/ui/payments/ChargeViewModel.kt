package com.velt.ui.payments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.velt.data.remote.ApiResult
import com.velt.data.remote.Merchant
import com.velt.data.remote.PaymentEvent
import com.velt.data.remote.PaymentStatus
import com.velt.data.repository.PaymentRepository
import com.velt.ui.i18n.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ChargeState {
    data object LoadingMerchant : ChargeState
    data object CreateMerchant : ChargeState
    data object EnterAmount : ChargeState
    data class AwaitingPalm(val paymentId: String) : ChargeState
    data class Authorizing(val paymentId: String) : ChargeState
    data class Held(
        val paymentId: String,
        val escrowTxHash: String?,
        val releaseAfterIso: String?,
        val confirming: Boolean = false
    ) : ChargeState

    data class Settled(val txHash: String?, val payerEnsName: String?) : ChargeState
    data class Failed(val reason: String, val canFundPayer: Boolean = false) : ChargeState
}

private const val POLL_INTERVAL_MS = 3_000L

class ChargeViewModel(private val repo: PaymentRepository) : ViewModel() {

    var state by mutableStateOf<ChargeState>(ChargeState.LoadingMerchant)
        private set
    var merchant by mutableStateOf<Merchant?>(null)
        private set
    var amountCents by mutableStateOf(0L)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var lastPersonId: String? = null
        private set

    private var trackingJob: Job? = null

    init {
        loadMerchant()
    }

    val amount: Double get() = amountCents / 100.0

    fun pressDigit(digit: String) {
        val next = amountCents * 10 + (digit.toLongOrNull() ?: return)
        if (next <= 99_999_999) amountCents = next
    }

    fun pressDelete() {
        amountCents /= 10
    }

    /** Suma un monto rápido (chips +10, +25, ...). [usdc] en unidades enteras de USDC. */
    fun addUsdc(usdc: Long) {
        val next = amountCents + usdc * 100
        if (next <= 99_999_999) amountCents = next
    }

    fun loadMerchant() {
        state = ChargeState.LoadingMerchant
        errorMessage = null
        viewModelScope.launch {
            when (val result = repo.merchants()) {
                is ApiResult.Success -> {
                    // DECISIÓN: el demo opera con un solo comercio — se usa el primero de la lista.
                    merchant = result.data.firstOrNull()
                    state = if (merchant == null) ChargeState.CreateMerchant else ChargeState.EnterAmount
                }
                is ApiResult.Failure -> {
                    errorMessage = result.message
                        ?: tr("Couldn't load your merchants.", "No se pudieron cargar tus comercios.")
                    state = ChargeState.CreateMerchant
                }
                is ApiResult.NetworkError -> {
                    errorMessage = tr(
                        "No connection. Check your internet and try again.",
                        "Sin conexión. Revisa tu internet e inténtalo de nuevo."
                    )
                    state = ChargeState.CreateMerchant
                }
            }
        }
    }

    fun createMerchant(name: String) {
        if (name.isBlank()) return
        errorMessage = null
        viewModelScope.launch {
            when (val result = repo.createMerchant(name.trim())) {
                is ApiResult.Success -> {
                    merchant = result.data
                    state = ChargeState.EnterAmount
                }
                is ApiResult.Failure -> errorMessage = result.message
                    ?: tr("Couldn't create the merchant.", "No se pudo crear el comercio.")
                is ApiResult.NetworkError -> errorMessage =
                    tr("No connection. Try again.", "Sin conexión. Inténtalo de nuevo.")
            }
        }
    }

    fun startCharge() {
        val merchantId = merchant?.id ?: return
        if (amountCents <= 0L) return
        errorMessage = null
        viewModelScope.launch {
            when (val result = repo.initiate(merchantId, amount)) {
                is ApiResult.Success -> {
                    state = ChargeState.AwaitingPalm(result.data.paymentId)
                    track(result.data.paymentId, result.data.wsUrl)
                }
                is ApiResult.Failure -> errorMessage = result.message
                    ?: tr("Couldn't start the charge.", "No se pudo iniciar el cobro.")
                is ApiResult.NetworkError -> errorMessage =
                    tr("No connection. Try again.", "Sin conexión. Inténtalo de nuevo.")
            }
        }
    }

    fun authorize(personId: String) {
        val paymentId = currentPaymentId() ?: return
        lastPersonId = personId
        state = ChargeState.Authorizing(paymentId)
        viewModelScope.launch {
            when (val result = repo.authorize(paymentId, personId)) {
                is ApiResult.Failure ->
                    failWith(result.code ?: result.message
                        ?: tr("Couldn't authorize the payment.", "No se pudo autorizar el pago."))
                is ApiResult.NetworkError ->
                    failWith(tr("No connection during authorization.", "Sin conexión durante la autorización."))
                is ApiResult.Success -> Unit
            }
        }
    }

    fun palmFailed(message: String) {
        failWith(message)
    }

    fun confirmDelivery() {
        val held = state as? ChargeState.Held ?: return
        state = held.copy(confirming = true)
        viewModelScope.launch {
            when (val result = repo.confirm(held.paymentId)) {
                is ApiResult.Failure -> {
                    errorMessage = result.message
                        ?: tr("Couldn't confirm delivery.", "No se pudo confirmar la entrega.")
                    state = held.copy(confirming = false)
                }
                is ApiResult.NetworkError -> {
                    errorMessage = tr("No connection. Try again.", "Sin conexión. Inténtalo de nuevo.")
                    state = held.copy(confirming = false)
                }
                is ApiResult.Success -> Unit
            }
        }
    }

    fun reset() {
        trackingJob?.cancel()
        trackingJob = null
        amountCents = 0L
        errorMessage = null
        state = if (merchant == null) ChargeState.LoadingMerchant else ChargeState.EnterAmount
        if (merchant == null) loadMerchant()
    }

    private fun currentPaymentId(): String? = when (val s = state) {
        is ChargeState.AwaitingPalm -> s.paymentId
        is ChargeState.Authorizing -> s.paymentId
        is ChargeState.Held -> s.paymentId
        else -> null
    }

    private fun track(paymentId: String, wsUrl: String) {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            repo.events(wsUrl).collect { applyEvent(paymentId, it) }
            // El WS se cerró (evento terminal o caída): si el pago sigue vivo, poll de respaldo.
            while (isActive && !isTerminal()) {
                delay(POLL_INTERVAL_MS)
                val result = repo.status(paymentId)
                if (result is ApiResult.Success) applyStatus(result.data)
            }
        }
    }

    private fun applyEvent(paymentId: String, event: PaymentEvent) {
        when (event.type) {
            "authorizing" -> if (state is ChargeState.AwaitingPalm || state is ChargeState.Authorizing) {
                state = ChargeState.Authorizing(paymentId)
            }
            "held" -> state = ChargeState.Held(paymentId, event.escrowTxHash, event.releaseAfter)
            "settled" -> state = ChargeState.Settled(event.txHash, event.payerEnsName)
            "failed" -> failWith(event.reason ?: "payment_failed")
        }
    }

    private fun applyStatus(status: PaymentStatus) {
        when (status.status) {
            "held" -> {
                val held = state as? ChargeState.Held
                state = ChargeState.Held(
                    status.paymentId,
                    status.escrowTxHash,
                    status.releaseAfter,
                    confirming = held?.confirming ?: false
                )
            }
            "settled" -> state = ChargeState.Settled(
                status.txHash ?: status.releaseTxHash,
                status.payerEnsName
            )
            "failed" -> failWith("payment_failed")
        }
    }

    private fun isTerminal(): Boolean = state is ChargeState.Settled || state is ChargeState.Failed

    private fun failWith(reason: String) {
        trackingJob?.cancel()
        state = ChargeState.Failed(
            reason = readableReason(reason),
            canFundPayer = reason == "insufficient_funds" && lastPersonId != null
        )
    }

    private fun readableReason(reason: String): String = when (reason) {
        "insufficient_funds" ->
            tr("The payer doesn't have enough funds.", "El pagador no tiene fondos suficientes.")
        "rpc_timeout" ->
            tr("The network took too long to respond. Try again.", "La red tardó demasiado en responder. Intenta de nuevo.")
        "tx_reverted" ->
            tr("The transaction was rejected on-chain.", "La transacción fue rechazada en la cadena.")
        "payment_failed" ->
            tr("The payment couldn't be completed.", "El pago no pudo completarse.")
        else -> reason
    }

    override fun onCleared() {
        trackingJob?.cancel()
    }

    companion object {
        fun factory(repo: PaymentRepository) = viewModelFactory {
            initializer { ChargeViewModel(repo) }
        }
    }
}
