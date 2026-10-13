package com.bepinex.android.fusion

import android.content.Context
import com.bepinex.android.BepInExLog
import dalvik.system.DexFile
import java.io.File
import java.util.zip.ZipFile

/**
 * Serves Firebase's runtime-extracted bridge classes to any class loader.
 *
 * Firebase C++ embeds `*_resources_lib.jar` files (e.g.
 * `database_resources_lib.jar` with `com.google.firebase.database.internal.cpp.*`)
 * inside its native libraries and extracts them into the app cache directory
 * during init. Its own lookup of those classes is unreliable in a hosted
 * process (the Android path of `FindClassInFiles` hands JNI-slashed names to
 * `loadClass`), so a `NoClassDefFoundError`/`ClassNotFoundException` for a
 * class that IS on disk aborts the game — observed as a SIGSEGV in
 * `firebase::database::Database::GetInstance` via `DeleteInternal`.
 *
 * This loader defines the requested class directly into the game loader from
 * the extracted jar. Once defined, every later lookup hits it via
 * findLoadedClass: the cached loaders Firebase iterates, parent delegation,
 * and game code. Only consulted after both the game and launcher loaders have
 * failed, so normal resolution order is unchanged.
 */
object FirebaseBridgeLoader {

    @Volatile
    private var cacheDir: File? = null

    /** The game's loader: classes defined here are visible to every Firebase
     *  lookup path (cached loaders, parent delegation, game code). */
    @Volatile
    private var gameLoader: ClassLoader? = null

    /** The hosted game's package, for enumerating its APK dex files. */
    @Volatile
    private var gamePackage: String? = null

    /** Application context for package queries. */
    @Volatile
    private var hostContext: Context? = null

    /** Re-entrancy guard: the set of class names currently being resolved on
     *  this thread. A nested lookup for the SAME name returns null at once
     *  (cuts infinite ping-pong); different names proceed, so a supertype
     *  rescue inside a bridge call still works. */
    private val resolving = ThreadLocal.withInitial { HashSet<String>() }

    /** Open jar files with their snapshot key, so a rewrite is noticed. */
    private val openDex = HashMap<String, Pair<String, DexFile>>()

    fun install(context: Context, gameClassLoader: ClassLoader, hostedPackage: String) {
        cacheDir = context.cacheDir
        gameLoader = gameClassLoader
        hostContext = context.applicationContext ?: context
        gamePackage = hostedPackage
    }

    /**
     * Eager warmup entry point. Call BEFORE installing the loadClass hook:
     * the thousands of warmup resolutions then run unhooked, keeping ART
     * pressure (and Pine bridge traffic) off the hooked path. Safe to call
     * any time; runs at most once per process.
     */
    fun prime() {
        try {
            warmGameClasses()
        } catch (_: Throwable) {
        }
    }

    /**
     * Load [name] (binary/dotted form) from the extracted Firebase jars.
     * Returns null when the jars are not staged yet or do not contain it.
     *
     * This runs for lookups on ANY loader — including Firebase's own private
     * DexClassLoaders, for which the hook's directed fallbacks never apply.
     * The game and launcher loaders are therefore tried here first: a
     * superclass living in game dex (e.g. ChildEventListener for
     * CppChildEventListener) must resolve even when the failing frame belongs
     * to a jar loader that cannot see it.
     */
    fun loadClass(name: String): Class<*>? {
        val cache = cacheDir ?: return null
        val target = gameLoader
        if (!name.startsWith("com.google.firebase.")) return null
        val inFlight = resolving.get()
        if (!inFlight.add(name)) return null
        return try {
            warmGameClasses()
            if (target != null) {
                try {
                    return target.loadClass(name)
                } catch (_: ClassNotFoundException) {
                }
            }
            try {
                javaClass.classLoader?.loadClass(name)?.let { return it }
            } catch (_: ClassNotFoundException) {
            } catch (_: Throwable) {
            }
            if (target != null) {
                defineInGameLoader(cache, target, name)
            } else {
                null
            }
        } catch (e: Throwable) {
            BepInExLog.w("FirebaseBridgeLoader: failed to load $name: ${e.message}")
            null
        } finally {
            inFlight.remove(name)
        }
    }

    /** Warmed once per process: define every Firebase class found in the
     *  hosted game's APK dex files into the game loader. ART-internal
     *  resolutions (superclasses, interfaces, verification) bypass loadClass
     *  hooks, so the only way to make a game-dex supertype visible to them is
     *  for it to be already defined. Best-effort and silent: any failure
     *  leaves on-demand resolution untouched. */
    @Volatile
    private var warmed = false

