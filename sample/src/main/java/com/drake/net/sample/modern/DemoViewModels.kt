package com.drake.net.sample.modern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drake.net.NetConfig
import com.drake.net.error.NetErrorDispatcher
import com.drake.net.netFlow
import com.drake.net.netResult
import com.drake.net.result.NetResult
import com.drake.net.result.NetState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** MVVM：UI 调用方法，ViewModel 通过只读 StateFlow 下发持久状态。 */
class DemoMvvmViewModel(
    private val repository: DemoRepository,
    val errors: NetErrorDispatcher = NetConfig.errorDispatcher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val mutableState = MutableStateFlow<NetState<String>>(NetState.Idle)
    val state = mutableState.asStateFlow()
    private var request: Job? = null

    fun load(scenario: DemoScenario) {
        request?.cancel()
        request = viewModelScope.launch(dispatcher) {
            mutableState.value = NetState.Loading
            when (val result = netResult { repository.load(scenario) }) {
                is NetResult.Success -> mutableState.value = NetState.Success(result.value)
                is NetResult.Failure -> {
                    mutableState.value = NetState.Failure(result.error)
                    // 显式启用全域错误分发；错误状态仍保留，重建画面不会丢失。
                    errors.dispatch(result.error)
                }
            }
        }
    }
}

sealed interface DemoIntent {
    data class Load(val scenario: DemoScenario) : DemoIntent
}

data class DemoUiState(
    val scenario: DemoScenario = DemoScenario.SUCCESS,
    val request: NetState<String> = NetState.Idle
)

/** MVI：所有输入经过 accept，状态单向流动，Effect 仅供单一 UI collector 消费。 */
class DemoMviViewModel(
    private val repository: DemoRepository,
    val errors: NetErrorDispatcher = NetConfig.errorDispatcher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val mutableState = MutableStateFlow(DemoUiState())
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<String>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var request: Job? = null

    fun accept(intent: DemoIntent) {
        when (intent) {
            is DemoIntent.Load -> {
                request?.cancel()
                request = viewModelScope.launch(dispatcher) {
                    netFlow(errorDispatcher = errors) { repository.load(intent.scenario) }
                        .collect { state ->
                            mutableState.value = DemoUiState(intent.scenario, state)
                            if (state is NetState.Success) effectChannel.send("MVI 请求完成")
                        }
                }
            }
        }
    }

    override fun onCleared() {
        effectChannel.close()
        super.onCleared()
    }
}
