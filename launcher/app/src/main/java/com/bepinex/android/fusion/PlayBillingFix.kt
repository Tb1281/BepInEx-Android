package com.bepinex.android.fusion

import android.os.Handler
import android.os.Looper
import com.bepinex.android.BepInExLog
import org.json.JSONArray
import org.json.JSONObject
import com.bepinex.android.hooks.HookEngine
import com.bepinex.android.hooks.CallFrame
import com.bepinex.android.hooks.MethodHook
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Mocks Google Play Billing Library (`com.android.billingclient.api.BillingClient`) responses
 * for games hosted under the BepInEx launcher sandbox.
 *
 * ## The failure this fixes
 *
 * When a hosted Unity game initializes In-App Purchasing (e.g. `Purchaser:Initialize()`),
 * it calls [BillingClient.queryProductDetailsAsync] or [BillingClient.querySkuDetailsAsync]
 * via Google Play Billing. In a launcher sandbox, Google Play Store (`com.android.vending`)
 * rejects foreign UID requests or returns errors (`retrieve products failed`).
 *
 * Games commonly retry `Initialize()` every frame upon failure. Each retry allocates JNI
 * global references (`QueryProductDetailsParams$Product` and `Class`) without freeing them.
 * Within seconds, 24,000+ references accumulate, exceeding Android's 51,200 limit and
 * triggering `JNI ERROR (app bug): global reference table overflow (max=51200)` (SIGABRT).
 *
 * ## Solution
 *
 * Intercepts [BillingClientImpl] and concrete subclasses ([zzcw]):
 * 1. [isReady] -> always returns `true`.
 * 2. [startConnection] -> dispatches [BillingClientStateListener.onBillingSetupFinished] with `BillingResult.OK`.
 * 3. [queryProductDetailsAsync] -> inspects requested product IDs and returns mock [ProductDetails]
 *    (supporting both v5/v6 `List<ProductDetails>` and v7+ `QueryProductDetailsResult`).
 * 4. [querySkuDetailsAsync] -> returns mock [SkuDetails] with `BillingResult.OK`.
 * 5. [queryPurchasesAsync] -> returns empty purchases with `BillingResult.OK`.
 *
 * ## Why the callbacks below allocate almost nothing
 *
 * The hooks fire on the game's own threads — binder and threadpool workers running
 * store SDK init bursts. Under Pine, the previous engine, the intercepted frame was
 * held as raw register addresses and converted to references lazily inside its
 * bridge (`Pine.getObject` -> native `AddLocalRef`, no validity check), so anything
 * that moved objects between frame capture and conversion (a GC triggered by
 * allocation on that same thread) turned the raw address stale and ART died in
 * `CheckReferenceResult` (SIGSEGV fault 0x60, observed 2026-10-12 on The Tower,
 * timing- and thread-variant, A/B-exonerated from the managed bytes).
 *
 * LSPlant, the current engine, closes that race structurally: its callbacks are
 * ordinary Java methods holding real references, so there is no raw-address
 * window. The allocation discipline below is retained anyway — the callbacks
 * still do no class/method/field LOOKUPS on the calling thread: everything
 * resolvable is resolved once in [install] (same loader, same search order as
 * before) and every per-class shape (dispatch methods, params fields, id/type
 * accessors) is cached on first use under one lock. What still allocates per
 * call is only what differs per call — the mock JSON payloads and the posted
 * Runnable — plus one small map key. Failure paths keep their logs; the hot
 * path stays quiet by design, not by accident.
 */
object PlayBillingFix {

    private const val TAG = "PlayBillingFix"

    @Volatile
    var isInstalled = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cacheLock = Any()

    /**
     * Everything resolvable without per-call data, resolved once in [install]
     * with the game's class loader. Set before any `HookEngine.hook` call, so hook
     * callbacks (which only run on later method entries) always see it via the
     * volatile write below. Null members mean "that billing shape is absent";
     * per-call code treats them exactly as today's failed lookups did.
     */
    private class BillingInfra(
        val newBuilder: Method,
        val setResponseCode: Method,
        val build: Method,
        val productDetailsCtor: Constructor<*>?,
        val queryResultCreate: Method?,
        val queryResultCtor: Constructor<*>?,
        val skuDetailsCtor: Constructor<*>?,
    )

