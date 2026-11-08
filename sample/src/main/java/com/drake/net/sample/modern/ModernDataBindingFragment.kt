package com.drake.net.sample.modern

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drake.net.sample.databinding.FragmentModernDataBindingBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * DataBinding + MVVM：布局直接绑定 [DemoMvvmViewModel.state]（StateFlow），
 * 由 [bindNetLoading]、[bindNetStateText] 把 NetState 转成显示状态。
 * 必须设置 lifecycleOwner，DataBinding 才会依 view 生命周期收集 StateFlow。
 */
class ModernDataBindingFragment : Fragment() {
    private val viewModel: DemoMvvmViewModel by viewModels { DemoViewModelFactory }
    private var _binding: FragmentModernDataBindingBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModernDataBindingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.lifecycleOwner = viewLifecycleOwner
        binding.vm = viewModel

        // 一次性提示无法用布局表达，仍在 view 生命周期内收集。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.errors.events.collect { failure ->
                    Snackbar.make(view, "全域错误：${failure.httpCode ?: failure.kind}", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
