package com.bepinex.android.fusion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import com.bepinex.android.BepInExLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Re-points a hosted Activity's identity at the game's own APK while ensuring storage
 * resolves to the launcher's sandbox.
 *
 * ## Why this exists
 *
 * The launcher hosts the game's Activity **inside its own process and under its own
 * package name** (`com.bepinex.android`), so `Activity.getPackageName()` and
 * `Activity.getResources()` naturally answer for *this* APK. That is correct for us and
 * wrong for the game.
 *
 * A game's SDKs routinely resolve their configuration through string resources keyed by
 * the Context's package name. `FirebaseOptions`' resource reader is the canonical case:
 *
 * ```java
 * int id = context.getResources().getIdentifier("google_app_id", "string", context.getPackageName());
 * ```
 *
 * Both halves must agree. Asked under `com.bepinex.android`, the lookup returns 0 --
 * not because the value is absent from the device, but because it lives in a *different*
 * APK's resource table than the one being queried. The game does ship these values; see
 * `docs/thetower-6000.md`, where The Tower's `base.apk` is shown to carry all six
 * (`google_app_id`, `google_api_key`, `gcm_defaultSenderId`, `project_id`,
 * `google_storage_bucket`, `google_crash_reporting_api_key`) while the launcher's own
 * `base.apk` carries none of them.
 *
 * Firebase does not merely log this. `FirebaseApp.CreateAndTrack` throws
 * `InitializationException: Firebase app creation failed`, and The Tower calls
 * `Crashlytics.Log` from `GameInitializer.Start` -> so the exception unwinds the game's
 * own async startup and the splash screen sits at `Loading... 0%` forever. No crash, no
 * tombstone, process alive: an `await` that never resumes.
 *
 * ## Storage Redirection (Candidate A)
 *
 * Identity (resources, assets, package name, classloader, ApplicationInfo metadata) is
 * routed to the game's APK, but storage paths (filesDir, cacheDir, SharedPreferences, etc.)
 * MUST resolve to the launcher's writable sandbox ([storageContext]).
 * Without explicit storage redirection, `getApplicationContext().getFilesDir()` returns
 * `/data/user/0/<game_package>/files`, which the launcher process has no permission to
 * access or create, triggering `CrossProcessLock: mkdir failed: ENOENT` and hanging startup.
 *
 * @see GameContextWrapper for the UnityPlayer constructor wrapper counterpart
 * @see UnityPlayerHooks for where the wrapped Context is consumed
 */