    @Volatile
    private var infra: BillingInfra? = null

    /** (declaring class, method name, parameter count) -> resolved dispatch, null if absent. */
    private val dispatchCache = mutableMapOf<Triple<Class<*>, String, Int>, Method?>()

    /** Params class -> its declared fields (shapes don't change; values are read per call). */
    private val paramsFieldsCache = mutableMapOf<Class<*>, Array<Field>>()

    private class IdAccessor(val getter: Method?, val field: Field?)
    /** Item class -> how to read its product id / type (same search order as before). */
    private val idAccessorCache = mutableMapOf<Class<*>, IdAccessor>()
    private val typeAccessorCache = mutableMapOf<Class<*>, IdAccessor>()

    fun install(gameClassLoader: ClassLoader) {
        if (isInstalled) {
            BepInExLog.d("$TAG already installed")
            return
        }

        try {
            val billingClientClass = try {
                gameClassLoader.loadClass("com.android.billingclient.api.BillingClient")
            } catch (_: ClassNotFoundException) {
                BepInExLog.d("$TAG: BillingClient class not found, game does not use Google Play Billing")
                return
            }

            // Target classes that implement/extend BillingClient
            val targetClassNames = listOf(
                "com.android.billingclient.api.BillingClientImpl",
                "com.android.billingclient.api.zzcw"
            )

            val targetClasses = mutableListOf<Class<*>>()
            for (name in targetClassNames) {
                try {
                    targetClasses.add(gameClassLoader.loadClass(name))
                } catch (_: ClassNotFoundException) {
                    // Ignore if subclass name differs in another billing version
                }
            }

            if (targetClasses.isEmpty()) {
                BepInExLog.w("$TAG: No BillingClient implementation classes found")
                return
            }

            // Resolve once, before any hook can fire. Same loader and same
            // lookup order the per-call code used; failures are logged here
            // instead of once per intercepted call.
            infra = resolveInfra(gameClassLoader)

            for (clazz in targetClasses) {
                hookIsReady(clazz)
                hookStartConnection(gameClassLoader, clazz)
                hookQueryProductDetailsAsync(gameClassLoader, clazz)
                hookQuerySkuDetailsAsync(gameClassLoader, clazz)
                hookQueryPurchasesAsync(gameClassLoader, clazz)
            }

            isInstalled = true
            BepInExLog.i("$TAG: Google Play Billing mock hooks installed successfully on ${targetClasses.size} classes")
        } catch (e: Throwable) {
            BepInExLog.e("$TAG: Failed to install Play Billing hooks", e)
        }
    }

    private fun resolveInfra(loader: ClassLoader): BillingInfra? {
        return try {
            val resultClass = loader.loadClass("com.android.billingclient.api.BillingResult")
            val newBuilder = resultClass.getMethod("newBuilder")
            val builderClass = newBuilder.returnType
            val setResponseCode = builderClass.getMethod("setResponseCode", Int::class.javaPrimitiveType)
            val build = builderClass.getMethod("build")

            var pdCtor: Constructor<*>? = null
            try {
                val pdClass = loader.loadClass("com.android.billingclient.api.ProductDetails")
                pdCtor = pdClass.getDeclaredConstructor(String::class.java).also { it.isAccessible = true }
            } catch (_: Throwable) {
                BepInExLog.w("$TAG: ProductDetails constructor not found; product mocks will be skipped")
            }

            var qrCreate: Method? = null
            var qrCtor: Constructor<*>? = null
            try {
                val resultDetailClass = loader.loadClass("com.android.billingclient.api.QueryProductDetailsResult")
                qrCreate = resultDetailClass.declaredMethods.firstOrNull {
                    it.name == "create" && it.parameterCount == 2
                }?.also { it.isAccessible = true }
                if (qrCreate == null) {
                    qrCtor = resultDetailClass.declaredConstructors.firstOrNull { it.parameterCount == 2 }
                        ?.also { it.isAccessible = true }
                }
                if (qrCreate == null && qrCtor == null) {
                    BepInExLog.w("$TAG: QueryProductDetailsResult has neither create() nor a 2-arg constructor")
                }
            } catch (_: ClassNotFoundException) {
                // v5/v6 billing has no QueryProductDetailsResult; plain lists are used instead.
            }

            var skuCtor: Constructor<*>? = null
            try {
                val skuClass = loader.loadClass("com.android.billingclient.api.SkuDetails")
                skuCtor = skuClass.getDeclaredConstructor(String::class.java).also { it.isAccessible = true }
            } catch (_: Throwable) {
                BepInExLog.w("$TAG: SkuDetails constructor not found; sku mocks will be skipped")
            }

            BillingInfra(newBuilder, setResponseCode, build, pdCtor, qrCreate, qrCtor, skuCtor)
        } catch (e: Throwable) {
            BepInExLog.e("$TAG: Failed to resolve billing infrastructure", e)
            null
        }
    }

