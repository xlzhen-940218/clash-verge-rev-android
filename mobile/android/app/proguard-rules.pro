# 保留 JS 桥接接口
-keep class io.github.clashverge.android.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 CMFA 核心模块
-keep class com.github.kr328.clash.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin 协程
-keepclassmembers class kotlinx.coroutines.** { *; }