class GameResourceContext(
    /** The Activity's original base context. Retained so nothing else changes behaviour. */
    base: Context,
    /**
     * The game's own package Context, from
     * `createPackageContext(gamePackage, CONTEXT_INCLUDE_CODE)`. Its Resources, package
     * name, assets and class loader are the game's.
     */
    private val gameContext: Context,
    /** The launcher's own Context; supplies the writable storage paths. */
    storage: Context,
    /** True if this instance represents the Application-level Context. */
    private val isApplicationContext: Boolean = false
) : ContextWrapper(base) {

    private val storageContext: Context = unwrapToRootContext(storage)

    companion object {
        /**
         * Unwraps a Context hierarchy down to its root Context (typically ContextImpl).
         * This prevents infinite recursion when delegating storage calls if an Application
         * or Activity wraps itself.
         */
        fun unwrapToRootContext(context: Context): Context {
            var cur = context
            var depth = 0
            while (cur is ContextWrapper && depth < 10) {
                val next = cur.baseContext ?: break
                if (next === cur) break
                cur = next
                depth++
            }
            return cur
        }

        /**
         * Wraps the process [Application]'s underlying `mBase` context with a [GameResourceContext].
         *
         * ## Why
         * Libraries like Firebase (`FirebaseApp.initializeAllApis`) cast `context.getApplicationContext()`
         * directly to `android.app.Application` to register [android.app.Application.ActivityLifecycleCallbacks]:
         * ```java
         * ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(...)
         * ```
         * If `getApplicationContext()` returns a custom [ContextWrapper], this immediately throws
         * `ClassCastException`.
         *
         * By reflectively replacing `Application.mBase` with a [GameResourceContext] that points to the
         * root `ContextImpl` for storage and the game's Context for identity (resources, package name,
         * class loader, ApplicationInfo), the `Application` instance remains a true [Application] satisfying
         * the cast and callback registration, while all resource lookups (e.g. `google_app_id`) correctly
         * resolve to the game's APK.
         */
        @JvmStatic
        fun wrapApplication(app: android.app.Application, gameContext: Context): Boolean {
            try {
                val mBaseField = ContextWrapper::class.java.getDeclaredField("mBase").apply {
                    isAccessible = true
                }
                val originalBase = mBaseField.get(app) as? Context ?: return false
                if (originalBase is GameResourceContext) {
                    return true // Already wrapped
                }
                val rootStorage = unwrapToRootContext(originalBase)
                val wrapped = GameResourceContext(
                    base = originalBase,
                    gameContext = gameContext,
                    storage = rootStorage,
                    isApplicationContext = true
                )
                mBaseField.set(app, wrapped)
                BepInExLog.i("GameResourceContext: Successfully wrapped Application.mBase for ${gameContext.packageName}")
                return true
            } catch (e: Throwable) {
                BepInExLog.e("GameResourceContext: Failed to wrap Application.mBase", e)
                return false
            }
        }
    }

    override fun getResources(): Resources = gameContext.resources

    /**
     * Re-points identity to the game package for game code and Unity, but defaults to the launcher's
     * own package name for all system, framework, and GMS IPC callers (including obfuscated Dynamite modules).
     *
     * ## Why Whitelist
     * System services validate the calling package against the process UID (which is the launcher's).
     * GMS Dynamite modules use Proguard-obfuscated packages (e.g. `m7.es.m`) that defeat blacklists.
     * If a foreign game package is sent across Binder IPC, GMS Core throws:
     * `SecurityException: Unknown calling package name 'com.TechTreeGames.TheTower'`
     * and kills the app with SIGKILL.
     * By whitelisting only Unity (`com.unity3d.*`) and the game's own package, all framework and
     * GMS callers safely receive the launcher's package name matching the UID.
     */
    override fun getPackageName(): String {
        val stack = Thread.currentThread().stackTrace
        val gamePkg = gameContext.packageName
        for (i in 2 until minOf(stack.size, 15)) {
            val className = stack[i].className
            if (className.startsWith("com.unity3d.") ||
                className.startsWith(gamePkg)) {
                return gamePkg
            }
        }
        return storageContext.packageName
    }

    /**
     * AppOps and IPC attribution (like Google Play Services Measurement/Dynamite) check
     * that [getOpPackageName] matches the calling process UID. Returning the foreign game
     * package here causes GMS Core to throw:
     * `SecurityException: Unknown calling package name 'com.TechTreeGames.TheTower'`
     * because the process UID is the launcher's. Therefore, [getOpPackageName] MUST return
     * the launcher's own package name ([storageContext.opPackageName]).
     */
    override fun getOpPackageName(): String = storageContext.opPackageName

    override fun getAssets(): AssetManager = gameContext.assets

    /**
     * Without this the game's own code keeps resolving against the launcher's loader,
     * which does not contain the game's classes.
     */
    override fun getClassLoader(): ClassLoader = gameContext.classLoader

    // --- Storage redirection: always point to launcher sandbox ---

    override fun getFilesDir(): File = storageContext.filesDir

    override fun getCacheDir(): File = storageContext.cacheDir

    override fun getCodeCacheDir(): File = storageContext.codeCacheDir

    override fun getDataDir(): File = storageContext.dataDir

    override fun getNoBackupFilesDir(): File = storageContext.noBackupFilesDir

    override fun getDir(name: String?, mode: Int): File = storageContext.getDir(name, mode)

    override fun getDatabasePath(name: String?): File = storageContext.getDatabasePath(name)

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        storageContext.getSharedPreferences(name, mode)

    override fun deleteSharedPreferences(name: String?): Boolean =
        storageContext.deleteSharedPreferences(name)

    override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?): Boolean =
        storageContext.moveSharedPreferencesFrom(sourceContext, name)

    override fun getExternalFilesDir(type: String?): File? =
        storageContext.getExternalFilesDir(type)

    override fun getExternalFilesDirs(type: String?): Array<File> =
        storageContext.getExternalFilesDirs(type)

    override fun getExternalCacheDir(): File? = storageContext.externalCacheDir

    override fun getExternalCacheDirs(): Array<File> = storageContext.externalCacheDirs

    override fun getObbDir(): File? = storageContext.obbDir

    override fun getObbDirs(): Array<File> = storageContext.obbDirs

    override fun openFileInput(name: String?): FileInputStream =
        storageContext.openFileInput(name)

    override fun openFileOutput(name: String?, mode: Int): FileOutputStream =
        storageContext.openFileOutput(name, mode)

    override fun deleteFile(name: String?): Boolean =
        storageContext.deleteFile(name)

    override fun fileList(): Array<String> =
        storageContext.fileList()

    /**
     * Returns the process [Application] instance.
     *
     * Callers like `FirebaseApp.initializeAllApis` cast `getApplicationContext()` directly
     * to `android.app.Application` to register lifecycle callbacks. If this method returns
     * a custom [ContextWrapper], it crashes with `ClassCastException`.
     *
     * The returned [Application] has its `mBase` reflectively wrapped with [GameResourceContext]
     * via [wrapApplication], so calls on the Application instance itself (e.g. `getResources()`,
     * `getPackageName()`, `getFilesDir()`) route through this wrapper while preserving the
     * `Application` identity and lifecycle callbacks.
     */
    override fun getApplicationContext(): Context {
        val app = (storageContext.applicationContext ?: baseContext?.applicationContext) as? android.app.Application
        if (app != null) {
            wrapApplication(app, gameContext)
            return app
        }
        return super.getApplicationContext()
    }

    /**
     * The game's [ApplicationInfo], with the two fields the host deliberately rewrites
     * left blank.
     *
     * `metaData` is what makes this necessary. Firebase discovers its components from
     * the manifest key `com.google.firebase.components`, read off the ApplicationInfo of
     * the Context's own package. With the launcher's info returned instead, that key is
     * absent, `ComponentDiscovery` logs `Application info not found.` and returns an
     * empty registrar list, so `FirebaseMessagingRegistrar` never runs. The symptom
     * moves rather than disappears: `FirebaseMessaging.getInstance()` then throws
     * "Firebase Messaging component is not present", which is a JNI error here and
     * aborts the process from inside `firebase::messaging::Initialize`.
     *
     * Measured on device, in sequence, both caused by the same missing metadata:
     * resource lookup fails -> `InitializationException` (silent 0% hang), then after
     * fixing that, registrar discovery fails -> FCM abort. Fixing only one leaves the
     * other.
     *
     * The copy is defensive: [ApplicationInfo] instances are shared mutable objects owned
     * by the system, so mutating the one returned by `gameContext` would corrupt it for
     * every other holder in the process.
     *
     * `nativeLibraryDir` is intentionally blanked. Leaving it pointed at the game's
     * `lib/arm64` would let Unity's loader find the game's own `libmain.so`, defeating
     * the redirection in [NativeLibraryManager] that makes hosting possible at all.
     * `dataDir` stays the launcher's so the game still writes into our sandbox.
     */
    private val gameApplicationInfo: ApplicationInfo? by lazy {
        val packageName = try {
            gameContext.packageName
        } catch (e: Exception) {
            BepInExLog.e("GameResourceContext: game packageName unavailable", e)
            null
        } ?: return@lazy null

        // Fetched with GET_META_DATA rather than taken from gameContext.applicationInfo.
        // The plain `applicationInfo` accessor does not populate `metaData` -- the Bundle
        // is only filled in when the caller asks for it. Firebase reads
        // `com.google.firebase.components` out of exactly that Bundle, so reading it the
        // cheap way yields null and logs "Application info not found." while the game
        // manifest demonstrably declares all 43 registrars.
        val withMetaData = try {
            gameContext.packageManager
                .getPackageInfo(packageName, PackageManager.GET_META_DATA)
                .applicationInfo
        } catch (e: Exception) {
            BepInExLog.e("GameResourceContext: GET_META_DATA lookup failed for $packageName", e)
            null
        } ?: return@lazy null

        // Fetched with GET_META_DATA deliberately: the plain `applicationInfo` accessor does
        // not populate `metaData`. Note this Bundle does NOT contain Firebase's component
        // registrars -- verified on device, it holds 28 keys (unity.*, gms.*, applovin.*,
        // notch.config, ...) and none begin with "com.google.firebase.components". Those 21
        // hang off <service ComponentDiscoveryService>; see FirebaseComponentDiscoveryFix.
        BepInExLog.i(
            "GameResourceContext: game applicationInfo resolved for $packageName " +
                "(metaData keys=${withMetaData.metaData?.size() ?: 0})"
        )

        // Defensive copy: ApplicationInfo is a shared mutable object owned by the system,
        // so mutating the one the PackageManager returned would corrupt it for every other
        // holder in the process.
        @Suppress("DEPRECATION")
        ApplicationInfo(withMetaData).apply {
            // Intentionally blanked: leaving this pointed at the game's lib/arm64 would
            // let Unity's loader find the game's own libmain.so, defeating the
            // findLibrary redirection that makes hosting possible at all.
            nativeLibraryDir = ""
            // Repointed at our sandbox: the game's own dataDir does not exist inside this
            // app, so anything deriving a writable path from it fails.
            dataDir = storageContext.applicationInfo.dataDir
        }
    }

    override fun getApplicationInfo(): ApplicationInfo =
        gameApplicationInfo ?: super.getApplicationInfo()
}