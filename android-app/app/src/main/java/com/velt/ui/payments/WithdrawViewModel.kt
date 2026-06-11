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
import com.velt.data.remote.WithdrawalEvent
import com.velt.data.remote.WithdrawalStatus
import com.velt.data.repository.PaymentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface WithdrawState {
    data object Loading : WithdrawState
    data object EnterDetails : WithdrawState
    data class Processing(val withdrawalId: String) : WithdrawState
    data class Settled(val txHash: String?) : WithdrawState
    data class Failed(val reason: String) : WithdrawState
}

private const val POLL_INTERVAL_MS = 3_000L

class WithdrawViewModel(private val repo: PaymentRepository) : ViewModel() {

    var state by mutableStateOf<WithdrawState>(WithdrawState.Loading)
        private set
    var merchant by mutableStateOf<Merchant?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var lastAmount by mutableStateOf(0.0)
        private set

    private var trackingJob: Job? = null

    init {
        load()
    }

    val balance: Double get() = merchant?.usdcBalance?.toDoubleOrNull() ?: 0.0
    val custodial: Boolean get() = merchant?.custodial != false

    fun load() {
        state = WithdrawState.Loading
        errorMessage = null
        viewModelScope.launch {
            val merchants = repo.merchants()
            if (merchants !is ApiResult.Success) {
                state = WithdrawState.Failed("No se pudieron cargar tus comercios.")
                return@launch
            }
            // DECISIÓN: el demo opera con un solo comercio — se usa el primero de la lista.
            val first = merchants.data.firstOrNull()
            if (first == null) {
                state = WithdrawState.Failed("Necesitas un comercio antes de retirar.")
                return@launch
            }
            when (val detail = repo.merchant(first.id)) {
                is ApiResult.Success -> {
                    merchant = detail.data
                    state = WithdrawState.EnterDetails
                }
                is ApiResult.Failure ->
                    state = WithdrawState.Failed(detail.message ?: "No se pudo cargar el saldo.")
                is ApiResult.NetworkError ->
                    state = WithdrawState.Failed("Sin conexión. Revisa tu internet e inténtalo de nuevo.")
            }
        }
    }

    fun startWithdraw(to: String, amount: Double) {
        val merchantId = merchant?.id ?: return
        if (amount <= 0.0) return
        errorMessage = null
        lastAmount = amount
        viewModelScope.launch {
            when (val result = repo.withdraw(merchantId, to.trim(), amount)) {
                is ApiResult.Success -> {
                    state = WithdrawState.Processing(result.data.withdrawalId)
                    track(result.data.withdrawalId, result.data.wsUrl)
                }
                is ApiResult.Failure ->
                    errorMessage = readableReason(result.code ?: result.message ?: "withdrawal_failed")
                is ApiResult.NetworkError -> errorMessage = "Sin conexión. Inténtalo de nuevo."
            }
        }
    }

    fun reset() {
        trackingJob?.cancel()
        trackingJob = null
        errorMessage = null
        load()
    }

    private fun track(withdrawalId: String, wsUrl: String) {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            repo.withdrawalEvents(wsUrl).collect { applyEvent(withdrawalId, it) }
            // El WS se cerró (evento terminal o caída): si el retiro sigue vivo, poll de respaldo.
            while (isActive && !isTerminal()) {
                delay(POLL_INTERVAL_MS)
                val result = repo.withdrawalStatus(withdrawalId)
                if (result is ApiResult.Success) applyStatus(result.data)
            }
        }
    }

    private fun applyEvent(withdrawalId: String, event: WithdrawalEvent) {
        when (event.type) {
            "processing" -> state = WithdrawState.Processing(withdrawalId)
            "settled" -> state = WithdrawState.Settled(event.txHash)
            "failed" -> failWith(event.reason ?: "withdrawal_failed")
        }
    }

    private fun applyStatus(status: WithdrawalStatus) {
        when (status.status) {
            "settled" -> state = WithdrawState.Settled(status.txHash)
            "failed" -> failWith(status.reason ?: "withdrawal_failed")
        }
    }

    private fun isTerminal(): Boolean = state is WithdrawState.Settled || state is WithdrawState.Failed

    private fun failWith(reason: String) {
        trackingJob?.cancel()
        state = WithdrawState.Failed(readableReason(reason))
    }

    private fun readableReason(reason: String): String = when (reason) {
        "insufficient_funds" -> "El comercio no tiene saldo suficiente."
        "account_not_custodial" -> "La cuenta del comercio es externa: Velt no custodia su llave y no puede retirar."
        "not_account_owner" -> "No eres el dueño de este comercio."
        "rpc_timeout" -> "La red tardó demasiado en responder. Intenta de nuevo."
        "tx_reverted" -> "La transacción fue rechazada en la cadena."
        "withdrawal_failed", "payment_failed" -> "El retiro no pudo completarse."
        else -> reason
    }

    override fun onCleared() {
        trackingJob?.cancel()
    }

    companion object {
        fun factory(repo: PaymentRepository) = viewModelFactory {
            initializer { WithdrawViewModel(repo) }
        }
    }
}
