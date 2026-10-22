package com.bepinex.android.hooks

import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The LSPlant hooker object for one hooked member. LSPlant generates a stub
 * class per hook whose static field holds this object and whose entry point
 * forwards every intercepted call to [callback], so the instance must stay
 * alive for as long as the hook is installed (HookEngine's registry does).
 *
 * The backup is the Method LSPlant returns from its Hook call: a copy of the
 * original ArtMethod in the generated class. Invoking it reflectively runs
 * the original code on the given receiver — including for constructors,
 * where the receiver is the already-allocated instance and the original
 * <init> body runs on it (the pattern LSPlant's own test app uses).
 */
class Hooker internal constructor(
    val target: Member,
    val callback: MethodHook,
) {
    private companion object {
        const val TAG = "HookEngine"
    }

    @Volatile
    var backup: Method? = null

    /**
     * LSPlant callback contract: `public Object callback(Object[] args)`.
     * args[0] is the receiver for instance methods and constructors; static
     * methods receive only their parameters, with no placeholder.
     */
    fun callback(args: Array<Any?>): Any? {
        val isStatic = target is Method && Modifier.isStatic(target.modifiers)
        val thisObject: Any?
        val callArgs: Array<Any?>
        if (isStatic) {
            thisObject = null
            callArgs = args
        } else {
            thisObject = args.getOrNull(0)
            callArgs = if (args.size <= 1) emptyArray() else args.copyOfRange(1, args.size)
        }

        val frame = CallFrame(target, thisObject, callArgs, this)
        // Callback exceptions never escape into the hooked call: Pine (and
        // Xposed) catch them, log, and let the original proceed. A site
        // written against Pine may dereference state that is only valid
        // later in the callee (Activity.getApplication() during attach is
        // null, and InstrumentationHooks' beforeCall throws on it) and
        // still have worked for years because the throw stopped there.
        try {
            callback.beforeCall(frame)
        } catch (t: Throwable) {
            Log.e(TAG, "beforeCall threw for $target; continuing with original call", t)
        }
        if (!frame.resultSet && frame.throwable == null) {
            try {
                frame.setOriginalResult(invokeOriginal(thisObject, frame.args))
            } catch (t: Throwable) {
                frame.throwable = t
            }
        }
        try {
            callback.afterCall(frame)
        } catch (t: Throwable) {
            Log.e(TAG, "afterCall threw for $target; keeping the call result", t)
        }
        frame.throwable?.let { throw it }
        return frame.result
    }

    fun invokeOriginal(thisObject: Any?, args: Array<out Any?>): Any? {
        val backupMethod = backup
            ?: throw IllegalStateException("Hook is not installed for $target")
        try {
            return backupMethod.invoke(thisObject, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }
}
