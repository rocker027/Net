package com.drake.net.sample.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.drake.net.result.NetState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 从主选单进入；Compose composition 与 Fragment view 生命周期绑定。 */
class ModernRequestFragment : Fragment() {
    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            DemoMvvmViewModel::class.java -> DemoMvvmViewModel(LocalDemoRepository()) as T
            DemoMviViewModel::class.java -> DemoMviViewModel(LocalDemoRepository()) as T
            else -> error("Unknown ViewModel: $modelClass")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val provider = ViewModelProvider(this, factory)
        val mvvm = provider[DemoMvvmViewModel::class.java]
        val mvi = provider[DemoMviViewModel::class.java]
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { MaterialTheme { RequestDemo(mvvm, mvi) } }
        }
    }
}

@Composable
private fun RequestDemo(mvvm: DemoMvvmViewModel, mvi: DemoMviViewModel) {
    val mvvmState by mvvm.state.collectAsStateWithLifecycle()
    val mviState by mvi.state.collectAsStateWithLifecycle()
    val scaffold = rememberScaffoldState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(lifecycleOwner, mvvm, mvi) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 全域广播只显示即时提示，画面错误以 StateFlow 为准。
            launch {
                mvvm.errors.events.collect { failure ->
                    scaffold.snackbarHostState.showSnackbar("全域错误：${failure.httpCode ?: failure.kind}")
                }
            }
            // Channel Effect 仅允许此处一个 collector；离开画面后等待下次 STARTED 消费。
            launch {
                mvi.effects.collect { scaffold.snackbarHostState.showSnackbar(it) }
            }
        }
    }
    Scaffold(scaffoldState = scaffold) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TabRow(selectedTabIndex = tab) {
                listOf("MVVM", "MVI").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            Text("所有 API 由本机 MockWebServer 回应，无需外网。先点慢请求，再点成功，可验证旧请求取消。")
            DemoScenario.values().forEach { scenario ->
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (tab == 0) mvvm.load(scenario) else mvi.accept(DemoIntent.Load(scenario))
                    }
                ) {
                    Text(when (scenario) {
                        DemoScenario.SUCCESS -> "请求成功"
                        DemoScenario.HTTP_ERROR -> "HTTP 503（全域错误提示）"
                        DemoScenario.SLOW -> "慢请求（2 秒）"
                    })
                }
            }
            RequestState(if (tab == 0) mvvmState else mviState.request)
        }
    }
}

@Composable
private fun RequestState(state: NetState<String>) {
    when (state) {
        NetState.Idle -> Text("等待请求")
        NetState.Loading -> CircularProgressIndicator()
        is NetState.Success -> Text("成功：${state.value}")
        is NetState.Failure -> Text("失败：${state.error.httpCode ?: state.error.kind}")
    }
}