    /**
     * The one dispatch-method lookup in the codebase. Same search order as
     * before (public methods, then declared), accessible set once, result —
     * including absence — remembered so a miss costs one map lookup, not two
     * array scans, on every intercepted call.
     */
    private fun dispatchMethodOn(listenerClass: Class<*>, name: String, argc: Int): Method? {
        val key = Triple(listenerClass, name, argc)
        synchronized(cacheLock) {
            if (dispatchCache.containsKey(key)) return dispatchCache[key]
        }
        val found = listenerClass.methods.firstOrNull { it.name == name && it.parameterCount == argc }
            ?: listenerClass.declaredMethods.firstOrNull { it.name == name && it.parameterCount == argc }
        found?.isAccessible = true
        synchronized(cacheLock) {
            dispatchCache[key] = found
        }
        return found
    }

    private fun paramsFieldsOf(paramsClass: Class<*>): Array<Field> {
        synchronized(cacheLock) {
            paramsFieldsCache[paramsClass]?.let { return it }
        }
        val fields = paramsClass.declaredFields
        fields.forEach { it.isAccessible = true }
        synchronized(cacheLock) {
            paramsFieldsCache[paramsClass] = fields
        }
        return fields
    }

    private fun idAccessorOf(itemClass: Class<*>): IdAccessor {
        synchronized(cacheLock) {
            idAccessorCache[itemClass]?.let { return it }
        }
        val getter = itemClass.methods.firstOrNull {
            (it.name == "getProductId" || it.name == "zza") &&
                it.parameterCount == 0 && it.returnType == String::class.java
        }?.also { it.isAccessible = true }
        val field = if (getter == null) {
            itemClass.declaredFields.firstOrNull { it.name == "zza" && it.type == String::class.java }
                ?.also { it.isAccessible = true }
        } else null
        val acc = IdAccessor(getter, field)
        synchronized(cacheLock) {
            idAccessorCache[itemClass] = acc
        }
        return acc
    }

    private fun typeAccessorOf(itemClass: Class<*>): IdAccessor {
        synchronized(cacheLock) {
            typeAccessorCache[itemClass]?.let { return it }
        }
        val getter = itemClass.methods.firstOrNull {
            (it.name == "getProductType" || it.name == "zzb") &&
                it.parameterCount == 0 && it.returnType == String::class.java
        }?.also { it.isAccessible = true }
        val field = if (getter == null) {
            itemClass.declaredFields.firstOrNull { it.name == "zzb" && it.type == String::class.java }
                ?.also { it.isAccessible = true }
        } else null
        val acc = IdAccessor(getter, field)
        synchronized(cacheLock) {
            typeAccessorCache[itemClass] = acc
        }
        return acc
    }

    private fun hookIsReady(clazz: Class<*>) {
        val isReadyMethod = clazz.declaredMethods.firstOrNull {
            it.name == "isReady" && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
        } ?: return

        try {
            isReadyMethod.isAccessible = true
            HookEngine.hook(isReadyMethod, object : MethodHook() {
                override fun afterCall(callFrame: CallFrame) {
                    callFrame.result = true
                }
            })
            BepInExLog.d("$TAG: Hooked isReady on ${clazz.name}")
        } catch (e: Throwable) {
            BepInExLog.w("$TAG: Failed to hook isReady on ${clazz.name}: ${e.message}")
        }
    }

