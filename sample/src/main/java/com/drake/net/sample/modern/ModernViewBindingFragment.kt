package com.drake.net.sample.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drake.net.result.NetState
import com.drake.net.sample.databinding.FragmentModernViewBindingBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * ViewBinding + MVVM：与 Compose 画面共用 [DemoMvvmViewModel]，只有收集状态的方式不同。
 * 收集绑定 viewLifecycleOwner 并在 STARTED 时执行，view 销毁后不会再访问 binding。
 */
class ModernViewBindingFragment : Fragment() {
    private val viewModel: DemoMvvmViewModel by viewModels { DemoViewModelFactory }
    private var _binding: FragmentModernViewBindingBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModernViewBindingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnSuccess.setOnClickListener { viewModel.load(DemoScenario.SUCCESS) }
        binding.btnHttpError.setOnClickListener { viewModel.load(DemoScenario.HTTP_ERROR) }
        binding.btnSlow.setOnClickListener { viewModel.load(DemoScenario.SLOW) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                // 全域错误只做即时提示，画面错误以 state 为准。
                launch {
                    viewModel.errors.events.collect { failure ->
                        Snackbar.make(view, "全域错误：${failure.httpCode ?: failure.kind}", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun render(state: NetState<String>) {
        binding.progress.isVisible = state is NetState.Loading
        binding.tvState.text = state.toDisplayText()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
