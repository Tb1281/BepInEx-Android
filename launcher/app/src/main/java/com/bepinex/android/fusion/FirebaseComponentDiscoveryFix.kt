package com.bepinex.android.fusion

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.bepinex.android.BepInExLog
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook

/**
 * Supplies Firebase's component registrars to a hosted game, using the game's own
 * manifest.
 *
 * ## The failure this fixes
 *
 * The host runs the game under its own package name, so the game's
 * `FirebaseInitProvider` and `ComponentDiscoveryService` -- both declared in the *game's*
 * manifest -- never start here. Firebase's discovery then finds nothing and the game
 * aborts during startup:
 *
 * ```
 * W ComponentDiscovery: Application info not found.
 * W ComponentDiscovery: Could not retrieve metadata, returning empty list of registrars.
 * F JNI DETECTED ERROR: NewGlobalRef called with pending exception
 *   java.lang.NullPointerException: Firebase Messaging component is not present
 *   native #17 firebase::messaging::Initialize+1148   (libFirebaseCppApp-13_12_0.so)
 * ```
 *
 * ## The exact failing line, from the game's dex
 *
 * `MetadataRegistrarNameRetriever.getMetadata(Context)` disassembles to:
 *
 * ```
 * invoke-virtual {v6}, Context;->getPackageManager:()Landroid/content/pm/PackageManager;
 * new-instance v3, Landroid/content/ComponentName;
 * invoke-direct  {v3, v6, v4}, ComponentName;-><init>:(Context;Class;)V
 * const/16 v6, #int 128                       // PackageManager.GET_META_DATA
 * invoke-virtual {v2, v3, v6}, PackageManager;->getServiceInfo:(ComponentName;I)ServiceInfo;
 * if (null) { Log.w(..., "<name> has no service info.");
 *              Log.w(..., "Application info not found."); return null; }
 * ```
 *
 * `ComponentName(Context, Class)` derives its package from the Context, and the Context
 * handed to this code path is not the one [GameResourceContext] wrapped, so the component
 * names this APK rather than the game. `getServiceInfo` then returns null and discovery
 * gives up.
 *
 * Note what is *not* the problem: the host can query that ServiceInfo fine when the
 * package is passed explicitly. Measured on device, before writing this:
 *
 * ```
 * getServiceInfo(ComponentName("com.TechTreeGames.TheTower",
 *                              "com.google.firebase.components.ComponentDiscoveryService"),
 *                GET_META_DATA).metaData.size() == 21
 * ```
 *
 * So the metadata is reachable and only the Context-derived component name is wrong. That
 * is what makes a hook at `retrieve` sufficient, and why nothing here declares a service
 * or reimplements Firebase discovery.
 *
 * ## Why this belongs to the launcher
 *
 * Same rule as [GameResourceContext]: the host re-homed the game, which broke a lookup
 * the game would otherwise win. The host reads the game's own declared metadata back on
 * the game's behalf. It does not invent values, and it does not need to know which game
 * is running -- only the package it is currently hosting.
 *
 * ## Deliberately narrow
 *
 * The hook replaces the result **only when the original returned an empty list**. A game
 * whose discovery works, or that reports a real error, is left alone; this cannot mask a
 * genuine Firebase problem. A game that does not use this Firebase class is never touched.
 *
 * Version sensitivity is limited but real: the target is the
 * `MetadataRegistrarNameRetriever.retrieve(Context)` seam of this Firebase generation.
 * If a future Firebase drops it, [install] logs and returns rather than failing the game.
 *
 * @see PackageManagerHooks for the same before/after-call shape
 * @see GameResourceContext for the identity half of hosting
 */
object FirebaseComponentDiscoveryFix {

    private const val TAG = "FirebaseComponents"

    private const val DISCOVERY_SERVICE =
        "com.google.firebase.components.ComponentDiscoveryService"

    private const val DISCOVERY_CLASS =
        "com.google.firebase.components.ComponentDiscovery\$MetadataRegistrarNameRetriever"

    private const val RETRIEVE = "retrieve"

    /** Manifest key every Firebase component registrar is declared under. */
    private const val REGISTRAR_PREFIX = "com.google.firebase.components:"

    /** The value a registrar meta-data entry must carry to be a registrar. */
    private const val REGISTRAR_MARKER = "com.google.firebase.components.ComponentRegistrar"

    @Volatile
    var isInstalled = false
        private set

    /**
     * Hook Firebase's registrar-name lookup and FirebaseOptions.fromResource so an empty
     * or failing result is filled in from the game's own manifest and resources.
     *
     * @param gameClassLoader loader that can see the game's classes
     * @param gamePackage the game being hosted; the package the metadata belongs to
     * @param gameContext package context for the target game
     */
    fun install(gameClassLoader: ClassLoader, gamePackage: String, gameContext: Context? = null) {
        if (isInstalled) {
            BepInExLog.d("Firebase component discovery fix already installed")
            return
        }

        hookComponentDiscovery(gameClassLoader, gamePackage)
        hookFirebaseOptions(gameClassLoader, gamePackage, gameContext)
        observeDatabaseGetInstance(gameClassLoader)
        isInstalled = true
    }

