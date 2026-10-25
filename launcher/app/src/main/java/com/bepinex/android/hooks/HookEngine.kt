package com.bepinex.android.hooks

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point of the LSPlant-backed hook engine (libhookengine.so +
 * liblsplant.so), replacing Pine. Loading this object loads the native shim,
 * whose JNI_OnLoad parses libart's symbol table. LSPlant itself is initialized
 * lazily on the first [isInitialized] or [hook] call: the hidden-API bypass
 * ([disableHiddenApi]) must be usable before PairIP's startup programs have
 * finished, and LSPlant is what PairIP reacts to.
 *
 * The registry is load-bearing twice over: it makes [hook] idempotent per
 * member (LSPlant itself also refuses a duplicate hook, but the facade needs
 * the same answer before and after), and it keeps each [Hooker] — which the
 * generated stub references — strongly reachable for the hook's lifetime.
 */
object HookEngine {
    private val hookers = ConcurrentHashMap<Member, Hooker>()

    private val callbackMethod: Method by lazy {
        Hooker::class.java.getDeclaredMethod("callback", Array<Any?>::class.java)
    }

    init {
        System.loadLibrary("hookengine")
    }

    fun isInitialized(): Boolean = nativeIsInitialized()

    /**
     * Installs the process-wide hidden-API allow policy. Must be called
     * before resolving framework internals that the SDK does not expose.
     */
    fun disableHiddenApi(): Boolean = nativeDisableHiddenApi()

    /**
     * Removes the hidden-API allow policy again, restoring ART's original
     * decision points. The PairIP preload path uses this after loading
     * libmain so PairIP's DT_INIT programs see an unmodified libart.
     */
    fun restoreHiddenApi(): Boolean = nativeRestoreHiddenApi()

    /**
     * Resolves a method through JNI, bypassing the managed hidden-API policy.
     * Returns a [Method] that [hook] can install directly.
     */
    fun findMethod(
        clazz: Class<*>,
        name: String,
        signature: String,
        isStatic: Boolean
    ): Method? = nativeFindMethod(clazz, name, signature, isStatic)

    /**
     * Hooks [member] (a [Method] or [java.lang.reflect.Constructor]) with
     * [callback]. Returns false if the engine is not initialized or LSPlant
     * refused the hook; returns true if the member is already hooked.
     */
    fun hook(member: Member, callback: MethodHook): Boolean {
        hookers[member]?.let { return true }
        try {
            (member as? AccessibleObject)?.isAccessible = true
        } catch (_: Throwable) {
            // JNI-resolved framework methods may refuse the accessibility
            // change; LSPlant hooks the underlying jmethodID regardless.
        }
        val hooker = Hooker(member, callback)
        if (hookers.putIfAbsent(member, hooker) != null) return true
        val backup = nativeHook(member, hooker, callbackMethod)
        if (backup == null) {
            hookers.remove(member, hooker)
            return false
        }
        hooker.backup = backup
        return true
    }

    fun unhook(member: Member): Boolean {
        hookers.remove(member) ?: return false
        return nativeUnhook(member)
    }

    fun isHooked(member: Member): Boolean = hookers.containsKey(member)

    private external fun nativeIsInitialized(): Boolean

    private external fun nativeDisableHiddenApi(): Boolean

    private external fun nativeRestoreHiddenApi(): Boolean

    private external fun nativeFindMethod(
        clazz: Class<*>,
        name: String,
        signature: String,
        isStatic: Boolean
    ): Method?

    private external fun nativeHook(target: Member, hooker: Hooker, callbackMethod: Method): Method?

    private external fun nativeUnhook(target: Member): Boolean

    private external fun nativeIsHooked(target: Member): Boolean
}
