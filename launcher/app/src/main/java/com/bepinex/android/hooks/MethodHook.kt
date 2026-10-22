package com.bepinex.android.hooks

import java.lang.reflect.Member

/**
 * Callback for a hooked method or constructor, shaped after the Pine
 * MethodHook this codebase used before the LSPlant migration so hook sites
 * keep their structure. [beforeCall] runs before the original; [afterCall]
 * always runs afterwards (including when [beforeCall] short-circuited the
 * original by setting a result), and may replace the result or throwable.
 *
 * An exception thrown by a callback never escapes into the hooked call:
 * like Pine, the engine logs it and proceeds (for [beforeCall], with the
 * original invocation; for [afterCall], with the result already computed).
 */
abstract class MethodHook {
    open fun beforeCall(callFrame: CallFrame) {}

    open fun afterCall(callFrame: CallFrame) {}
}

/**
 * One intercepted invocation.
 *
 * Semantics follow the Pine CallFrame the sites were written against:
 * assigning [result] (or calling [setResult]) inside
 * [MethodHook.beforeCall] skips the original method; [throwable] set by the
 * original (or by a callback) is rethrown to the caller after
 * [MethodHook.afterCall] unless a callback clears it.
 */
class CallFrame internal constructor(
    /** The hooked [java.lang.reflect.Method] or [java.lang.reflect.Constructor]. */
    val method: Member,
    /** Receiver of the invocation; null for static methods. For constructors, the instance under construction. */
    val thisObject: Any?,
    /** Invocation arguments, without the receiver. Mutating elements rewrites the call. */
    val args: Array<Any?>,
    private val hooker: Hooker,
) {
    private var _result: Any? = null

    /**
     * The invocation result. Assigning it in beforeCall skips the original.
     * Assigning it also clears [throwable]: a callback that produces a result
     * for a call that failed (the ClassLoaderHooks cross-loader fallback is
     * the canonical case) means the call to succeed, following the Xposed
     * setResult semantics Pine mirrored.
     */
    // The JVM setter name is moved aside so the explicit setResult() below
    // (the Pine-shaped API the hook sites call) can own it without a platform
    // declaration clash; Kotlin callers are unaffected.
    @set:JvmName("setResultProperty")
    var result: Any?
        get() = _result
        set(value) {
            _result = value
            resultSet = true
            _throwable = null
        }

    internal var resultSet = false

    private var _throwable: Throwable? = null

    /**
     * The exception the invocation failed with, if any. Assigning a non-null
     * throwable discards any result: the invocation fails with it after
     * afterCall unless a callback assigns a result or clears this.
     */
    var throwable: Throwable?
        get() = _throwable
        set(value) {
            _throwable = value
            if (value != null) {
                _result = null
                resultSet = false
            }
        }

    fun hasThrowable(): Boolean = _throwable != null

    fun setResult(result: Any?) {
        this.result = result
    }

    internal fun setOriginalResult(value: Any?) {
        _result = value
    }

    /**
     * Invokes the original method on an explicit receiver with explicit
     * arguments, bypassing this hook. Receiver and arguments need not be the
     * frame's own (ClassLoaderHooks calls the original on the *other*
     * class loader). Throws the original's own exception, unwrapped.
     */
    fun invokeOriginalMethod(thisObject: Any?, vararg args: Any?): Any? =
        hooker.invokeOriginal(thisObject, args)
}