    /**
     * Observe the Java FirebaseDatabase.getInstance call that the C++ Database
     * init depends on. Logs whether it returns an instance or throws, without
     * changing its outcome. Silent unless the game actually reaches it.
     */
    private fun observeDatabaseGetInstance(gameClassLoader: ClassLoader) {
        val dbClass = try {
            gameClassLoader.loadClass("com.google.firebase.database.FirebaseDatabase")
        } catch (_: Throwable) {
            return
        }
        for (m in dbClass.declaredMethods) {
            if (m.name != "getInstance") continue
            try {
                m.isAccessible = true
                Pine.hook(m, object : MethodHook() {
                    override fun afterCall(callFrame: Pine.CallFrame) {
                        try {
                            val t = if (callFrame.hasThrowable()) callFrame.throwable else null
                            BepInExLog.i(
                                "FirebaseDatabase.getInstance -> " +
                                    "result=${if (callFrame.result != null) "OK" else "null"} " +
                                    "throwable=${t?.javaClass?.simpleName}:${t?.message?.take(160)}"
                            )
                        } catch (_: Exception) {
                        }
                    }
                })
            } catch (e: Exception) {
                BepInExLog.e("Failed to observe FirebaseDatabase.getInstance", e)
            }
        }
    }

    private fun hookComponentDiscovery(gameClassLoader: ClassLoader, gamePackage: String) {
        val retrieverClass = try {
            gameClassLoader.loadClass(DISCOVERY_CLASS)
        } catch (e: ClassNotFoundException) {
            // Game does not use this Firebase generation. Not an error.
            BepInExLog.i("No $DISCOVERY_CLASS in $gamePackage; discovery fix not needed")
            return
        } catch (e: Exception) {
            BepInExLog.e("Failed to load $DISCOVERY_CLASS", e)
            return
        }

        val retrieve = retrieverClass.declaredMethods.firstOrNull { method ->
            method.name == RETRIEVE &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Context::class.java
        }
        if (retrieve == null) {
            BepInExLog.w("$DISCOVERY_CLASS.$RETRIEVE(Context) not found; " +
                "discovery fix not installed")
            return
        }

        try {
            retrieve.isAccessible = true
            Pine.hook(retrieve, object : MethodHook() {
                override fun afterCall(callFrame: Pine.CallFrame) {
                    try {
                        // Only ever fill in a result that came back empty. Anything else
                        // is a real answer and is left untouched.
                        val original = callFrame.result as? Collection<*>
                        if (original != null && original.isNotEmpty()) return
                        if (callFrame.hasThrowable() && callFrame.throwable != null) return

                        val names = readRegistrarNames(gamePackage) ?: return
                        if (names.isEmpty()) {
                            BepInExLog.w(
                                "Firebase discovery: $gamePackage declares no registrars"
                            )
                            return
                        }

                        callFrame.result = ArrayList(names)
                        BepInExLog.i(
                            "Firebase discovery: supplied ${names.size} registrar(s) from " +
                                "$gamePackage (host cannot run its discovery service)"
                        )
                    } catch (e: Exception) {
                        // A rescue hook must never become the reason the game dies.
                        BepInExLog.e("Firebase discovery fix failed", e)
                    }
                }
            })
            BepInExLog.i("Firebase component discovery fix installed on $DISCOVERY_CLASS.$RETRIEVE")
        } catch (e: Exception) {
            BepInExLog.e("Failed to hook $DISCOVERY_CLASS.$RETRIEVE", e)
        }
    }