    private fun hookStartConnection(gameClassLoader: ClassLoader, clazz: Class<*>) {
        val startConnectionMethod = clazz.declaredMethods.firstOrNull {
            it.name == "startConnection" && it.parameterCount == 1
        } ?: return

        try {
            startConnectionMethod.isAccessible = true
            HookEngine.hook(startConnectionMethod, object : MethodHook() {
                override fun beforeCall(callFrame: CallFrame) {
                    val listener = callFrame.args.firstOrNull() ?: return
                    BepInExLog.i("$TAG: startConnection intercepted on ${clazz.name}")

                    val okResult = createBillingResultOk(gameClassLoader)
                    if (okResult != null) {
                        dispatchBillingSetupFinished(listener, okResult)
                        callFrame.setResult(null)
                    }
                }
            })
            BepInExLog.d("$TAG: Hooked startConnection on ${clazz.name}")
        } catch (e: Throwable) {
            BepInExLog.w("$TAG: Failed to hook startConnection on ${clazz.name}: ${e.message}")
        }
    }

    private fun dispatchBillingSetupFinished(listener: Any, billingResultOk: Any) {
        val method = dispatchMethodOn(listener.javaClass, "onBillingSetupFinished", 1)

        if (method != null) {
            mainHandler.post {
                try {
                    method.invoke(listener, billingResultOk)
                    BepInExLog.i("$TAG: Dispatched onBillingSetupFinished(OK)")
                } catch (t: Throwable) {
                    BepInExLog.e("$TAG: Error dispatching onBillingSetupFinished", t)
                }
            }
        } else {
            BepInExLog.w("$TAG: onBillingSetupFinished method not found on ${listener.javaClass.name}")
        }
    }

    private fun hookQueryProductDetailsAsync(gameClassLoader: ClassLoader, clazz: Class<*>) {
        val queryMethod = clazz.declaredMethods.firstOrNull {
            it.name == "queryProductDetailsAsync" && it.parameterCount == 2
        } ?: return

        try {
            queryMethod.isAccessible = true
            HookEngine.hook(queryMethod, object : MethodHook() {
                override fun beforeCall(callFrame: CallFrame) {
                    val params = callFrame.args.getOrNull(0) ?: return
                    val listener = callFrame.args.getOrNull(1) ?: return
                    BepInExLog.i("$TAG: queryProductDetailsAsync intercepted on ${clazz.name}")

                    val okResult = createBillingResultOk(gameClassLoader)
                    if (okResult != null) {
                        dispatchProductDetailsResponse(gameClassLoader, listener, params, okResult)
                        callFrame.setResult(null)
                    }
                }
            })
            BepInExLog.d("$TAG: Hooked queryProductDetailsAsync on ${clazz.name}")
        } catch (e: Throwable) {
            BepInExLog.w("$TAG: Failed to hook queryProductDetailsAsync on ${clazz.name}: ${e.message}")
        }
    }

    private fun dispatchProductDetailsResponse(
        loader: ClassLoader,
        listener: Any,
        params: Any,
        billingResultOk: Any
    ) {
        val requestedProducts = extractProductsFromParams(params)
        BepInExLog.i("$TAG: Extracted ${requestedProducts.size} requested products from params: $requestedProducts")

        val mockProductDetailsList = ArrayList<Any>()
        for ((productId, productType) in requestedProducts) {
            val pd = createMockProductDetails(loader, productId, productType)
            if (pd != null) {
                mockProductDetailsList.add(pd)
            }
        }

        val method = dispatchMethodOn(listener.javaClass, "onProductDetailsResponse", 2)

        if (method == null) {
            BepInExLog.w("$TAG: onProductDetailsResponse method not found on ${listener.javaClass.name}")
            return
        }

        val secondParamType = method.parameterTypes[1]

        val secondArg: Any = if (secondParamType.name.contains("QueryProductDetailsResult")) {
            createQueryProductDetailsResult(loader, mockProductDetailsList) ?: mockProductDetailsList
        } else {
            mockProductDetailsList
        }

        mainHandler.post {
            try {
                method.invoke(listener, billingResultOk, secondArg)
                BepInExLog.i("$TAG: Successfully dispatched onProductDetailsResponse with ${mockProductDetailsList.size} mock products")
            } catch (t: Throwable) {
                BepInExLog.e("$TAG: Error dispatching onProductDetailsResponse", t)
            }
        }
    }

