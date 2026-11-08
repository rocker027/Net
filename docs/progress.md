Net支持上传/下载的进度监听, 且具备完善的进度信息


## 上传进度监听

```kotlin
scopeNetLife {
    Post<String>(Api.UPLOAD) {
        param("file", assetsFile())
        addUploadListener(object : ProgressListener() {
            override fun onProgress(p: Progress) {
                seek.post {
                    seek.progress = p.progress()
                    tvProgress.text =
                        "上传进度: ${p.progress()}% 上传速度: ${p.speedSize()}     " +
                                "\n\n文件大小: ${p.totalSize()}  已上传: ${p.currentSize()}  剩余大小: ${p.remainSize()}" +
                                "\n\n已使用时间: ${p.useTime()}  剩余时间: ${p.remainTime()}"
                }
            }
        })
    }.await()
}
```



## 下载进度监听

```kotlin
scopeNetLife {
    val file =
        Get<File>("https://github.com/liangjingkanji/Net/releases/latest/download/net-sample.apk") {
            setDownloadFileName("net.apk")
            setDownloadDir(requireContext().filesDir)

            addDownloadListener(object : ProgressListener() {
                override fun onProgress(p: Progress) {
                    seek?.post {
                        val progress = p.progress()
                        seek.progress = progress
                        tvProgress.text =
                            "下载进度: $progress% 下载速度: ${p.speedSize()}     " +
                                    "\n\n文件大小: ${p.totalSize()}  已下载: ${p.currentSize()}  剩余大小: ${p.remainSize()}" +
                                    "\n\n已使用时间: ${p.useTime()}  剩余时间: ${p.remainTime()}"
                    }
                }
            })
        }.await()
}
```

!!! success "监听任何进度"
    不仅是泛型为File才有效, 任何请求体/响应体都可以监听进度

    `Net.addXXListener()`可以根据`Id`任意位置监听请求


## 寫入 StateFlow 或 Compose

每次回呼收到的 `Progress` 都是獨立快照，之後的進度更新不會改動已交出的實例。可以直接寫入 `MutableStateFlow`，由 Compose、ViewBinding 或 DataBinding 收集。

```kotlin
class DownloadViewModel : ViewModel() {
    private val mutableProgress = MutableStateFlow<Progress?>(null)
    val progress = mutableProgress.asStateFlow()
    private val mutableResult = MutableStateFlow<NetState<File>>(NetState.Idle)
    val result = mutableResult.asStateFlow()

    fun download(dir: File) = viewModelScope.launch {
        mutableResult.value = NetState.Loading
        // netResult 把失敗轉成 Failure，避免例外讓 viewModelScope 崩潰；取消仍向外傳遞
        mutableResult.value = netResult {
            netGet<File>("https://example.com/app.apk") {
                setDownloadDir(dir)
                addDownloadListener(object : ProgressListener() {
                    // 回呼在 OkHttp 的 IO 執行緒；MutableStateFlow 可跨執行緒寫入
                    override fun onProgress(p: Progress) {
                        mutableProgress.value = p
                    }
                })
            }
        }.toState()
    }
}
```

!!! warning "回呼執行緒"
    `onProgress` 在 OkHttp 讀寫請求體／回應體的執行緒執行，不是主執行緒。直接更新 View 前請切回主執行緒（例如 `view.post`）。

!!! note "相容性"
    舊版每次回呼都傳同一個可變物件。保存 `p` 參考並期待它持續更新的程式，需改成在每次回呼讀取新值。

## 监听器

实现`ProgressListener`监听进度信息, 进度信息为`Progress`

### 进度间隔时间

ProgressListener的构造参数`interval`控制触发进度监听器的间隔时间

### 进度信息

| 函数 | 描述 |
|-|-|
| currentBytes | 已完成字节数 |
| totalBytes | 全部大小字节数 |
| intervalBytes | 进度间隔时间内完成的字节数 |
| intervalTime | 距离上次进度变化间隔时间 |
| startElapsedRealtime | 开始下载时间(该时间值开机到现在的毫秒数) |
| speedBytes | 每秒下载字节数 |
| progress | 进度, 1-100 |
| finish | 此次下载/上传是否完成 |
| useTimeSeconds | 已经使用时间, 单位秒 |
| remainTimeSeconds | 估算的剩余时间, 单位秒 |

### 格式化字符串

| 函数 | 描述 |
|-|-|
| currentSize | 已完成大小, 例如: `120kB` 或者 `1.5MB` |
| totalSize | 全部大小 |
| remainSize | 剩余大小 |
| speedSize | 每秒下载大小 |
| useTime | 已使用时间, 格式为: `01:23:04` |
| remainTime | 估算的剩余时间, 格式为: `01:23:04`  |