    private fun hookFirebaseOptions(
        gameClassLoader: ClassLoader,
        gamePackage: String,
        gameContext: Context?
    ) {
        val optionsClass = try {
            gameClassLoader.loadClass("com.google.firebase.FirebaseOptions")
        } catch (_: ClassNotFoundException) {
            return
        } catch (e: Exception) {
            BepInExLog.e("Failed to load com.google.firebase.FirebaseOptions", e)
            return
        }

        val fromResource = optionsClass.declaredMethods.firstOrNull { method ->
            method.name == "fromResource" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Context::class.java
        } ?: return

        try {
            fromResource.isAccessible = true
            Pine.hook(fromResource, object : MethodHook() {
                override fun afterCall(callFrame: Pine.CallFrame) {
                    try {
                        val original = callFrame.result
                        val hadThrowable = callFrame.hasThrowable() && callFrame.throwable != null
                        if (original != null && !hadThrowable) {
                            return
                        }

                        if (hadThrowable) {
                            callFrame.throwable = null
                        }

                        val res = gameContext?.resources
                            ?: (callFrame.args?.firstOrNull() as? Context)?.resources

                        fun readString(name: String): String? {
                            if (res == null) return null
                            var id = res.getIdentifier(name, "string", gamePackage)
                            if (id == 0) {
                                id = res.getIdentifier(name, "string", null)
                            }
                            return if (id != 0) try { res.getString(id) } catch (_: Throwable) { null } else null
                        }

                        val appId = readString("google_app_id")
                            ?: return

                        val apiKey = readString("google_api_key")
                            ?: return

                        val dbUrl = readString("firebase_database_url")
                        val gaTrackingId = readString("ga_trackingId")
                        val gcmSenderId = readString("gcm_defaultSenderId")

                        val storageBucket = readString("google_storage_bucket")

                        val projectId = readString("project_id")

                        // 1. Try 7-arg constructor
                        val constructor = optionsClass.declaredConstructors.firstOrNull { c ->
                            c.parameterTypes.size == 7 &&
                                c.parameterTypes.all { it == String::class.java }
                        }

                        if (constructor != null) {
                            constructor.isAccessible = true
                            val instance = constructor.newInstance(
                                appId, apiKey, dbUrl, gaTrackingId, gcmSenderId, storageBucket, projectId
                            )
                            callFrame.result = instance
                            BepInExLog.i("FirebaseOptions: rescued options from $gamePackage resources via constructor")
                            return
                        }

                        // 2. Fallback to Builder
                        val builderClass = gameClassLoader.loadClass("com.google.firebase.FirebaseOptions\$Builder")
                        val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                        builderClass.getMethod("setApplicationId", String::class.java).invoke(builder, appId)
                        builderClass.getMethod("setApiKey", String::class.java).invoke(builder, apiKey)
                        if (dbUrl != null) builderClass.getMethod("setDatabaseUrl", String::class.java).invoke(builder, dbUrl)
                        if (gaTrackingId != null) builderClass.getMethod("setGaTrackingId", String::class.java).invoke(builder, gaTrackingId)
                        if (gcmSenderId != null) builderClass.getMethod("setGcmSenderId", String::class.java).invoke(builder, gcmSenderId)
                        if (storageBucket != null) builderClass.getMethod("setStorageBucket", String::class.java).invoke(builder, storageBucket)
                        if (projectId != null) builderClass.getMethod("setProjectId", String::class.java).invoke(builder, projectId)

                        val built = builderClass.getMethod("build").invoke(builder)
                        callFrame.result = built
                        BepInExLog.i("FirebaseOptions: rescued options from $gamePackage resources via Builder")
                    } catch (e: Throwable) {
                        BepInExLog.e("FirebaseOptions rescue failed", e)
                    }
                }
            })
            BepInExLog.i("FirebaseOptions.fromResource hook installed for $gamePackage")
        } catch (e: Exception) {
            BepInExLog.e("Failed to hook FirebaseOptions.fromResource", e)
        }
    }

    /**
     * Read the registrar names the game declares on its own discovery service.
     *
     * The package is passed explicitly rather than taken from a Context, which is the
     * whole point: [android.content.ComponentName]'s Context constructor is what this
     * hook exists to bypass.
     *
     * @return the registrar class names, or null if the query could not be answered
     */
    private fun readRegistrarNames(gamePackage: String): List<String>? {
        val pm = try {
            contextRef?.packageManager
        } catch (e: Exception) {
            BepInExLog.e("Firebase discovery: PackageManager unavailable", e)
            null
        } ?: return null

        val metaData: Bundle = try {
            val info = pm.getServiceInfo(
                ComponentName(gamePackage, DISCOVERY_SERVICE),
                PackageManager.GET_META_DATA
            )
            info.metaData
        } catch (e: Exception) {
            // NameNotFoundException, or the service simply is not declared.
            BepInExLog.w("Firebase discovery: no $DISCOVERY_SERVICE in $gamePackage")
            null
        } ?: return null

        val names = ArrayList<String>()
        for (key in metaData.keySet()) {
            if (!key.startsWith(REGISTRAR_PREFIX)) continue
            // Match Firebase: only entries whose value is the registrar marker count.
            // getString() is equivalent to get() here (the marker is a String;
            // non-String values yield null and are skipped either way) and avoids
            // the API 33-deprecated Bundle.get().
            val value = try {
                metaData.getString(key)
            } catch (e: Exception) {
                null
            }
            if (REGISTRAR_MARKER != value) continue
            names.add(key.substring(REGISTRAR_PREFIX.length))
        }
        names.sort()
        return names
    }

    /**
     * A Context used only to obtain the PackageManager. The host's own context is fine
     * here: this code passes the package explicitly, so nothing depends on the Context's
     * identity.
     */
    @Volatile
    private var contextRef: Context? = null

    fun setPackageManagerSource(context: Context) {
        contextRef = context.applicationContext ?: context
    }
}