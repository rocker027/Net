# ViewBinding 與 DataBinding

ViewBinding、DataBinding 與 Compose 可以共用同一個 ViewModel，只有最後收集狀態的方式不同。請求與狀態的寫法見 [MVVM／MVI／Compose 指南](state-api.md)，本頁只說明 View 系統的接法。

```text
Repository（netGet 等）→ ViewModel（netResult／netFlow → StateFlow<UiState>）→ UI 收集
```

| UI | 收集方式 | sample |
| --- | --- | --- |
| Compose | `collectAsStateWithLifecycle()` | [ModernRequestFragment.kt](https://github.com/rocker027/Net/blob/master/sample/src/main/java/com/drake/net/sample/modern/ModernRequestFragment.kt) |
| ViewBinding | `viewLifecycleOwner` + `repeatOnLifecycle(STARTED)` | [ModernViewBindingFragment.kt](https://github.com/rocker027/Net/blob/master/sample/src/main/java/com/drake/net/sample/modern/ModernViewBindingFragment.kt) |
| DataBinding | 布局直接綁 StateFlow，並設定 `lifecycleOwner` | [ModernDataBindingFragment.kt](https://github.com/rocker027/Net/blob/master/sample/src/main/java/com/drake/net/sample/modern/ModernDataBindingFragment.kt) |

三個畫面都從 sample 側邊選單進入，共用 [DemoMvvmViewModel](https://github.com/rocker027/Net/blob/master/sample/src/main/java/com/drake/net/sample/modern/DemoViewModels.kt)。

## ViewBinding

在 Fragment 中，收集必須綁 `viewLifecycleOwner`，不要綁 Fragment 本身。Fragment 留在返回堆疊時 view 已銷毀，但 Fragment 仍存活，這時更新 binding 會出錯。

```kotlin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    binding.btnLoad.setOnClickListener { viewModel.load() }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.state.collect { state ->
                binding.progress.isVisible = state is NetState.Loading
                binding.tvState.text = state.toDisplayText()
            }
        }
    }
}

override fun onDestroyView() {
    _binding = null
    super.onDestroyView()
}
```

- `toDisplayText()` 是 sample 的文案函式（[NetStateDisplay.kt](https://github.com/rocker027/Net/blob/master/sample/src/main/java/com/drake/net/sample/modern/NetStateDisplay.kt)），請換成 App 自己的文案轉換。
- `repeatOnLifecycle(STARTED)` 在畫面進入背景時停止收集，回到前景再重新收集。StateFlow 會立即補上最新狀態。
- `repeatOnLifecycle` 需要 `androidx.lifecycle:lifecycle-runtime-ktx` 2.4 以上。Net 以 `implementation` 依賴 lifecycle，不會傳遞到 App 的編譯 classpath，請在 App 自行宣告。
- 全域錯誤提示同樣在這個區塊內收集 `NetConfig.errorDispatcher.events`，見 sample。

## DataBinding

AGP 7.0 以上的 DataBinding 可以直接綁 `StateFlow`。綁定前必須設定 `lifecycleOwner`，否則布局不會更新。

```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    binding.lifecycleOwner = viewLifecycleOwner
    binding.vm = viewModel
}
```

`NetState` 是 sealed 型別，在 XML 判斷型別需要 `instanceof` 和轉型，不好讀。建議在 App 端寫 BindingAdapter，把 `NetState` 轉成顯示狀態：

```kotlin
@BindingAdapter("netLoading")
fun bindNetLoading(view: View, state: NetState<*>?) {
    view.isVisible = state is NetState.Loading
}

@BindingAdapter("netStateText")
fun bindNetStateText(view: TextView, state: NetState<*>?) {
    view.text = (state ?: NetState.Idle).toDisplayText()
}
```

```xml
<ProgressBar
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    app:netLoading="@{vm.state}" />

<TextView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:netStateText="@{vm.state}" />
```

- 失敗文案請依 `NetFailure.kind` 或業務碼轉成字串資源，不要直接顯示 `cause.message`。
- BindingAdapter 依賴 DataBinding，放在 App 模組。Net 核心不依賴 DataBinding。
- 一次性提示（Snackbar、導航）無法用布局表達，仍依 ViewBinding 段落的方式收集。

## 舊作用域在 View 系統的生命週期

既有頁面可以繼續使用舊作用域。綁定規則如下：

| 作用域 | 取消時機 |
| --- | --- |
| `Fragment.scopeLife`／`Fragment.scopeNetLife` | 呼叫時已有 view：目前 view 到 `lifeEvent`。尚無 view：下一個 view 到 `lifeEvent` |
| `Fragment.scopeDialog` | 對話框被取消、Fragment 的 view 銷毀或 Activity 銷毀，取最早者 |
| `View.scopeNetLife`、`StateLayout.scope`、`PageRefreshLayout.scope` | view tree 的 LifecycleOwner 銷毀。祖先鏈尚無 owner（例如尚未加入父層的 view）時，在第一次 attach 後綁定 |
| `Flow.launchIn(owner)` | owner 到 `event` 才取消，背景時不暫停 |

- 在 `onDestroyView` 之中或之後、下一個 view 建立前呼叫 Fragment 版作用域，要到下一個 view 銷毀才會取消。這段期間不要存取 binding。
- 以下情況 Fragment 版作用域不會自動取消，只會等請求自然結束：上述時段呼叫後 Fragment 沒有再建立 view 就銷毀；只覆寫 `onCreateDialog`、沒有 view 的 DialogFragment。後者的 `scopeDialog` 仍會在對話框取消或 Activity 銷毀時取消。
- view 銷毀造成的取消，`finally` 會帶著 `CancellationException` 執行，`catch` 不會因取消被呼叫。使用預設的 `Dispatchers.Main` 時，`finally` 在 `onDestroyView` 之後才執行，不可在裡面存取 binding。
- View 作用域已 attach 但找不到 LifecycleOwner 時（例如不是 `ComponentActivity` 的畫面），不會自動取消。開啟 `NetConfig.debug` 時會記錄一行日誌。
- 在 owner 已銷毀的 view 上建立 View 作用域（例如 Fragment 重用上一個 view 的 root，或在 `onDestroyView` 內建立），不會自動取消，與舊版相同。
- view 從畫面 detach 不會取消 View 作用域。RecyclerView 等容器會反覆 detach 和 attach 同一個 view。
- `Flow.launchIn(owner)` 在 Fragment 請傳 `viewLifecycleOwner`。需要在背景暫停時，改用上方 `repeatOnLifecycle` 寫法。

## 進度

`ProgressListener.onProgress` 每次收到獨立的 `Progress` 快照，可以直接寫入 `MutableStateFlow`，再用上方任一方式收集。回呼在 OkHttp 的 IO 執行緒，不要在回呼內直接操作 View。詳見 [進度監聽](progress.md)。