    private fun createQueryProductDetailsResult(loader: ClassLoader, products: List<Any>): Any? {
        // The factory shape was resolved at install; per call only invokes.
        // The loader parameter is kept (callers pass it through) but no
        // lookup happens here anymore.
        val current = infra
        return try {
            when {
                current?.queryResultCreate != null ->
                    current.queryResultCreate.invoke(null, products, emptyList<Any>())
                current?.queryResultCtor != null ->
                    current.queryResultCtor.newInstance(products, emptyList<Any>())
                else -> {
                    BepInExLog.e("$TAG: Failed to create QueryProductDetailsResult")
                    null
                }
            }
        } catch (e: Throwable) {
            BepInExLog.e("$TAG: Failed to create QueryProductDetailsResult", e)
            null
        }
    }

    private fun createMockProductDetails(loader: ClassLoader, productId: String, productType: String?): Any? {
        val ctor = infra?.productDetailsCtor
        if (ctor == null) {
            BepInExLog.e("$TAG: Failed to create mock ProductDetails for $productId")
            return null
        }
        return try {
            val type = if (productType.isNullOrBlank()) "inapp" else productType
            val json = JSONObject().apply {
                put("productId", productId)
                put("type", type)
                put("title", productId)
                put("name", productId)
                put("description", productId)
                if (type == "subs") {
                    put("subscriptionOfferDetails", JSONArray())
                } else {
                    put("oneTimePurchaseOfferDetails", JSONObject().apply {
                        put("formattedPrice", "$0.00")
                        put("priceAmountMicros", 0L)
                        put("priceCurrencyCode", "USD")
                    })
                }
            }
            ctor.newInstance(json.toString())
        } catch (e: Throwable) {
            BepInExLog.e("$TAG: Failed to create mock ProductDetails for $productId", e)
            null
        }
    }

