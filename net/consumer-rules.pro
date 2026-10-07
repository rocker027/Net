-keep class okhttp3.** { *; }
-keep class com.drake.net.exception.** { *; }
# NetDeferred 以反射轉呼叫協程 1.9+ 的 Job.getParent()，混淆後方法名須保持不變
-keepclassmembernames interface kotlinx.coroutines.Job { kotlinx.coroutines.Job getParent(); }
