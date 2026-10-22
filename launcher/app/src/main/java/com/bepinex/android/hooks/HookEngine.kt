package com.bepinex.android.hooks

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point of the LSPlant-backed hook engine (libhookengine.so +
 * liblsplant.so), replacing Pine. Loading this object loads the native shim,
 * whose JNI_OnLoad initializes LSPlant with the unrestricted JNIEnv LSPlant
 * requires; hook installation is only possible after that succeeds, which
 * [isInitialized] reports.
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
     * Hooks [member] (a [Method] or [java.lang.reflect.Constructor]) with
     * [callback]. Returns false if the engine is not initialized or LSPlant
     * refused the hook; returns true if the member is already hooked.
     */
    fun hook(member: Member, callback: MethodHook): Boolean {
        hookers[member]?.let { return true }
        (member as? AccessibleObject)?.isAccessible = true
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

    private external fun nativeHook(target: Member, hooker: Hooker, callbackMethod: Method): Method?

    private external fun nativeUnhook(target: Member): Boolean

    private external fun nativeIsHooked(target: Member): Boolean
}
