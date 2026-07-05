# ============================================================================
# LLM Social - ProGuard / R8 规则
# ============================================================================

# ----------------------------------------------------------------------------
# 通用优化
# ----------------------------------------------------------------------------
# 保留注解元信息
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations

# 抑制 R8 警告
-dontwarn org.jetbrains.annotations.**

# ----------------------------------------------------------------------------
# Hilt (Dagger)
# ----------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class dagger.hilt.android.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory$ViewModelFactoriesEntryPoint { *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface kotlin.coroutines.Continuation

# Hilt 生成的类
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep,allowobfuscation,allowshrinking class * extends androidx.lifecycle.ViewModel

# Dagger 元件
-keep,allowobfuscation,allowshrinking @dagger.hilt.DefineComponent class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.InstallIn class *

# ----------------------------------------------------------------------------
# Room
# ----------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# ----------------------------------------------------------------------------
# Retrofit
# ----------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Retrofit 保留接口方法
-keep,allowobfuscation,allowshrinking @retrofit2.http.* interface *
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ----------------------------------------------------------------------------
# Kotlinx Serialization
# ----------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.trae.social.**$$serializer { *; }
-keepclassmembers class com.trae.social.** {
    *** Companion;
}
-keepclasseswithmembers class com.trae.social.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class ** { *; }

# ----------------------------------------------------------------------------
# Compose
# ----------------------------------------------------------------------------
# 保留 Compose runtime 与 metadata
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-dontwarn androidx.compose.**

# ----------------------------------------------------------------------------
# WorkManager
# ----------------------------------------------------------------------------
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ----------------------------------------------------------------------------
# CameraX
# ----------------------------------------------------------------------------
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ----------------------------------------------------------------------------
# 应用自身模型（序列化数据类）
# ----------------------------------------------------------------------------
-keep class com.trae.social.**.model.** { *; }
-keep class com.trae.social.**.entity.** { *; }
-keep class com.trae.social.**.dto.** { *; }

# ----------------------------------------------------------------------------
# 协程
# ----------------------------------------------------------------------------
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }

# ----------------------------------------------------------------------------
# Timber
# ----------------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.** { *; }
