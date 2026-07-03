# ==============================================================================
# 百度人脸 SDK 混淆规则 (已修复缺失的 com.baidu.vis 包)
# ==============================================================================
-keep class com.baidu.idl.main.facesdk.** { *; }
-keep class com.baidu.vis.** { *; }
-keep class com.punch.app.face.** { *; }

-dontwarn com.baidu.**
-dontwarn com.punch.app.face.**

# 保护所有 native 方法以及它们所在的类，防止 C/C++ 与 Java 互相反射调用时找不到方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==============================================================================
# 三方库与基础框架
# ==============================================================================
# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# 本应用所有 Model（序列化/反序列化）
-keep class com.punch.app.model.** { *; }

# AndroidX
-keep class androidx.** { *; }