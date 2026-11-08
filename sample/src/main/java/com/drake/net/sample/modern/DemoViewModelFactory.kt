package com.drake.net.sample.modern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** 示范用 ViewModel 工厂；正式项目通常由依赖注入提供 Repository。Compose、ViewBinding、DataBinding 画面共用。 */
object DemoViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        DemoMvvmViewModel::class.java -> DemoMvvmViewModel(LocalDemoRepository()) as T
        DemoMviViewModel::class.java -> DemoMviViewModel(LocalDemoRepository()) as T
        else -> error("Unknown ViewModel: $modelClass")
    }
}