    private fun extractProductsFromParams(params: Any): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (field in paramsFieldsOf(params.javaClass)) {
            try {
                val value = field.get(params)
                if (value is List<*>) {
                    for (item in value) {
                        if (item != null) {
                            val id = extractProductId(item)
                            val type = extractProductType(item)
                            if (!id.isNullOrBlank()) {
                                result.add(Pair(id, type ?: "inapp"))
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        return result
    }

    private fun extractProductId(product: Any): String? {
        val acc = idAccessorOf(product.javaClass)
        try {
            acc.getter?.let {
                val r = it.invoke(product) as? String
                if (!r.isNullOrBlank()) return r
            }
            acc.field?.let {
                val r = it.get(product) as? String
                if (!r.isNullOrBlank()) return r
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun extractProductType(product: Any): String? {
        val acc = typeAccessorOf(product.javaClass)
        try {
            acc.getter?.let {
                val r = it.invoke(product) as? String
                if (!r.isNullOrBlank()) return r
            }
            acc.field?.let {
                val r = it.get(product) as? String
                if (!r.isNullOrBlank()) return r
            }
        } catch (_: Throwable) {}
        return "inapp"
    }

    private fun hookQueryPurchasesAsync(gameClassLoader: ClassLoader, clazz: Class<*>) {
        val queryMethod = clazz.declaredMethods.firstOrNull {
            it.name == "queryPurchasesAsync" && it.parameterCount == 2
        } ?: return

        try {
            queryMethod.isAccessible = true
            HookEngine.hook(queryMethod, object : MethodHook() {
                override fun beforeCall(callFrame: CallFrame) {
                    val listener = callFrame.args.getOrNull(1) ?: return
                    BepInExLog.i("$TAG: queryPurchasesAsync intercepted on ${clazz.name}")
                    val okResult = createBillingResultOk(gameClassLoader)
                    if (okResult != null) {
                        dispatchQueryPurchasesResponse(listener, okResult)
                        callFrame.setResult(null)
                    }
                }
            })
            BepInExLog.d("$TAG: Hooked queryPurchasesAsync on ${clazz.name}")
        } catch (e: Throwable) {
            BepInExLog.w("$TAG: Failed to hook queryPurchasesAsync on ${clazz.name}: ${e.message}")
        }
    }

    private fun dispatchQueryPurchasesResponse(listener: Any, billingResultOk: Any) {
        val method = dispatchMethodOn(listener.javaClass, "onQueryPurchasesResponse", 2)

        if (method != null) {
            mainHandler.post {
                try {
                    method.invoke(listener, billingResultOk, emptyList<Any>())
                    BepInExLog.i("$TAG: Dispatched onQueryPurchasesResponse(OK, emptyList)")
                } catch (t: Throwable) {
                    BepInExLog.e("$TAG: Error dispatching onQueryPurchasesResponse", t)
                }
            }
        }
    }

    private fun hookQuerySkuDetailsAsync(gameClassLoader: ClassLoader, clazz: Class<*>) {
        val queryMethod = clazz.declaredMethods.firstOrNull {
            it.name == "querySkuDetailsAsync" && it.parameterCount == 2
        } ?: return

        try {
            queryMethod.isAccessible = true
            HookEngine.hook(queryMethod, object : MethodHook() {
                override fun beforeCall(callFrame: CallFrame) {
                    val params = callFrame.args.getOrNull(0) ?: return
                    val listener = callFrame.args.getOrNull(1) ?: return
                    BepInExLog.i("$TAG: querySkuDetailsAsync intercepted on ${clazz.name}")
                    val okResult = createBillingResultOk(gameClassLoader)
                    if (okResult != null) {
                        dispatchSkuDetailsResponse(gameClassLoader, listener, params, okResult)
                        callFrame.setResult(null)
                    }
                }
            })
            BepInExLog.d("$TAG: Hooked querySkuDetailsAsync on ${clazz.name}")
        } catch (e: Throwable) {
            BepInExLog.w("$TAG: Failed to hook querySkuDetailsAsync on ${clazz.name}: ${e.message}")
        }
    }

    private fun dispatchSkuDetailsResponse(
        loader: ClassLoader,
        listener: Any,
        params: Any,
        billingResultOk: Any
    ) {
        val skuList = mutableListOf<String>()
        for (f in paramsFieldsOf(params.javaClass)) {
            try {
                val v = f.get(params)
                if (v is List<*>) {
                    for (item in v) {
                        if (item is String && item.isNotBlank()) skuList.add(item)
                    }
                }
            } catch (_: Throwable) {}
        }

        val mockSkuDetailsList = ArrayList<Any>()
        val ctor = infra?.skuDetailsCtor
        for (sku in skuList) {
            if (ctor == null) break
            try {
                val json = JSONObject().apply {
                    put("productId", sku)
                    put("type", "inapp")
                    put("price", "$0.00")
                    put("price_amount_micros", 0L)
                    put("price_currency_code", "USD")
                    put("title", sku)
                    put("description", sku)
                }
                mockSkuDetailsList.add(ctor.newInstance(json.toString()))
            } catch (_: Throwable) {}
        }

        val method = dispatchMethodOn(listener.javaClass, "onSkuDetailsResponse", 2)

        if (method != null) {
            mainHandler.post {
                try {
                    method.invoke(listener, billingResultOk, mockSkuDetailsList)
                    BepInExLog.i("$TAG: Successfully dispatched onSkuDetailsResponse with ${mockSkuDetailsList.size} mock skus")
                } catch (t: Throwable) {
                    BepInExLog.e("$TAG: Error dispatching onSkuDetailsResponse", t)
                }
            }
        }
    }

    private fun createBillingResultOk(loader: ClassLoader): Any? {
        // Builder shape resolved at install; per call is three invokes, zero
        // lookups. The loader parameter is kept for signature stability.
        val current = infra ?: run {
            BepInExLog.e("$TAG: Failed to create BillingResult.OK")
            return null
        }
        return try {
            val builder = current.newBuilder.invoke(null)
            current.setResponseCode.invoke(builder, 0) // BillingResponseCode.OK = 0
            current.build.invoke(builder)
        } catch (e: Throwable) {
            BepInExLog.e("$TAG: Failed to create BillingResult.OK", e)
            null
        }
    }
}