    private fun warmGameClasses() {
        if (warmed) return
        synchronized(this) {
            if (warmed) return
            warmed = true
        }
        try {
            val ctx = hostContext ?: return
            val pkg = gamePackage ?: return
            val loader = gameLoader ?: return
            val appInfo = try {
                ctx.packageManager.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                return
            }
            val apks = ArrayList<String>()
            if (appInfo.sourceDir != null) apks.add(appInfo.sourceDir)
            val splits = appInfo.splitSourceDirs
            if (splits != null) {
                for (s in splits) apks.add(s)
            }
            BepInExLog.i("FirebaseBridgeLoader: warmup scanning ${apks.size} apks for $pkg")
            var count = 0
            for (apk in apks) {
                try {
                    count += warmApk(apk, loader)
                } catch (_: Throwable) {
                }
            }
            BepInExLog.i("FirebaseBridgeLoader: warmed $count game Firebase classes")
        } catch (e: Throwable) {
            BepInExLog.w("FirebaseBridgeLoader: warmup failed: ${e.message}")
        }
    }

    /** Define all com.google.firebase.* classes in [apkPath]'s dex files.
     *  Each classesN.dex is extracted to a temp file and enumerated through
     *  ART itself (no custom dex parsing); every Firebase entry is then
     *  loaded through the game loader's normal path, which defines it there.
     *  @return how many were defined. */
    private fun warmApk(apkPath: String, loader: ClassLoader): Int {
        var count = 0
        val scanDir = File(cacheDir, "dexscan").apply { mkdirs() }
        try {
            ZipFile(apkPath).use { zip ->
                val dexEntries = java.util.Collections.list(zip.entries())
                    .filter { e ->
                        e.name == "classes.dex" ||
                            (e.name.startsWith("classes") && e.name.endsWith(".dex"))
                    }
                for (entry in dexEntries) {
                    var tmp: File? = null
                    var dx: DexFile? = null
                    try {
                        tmp = File.createTempFile("scan", ".dex", scanDir)
                        zip.getInputStream(entry).use { ins ->
                            tmp.outputStream().use { out -> ins.copyTo(out) }
                        }
                        // ART refuses writable dex files; the temp copy must
                        // be read-only before opening (same rule as the
                        // Firebase jars themselves).
                        tmp.setReadOnly()
                        dx = DexFile.loadDex(tmp.absolutePath, null, 0)
                        for (name in java.util.Collections.list(dx.entries())) {
                            if (!name.startsWith("com.google.firebase.")) continue
                            try {
                                loader.loadClass(name)
                                count++
                            } catch (_: Throwable) {
                            }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        try {
                            dx?.close()
                        } catch (_: Exception) {
                        }
                        try {
                            tmp?.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return count
    }

    private fun liveJars(cache: File): List<File> =
        cache.listFiles { f ->
            f.isFile && f.name.endsWith("_resources_lib.jar")
        }?.sortedBy { it.name } ?: emptyList()

    private fun liveKey(jar: File): String =
        jar.name + ":" + jar.length() + ":" + jar.lastModified()

    /**
     * Define [name] into the game loader from whichever extracted jar holds
     * it. The whole jar is defined, not just the requested class: bridge
     * classes reference each other (superclasses like CppChildEventListener
     * extends CppEventListener, anonymous inners like JniAuthStateListener$1),
     * and those resolutions bypass loadClass hooks, so defining one class
     * would only move the NoClassDefFoundError to the next. Once defined,
     * every later lookup hits via findLoadedClass.
     *
     * Jars whose bytes fail the zip CRC are skipped: Firebase rewrites these
     * files during init with no locking, and a torn read must not be adopted
     * — the live file heals on the next write and the caller retries.
     */
    private fun defineInGameLoader(cache: File, target: ClassLoader, name: String): Class<*>? {
        val simpleName = name.substringAfterLast('.')
        for (jar in liveJars(cache)) {
            try {
                if (!jarContains(jar, simpleName)) continue
                defineJar(jar, target)
                try {
                    return target.loadClass(name)
                } catch (_: ClassNotFoundException) {
                }
            } catch (e: Throwable) {
                BepInExLog.w("FirebaseBridgeLoader: define failed for $name")
            }
        }
        return null
    }

    /** Define every Firebase class in [jar] into the game loader. Passes
     *  repeat so children whose superclass lives in the same jar resolve
     *  later; missing supertypes are preloaded first (see [noteFailures]).
     *  Individually failing classes are skipped, never fatal. */
    private fun defineJar(jar: File, target: ClassLoader) {
        val key = liveKey(jar)
        val dex = synchronized(this) {
            val cached = openDex[jar.absolutePath]
            if (cached != null && cached.first == key) {
                cached.second
            } else {
                try {
                    cached?.second?.close()
                } catch (_: Exception) {
                }
                val fresh = DexFile.loadDex(jar.absolutePath, null, 0)
                openDex[jar.absolutePath] = key to fresh
                fresh
            }
        }
        val m = DexFile::class.java.getMethod(
            "loadClassBinaryName",
            String::class.java,
            ClassLoader::class.java,
            List::class.java
        )
        val entries = try {
            java.util.Collections.list(dex.entries())
        } catch (_: Exception) {
            return
        }
        var defined = 0
        repeat(4) {
            for (entry in entries) {
                if (!entry.startsWith("com.google.firebase.")) continue
                try {
                    val suppressed = ArrayList<Throwable>()
                    if (m.invoke(dex, entry.replace('.', '/'), target, suppressed) != null) {
                        defined++
                    } else {
                        noteFailures(target, suppressed, null)
                    }
                } catch (e: Throwable) {
                    // Missing supertype or sibling not yet defined; preload
                    // whatever the failure names so the next pass retries
                    // with it present.
                    noteFailures(target, emptyList(), e)
                }
            }
        }
        BepInExLog.i("FirebaseBridgeLoader: defined $defined classes from " + jar.name)
    }

    /** Collect a failure and its causes into [out]. */
    private fun collectFailures(t: Throwable, out: ArrayList<Throwable>) {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 6) {
            out.add(c)
            for (s in c.suppressed) {
                if (s !== t) out.add(s)
            }
            c = c.cause
            depth++
        }
    }

    private fun noteFailures(target: ClassLoader, suppressed: List<Throwable>, thrown: Throwable?) {
        val all = ArrayList<Throwable>(suppressed.size + 2)
        all.addAll(suppressed)
        if (thrown != null) collectFailures(thrown, all)
        ensureSupertypes(target, all)
    }

    /**
     * A define failed with linkage errors in [suppressed]. Load every
     * "Failed resolution of: L...;" type named by the failure (or its causes)
     * directly through the game loader so the retry finds it via
     * findLoadedClass. ART-internal resolutions bypass loadClass hooks, so an
     * explicit load is the only way to make a game-dex supertype visible to a
     * jar-defined child.
     */
    private fun ensureSupertypes(target: ClassLoader, suppressed: List<Throwable>) {
        for (t in suppressed) {
            val msg = t.message ?: continue
            if (t !is NoClassDefFoundError || !msg.startsWith("Failed resolution of: L")) continue
            val descriptor = msg.substringAfter("Failed resolution of: ").substringBefore(";")
            val dotted = descriptor.trimStart('L').replace('/', '.')
            if (dotted.isEmpty() || dotted.startsWith("java.")) continue
            try {
                target.loadClass(dotted)
                BepInExLog.d("FirebaseBridgeLoader: preloaded supertype " + dotted)
            } catch (_: Throwable) {
            }
        }
    }

    /** Cheap prefilter: does this jar's classes.dex contain the simple name?
     *  Also validates the zip CRC so torn reads are skipped. */
    private fun jarContains(jar: File, simpleName: String): Boolean {
        return try {
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry("classes.dex") ?: return false
                if (entry.size <= 0 || entry.size > 8 * 1024 * 1024 || entry.crc < 0) return false
                val bytes = zip.getInputStream(entry).readBytes()
                if (bytes.size.toLong() != entry.size) return false
                val crc = java.util.zip.CRC32()
                crc.update(bytes)
                if (crc.value != entry.crc) return false
                val needle = simpleName.toByteArray(Charsets.US_ASCII)
                outer@ for (i in 0..bytes.size - needle.size) {
                    for (j in needle.indices) {
                        if (bytes[i + j] != needle[j]) continue@outer
                    }
                    return true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Minimal dex header check: magic plus a sane class-def count. */
    private fun isDex(bytes: ByteArray): Boolean {
        if (bytes.size < 100) return false
        if (bytes[0].toInt() != 0x64 || bytes[1].toInt() != 0x65 ||
            bytes[2].toInt() != 0x78 || bytes[3].toInt() != 0x0A
        ) {
            return false
        }
        val classDefs = readLe32(bytes, 96)
        return classDefs in 1..65536
    }

    private fun readLe32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)
}
// touchcheck
