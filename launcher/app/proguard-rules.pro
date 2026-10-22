# ==============================================================================
# Safe R8 Configuration (Tree-shaking only, no obfuscation, no bytecode mutations)
# ==============================================================================

# 1. Disable Obfuscation:
# Guarantees that class and member names remain identical for JNI, reflection,
# dynamic classloading, and runtime interop.
-dontobfuscate

# 2. Disable Aggressive Bytecode Optimization:
# Prevents method inlining, argument elimination, and class merging,
# which ensures Pine dynamic ART hooks find exact method descriptors.
-dontoptimize

# 3. Preserve Essential Metadata & Attributes:
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# 4. Keep Unity-related classes — required by native libmain.so JNI
-keep class com.unity3d.player.** { *; }

# 5. Keep all BepInEx launcher and fusion classes
-keep class com.bepinex.** { *; }
-dontwarn com.bepinex.**

# 6. Keep the Android Crypto PAL's Java classes and members by exact name.
# libSystem.Security.Cryptography.Native.Android.so resolves every one of these
# by JNI class name + member name + descriptor, from provenance/pal-java-contract.txt.
-keep class net.dot.android.crypto.** { *; }
-dontwarn net.dot.android.crypto.**

# 7. Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# 8. Keep JNI callback classes
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 9. Keep Pine hook framework
-keep class top.canyie.pine.** { *; }
-dontwarn top.canyie.pine.**

# 10. Keep Firebase, Google Play Services, and Billing SDKs
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**
-keep class com.android.billingclient.** { *; }
-dontwarn com.android.billingclient.**

# 11. Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}


# LSPlant hook facade: Hooker.callback is looked up by name through
# reflection and invoked from LSPlant's generated stub classes, and
# HookEngine's native methods must keep their names for JNI registration.
-keep class com.bepinex.android.hooks.** { *; }
