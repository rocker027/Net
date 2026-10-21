多個請求並行執行，`fastest` 選出第一個成功的結果。候選全部失敗時，拋出最後一個處理到的錯誤。

`Get`／`Post` 是一般 `async` 子協程。要忽略先失敗的候選，必須在 `supervisorScope` 中建立候選，避免候選失敗先取消父協程。以下範例需匯入 `kotlinx.coroutines.supervisorScope`；錯誤回退另需 `kotlinx.coroutines.CancellationException`。

```kotlin
scopeNetLife {
    supervisorScope {
        val first = Get<String>("path") // 可失敗的候選
        val second = Get<String>("path1")
        val third = Post<String>("path2")

        tv.text = fastest(listOf(first, second, third))
    }
}
```

未指定 `group` 時，`fastest` 回傳後保留原始候選任務；外層 `supervisorScope` 仍會等待自己的子協程結束。若要成功後取消其餘網路請求，請使用下節的同一分組。呼叫端取消仍會取消整個結構化協程範圍，不會被當成候選失敗忽略。

## 取消剩餘請求

每個請求設定相同的分組，並將該分組傳給 `fastest`：

```kotlin
scopeNetLife {
    supervisorScope {
        val first = Get<String>("path") { setGroup("初始化") }
        val second = Post<String>("path1") { setGroup("初始化") }
        val third = Get<String>("path2") { setGroup("初始化") }

        tv.text = fastest(listOf(first, second, third), "初始化")
    }
}
```

成功選出結果後會取消該分組仍在執行的 Net 請求。分組應限於這次競速，避免取消其他同名分組的請求。

## 轉換候選結果

候選回傳型別不同時，可以使用 `transform` 轉成共同的結果型別：

```kotlin
scopeNetLife {
    supervisorScope {
        val first = Post<String>("path").transform { value ->
            "Post: $value"
        }
        val second = Get<String>("path1").transform { value ->
            "Get: $value"
        }

        tv.text = fastest(listOf(first, second))
    }
}
```

候選結果依抵達順序嘗試轉換，第一個轉換成功的結果勝出。若轉換拋出錯誤，會繼續等待其他候選；成功選出結果後，不再執行其他候選的 `transform`。全部請求或轉換失敗時，`fastest` 會拋出錯誤。

## 錯誤回退

所有候選失敗後，可以在相同的 `supervisorScope` 建立備援請求。不要捕獲並吞掉呼叫端取消。

```kotlin
scopeNetLife {
    supervisorScope {
        val first = Get<String>("path")
        val second = Get<String>("path1")

        val data = try {
            fastest(listOf(first, second))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val backup = Get<String>("path2")
            val backup2 = Get<String>("path3")
            fastest(listOf(backup, backup2))
        }
        tv.text = data
    }
}
```
