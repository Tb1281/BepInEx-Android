/*
 * BepInEx.Android - libfusion.so core
 *
 * Coordinates the full injection pipeline:
 *   Config parse -> allocate injected code cave -> il2cpp_init hook -> CoreCLR -> BepInEx
 */

#include "fusion.h"
#include "fusion/src/trace_log.h"
#include <dlfcn.h>
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <dirent.h>
#include <fstream>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "Fusion"
#define LOGI(...) ::bepinex::log_tag(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) ::bepinex::log_tag(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) ::bepinex::log_tag(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Global config instance

FusionConfig g_config;
static std::string g_configPath;
static bool g_staged = false;

/* Captured in fusion_bootstrap_from_libmain while a JNIEnv is still valid, and
 * read later by dotnet.cpp. See fusion_get_java_vm(). */
static JavaVM *g_javaVM = nullptr;

// Forward declarations

/* libmain overrides (defined in main.cpp with extern "C" linkage) */
extern "C" {
    void libmain_set_override_unity_path(const char *path);
    void libmain_set_override_il2cpp_path(const char *path);
    const char *libmain_get_override_unity_path();
    const char *libmain_get_override_il2cpp_path();
}

/* Hooking */
bool il2cpp_initialize(const char *il2cppPath);
void il2cpp_install_init_hook(void *hookCallback);
void il2cpp_destroy_init_hook();
int il2cpp_init(char *domain_name);
void *il2cpp_get_handle();
uintptr_t il2cpp_get_library_base();
bool safehook_initialize(void *lib_handle, uintptr_t lib_base,
                         void *(*allocator)(void *, void *, size_t));

/* CoreCLR / BepInEx bootstrap */
bool dotnet_start_runtime();

// Config file parsing

static std::string read_file(const char *path)
{
    std::string result;
    FILE *f = fopen(path, "rb");
    if (!f) return result;

    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return {}; }
    const long size = ftell(f);
    if (size < 0 || fseek(f, 0, SEEK_SET) != 0) { fclose(f); return {}; }
    result.resize(static_cast<size_t>(size));
    const size_t read = result.empty() ? 0 : fread(result.data(), 1, result.size(), f);
    fclose(f);
    if (read != result.size()) return {};
    return result;
}

/*
 * Simple config parser. Expected format (one key=value per line):
 *
 *   gameLibraryDirectory=/data/app/.../lib/arm64
 *   appLibraryDirectory=/data/app/.../lib/arm64
 *   appDataDirectory=/data/data/<applicationId>/files/...
 *   bepInExDirectory=/storage/...
 *   dotnetDirectory=/storage/...
 *   unityDataDirectory=/storage/...
 *   unityVersion=2022.3.62f3
 *   useOriginalLibUnity=false
 *   gameUnityLibraryPath=<apk>!/lib/arm64-v8a/libunity.so
 */
static bool parse_fusion_config(const char *text, FusionConfig *out)
{
    if (!text || !out) return false;

    const char *p = text;
    while (*p) {
        /* Skip whitespace */
        while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
        if (!*p) break;

        /* Read key */
        std::string key;
        while (*p && *p != '=' && *p != '\n' && *p != '\r') key += *p++;
        if (*p != '=') continue; /* skip malformed lines */
        p++; /* skip = */

        /* Read value */
        std::string value;
        while (*p && *p != '\n' && *p != '\r') value += *p++;
        if (*p) p++; /* skip newline */

        /* Assign */
        if (key == "gameLibraryDirectory") out->gameLibraryDir = value;
        else if (key == "appLibraryDirectory") out->appLibraryDir = value;
        else if (key == "appDataDirectory") out->appDataDir = value;
        else if (key == "bepInExDirectory") out->bepInExDir = value;
        else if (key == "dotnetDirectory") out->dotnetDir = value;
        else if (key == "unityDataDirectory") out->unityDataDir = value;
        else if (key == "unityVersion") out->unityVersion = value;
        else if (key == "useOriginalLibUnity") out->useOriginalLibUnity = (value == "true");
        else if (key == "gameUnityLibraryPath") out->gameUnityLibraryPath = value;
        else if (key == "externalFilesDirectory") out->externalFilesDir = value;
    }

    return !out->appLibraryDir.empty();
}

// Staging

static bool stage_fusion_config(const FusionConfig &config)
{
    LOGI("=== Staging Fusion ===");
    LOGI("  gameLibDir:   %s", config.gameLibraryDir.c_str());
    LOGI("  appLibDir:    %s", config.appLibraryDir.c_str());
    LOGI("  appDataDir:   %s", config.appDataDir.c_str());
    LOGI("  bepInExDir:   %s", config.bepInExDir.c_str());
    LOGI("  dotnetDir:    %s", config.dotnetDir.c_str());
    LOGI("  unityDataDir: %s", config.unityDataDir.c_str());
    LOGI("  unityVersion: %s", config.unityVersion.c_str());
    LOGI("  useOrigUnity: %s", config.useOriginalLibUnity ? "true" : "false");
    LOGI("  gameUnity:    %s", config.gameUnityLibraryPath.c_str());
    LOGI("  externalDir:  %s", config.externalFilesDir.c_str());

    if (!config.externalFilesDir.empty()) {
        bepinex::set_external_files_dir(config.externalFilesDir);
    }

    /* Set library paths */
    std::string unityPath;
    std::string il2cppPath;
    std::string gameIl2cppPath = config.gameLibraryDir + "/libil2cpp.so";
    std::string gameUnityPath = config.gameUnityLibraryPath.empty()
        ? config.gameLibraryDir + "/libunity.so"
        : config.gameUnityLibraryPath;

    if (config.useOriginalLibUnity) {
        /* For split installs this may be an uncompressed APK entry; bionic's
         * linker maps such entries directly, matching a normal game launch. It
         * only works while the entry stays uncompressed (extractNativeLibs=false). */
        unityPath = gameUnityPath;
    } else {
        unityPath = config.appLibraryDir + "/libunity.so";
        /* appLibraryDir/libunity.so may not exist for most games; fall back to
         * the resolved game path (staged copy or APK entry). */
        if (std::ifstream(unityPath).fail()) {
            LOGW("app copy of libunity not found at %s; using game path instead", unityPath.c_str());
            unityPath = gameUnityPath;
        }
    }

    /*
     * Patch libil2cpp.so with an extended memory segment
     * (1 MB code cave) -> copy to appDataDir -> load the patched copy.
     *
     * The code cave provides executable memory within the library's own
     * segment, bypassing Android 14+ W^X restrictions. Dobby trampolines
     * are allocated from this cave instead of mmap (which would be W^X).
     *
     * The patched copy path matches the DataLibrary redirect in
     * NativeLibraryManager: findLibrary("il2cpp") resolves from
     * appDataDirectory, so appDataDir/libil2cpp.so is the returned path.
     */
    std::string patchedIl2CppPath = config.appDataDir + "/libil2cpp.so";
    LOGI("Patching il2cpp: %s -> %s (1MB pool)", gameIl2cppPath.c_str(), patchedIl2CppPath.c_str());

    /* allocate_setup_injected_noload() writes the patched ELF and returns the
     * code-cave base; allocate_bind_loaded() re-binds it after the dlopen. Only
     * success/failure matters here. */
    void *codeCave = allocate_setup_injected_noload(
        gameIl2cppPath.c_str(),
        patchedIl2CppPath.c_str(),
        1024 * 1024);  // 1 MB code cave, load AFTER libunity
    if (!codeCave) {
        LOGE("allocate_setup_injected_noload failed — falling back to original il2cpp path");
        il2cppPath = gameIl2cppPath;
    } else {
        il2cppPath = patchedIl2CppPath;
        LOGI("Patched il2cpp ELF written (load deferred until after libunity)");
    }

    LOGI("  unityPath:  %s", unityPath.c_str());
    LOGI("  il2cppPath: %s", il2cppPath.c_str());

    /* Tell libmain where to find the libraries.
     * Do NOT dlopen/hook libunity here — UnityPlayer is still constructing
     * and loading libunity too early crashes some games (PVZ). */
    libmain_set_override_unity_path(unityPath.c_str());
    libmain_set_override_il2cpp_path(il2cppPath.c_str());

    g_config = config;
    g_staged = true;

    LOGI("=== Fusion staged ===");
    return true;
}

// il2cpp_init hook callback

/*
 * One-shot hook on il2cpp_init.
 *
 * Called when Unity invokes il2cpp_init. We:
 *   1. Destroy the hook (one-shot —only fire once)
 *   2. Call the real il2cpp_init (chains through Dobby trampoline)
 *   3. Set environment variables for BepInEx
 *   4. Start CoreCLR -> BepInEx
 *
 * Signature of the hook target: int il2cpp_init(char *domain_name)
 */
static int il2cpp_init_hook(char *domain_name)
{
    LOGI("il2cpp_init HOOK FIRED!");

    /* 1. Destroy hook IMMEDIATELY (one-shot pattern) */
    il2cpp_destroy_init_hook();
    LOGI("hook destroyed (one-shot)");

    /* 2. Call the real il2cpp_init (goes through Dobby's trampoline) */
    int result = il2cpp_init(domain_name);
    LOGI("real il2cpp_init returned %d", result);

    /* 3. Set env vars for BepInEx —use libmain override paths */
    const char *il2cppPath = libmain_get_override_il2cpp_path();
    setenv("BEPINEX_GAME_ASSEMBLY_PATH", il2cppPath, 1);
    setenv("NEXT_GAME_BINARY", il2cppPath, 1);
    setenv("NEXT_BEPINEX_PATH", g_config.bepInExDir.c_str(), 1);
    setenv("NEXT_GAME_DATA_DIR", g_config.unityDataDir.c_str(), 1);
    setenv("NEXT_APP_DATA_DIR", g_config.appDataDir.c_str(), 1);
    setenv("NEXT_UNITY_VERSION", g_config.unityVersion.c_str(), 1);
    setenv("TMPDIR", g_config.appDataDir.c_str(), 1);
    LOGI("env vars set");

    /* 4. Start CoreCLR + BepInEx */
    LOGI("calling dotnet_start_runtime...");
    dotnet_start_runtime();

    LOGI("hook returning");
    return result;
}

// Public API

extern "C" {

bool fusion_stage_from_config_path(const char *configPath)
{
    LOGI("fusion_stage_from_config_path: %s", configPath);
    g_configPath = configPath;

    std::string content = read_file(configPath);
    if (content.empty()) {
        LOGE("Failed to read config: %s", configPath);
        return false;
    }
    LOGI("Config (%zu bytes):\n%s", content.size(), content.c_str());

    FusionConfig config;
    if (!parse_fusion_config(content.c_str(), &config)) {
        LOGE("Failed to parse config");
        return false;
    }

    return stage_fusion_config(config);
}

static jobject g_appClassLoader = nullptr;
static jmethodID g_loadClassMethod = nullptr;

JavaVM *fusion_get_java_vm(void)
{
    return g_javaVM;
}

jobject fusion_get_app_class_loader(void)
{
    return g_appClassLoader;
}

jmethodID fusion_get_load_class_mid(void)
{
    return g_loadClassMethod;
}

// Resolves app.getClassLoader() (the launcher's loader, which can see
// net.dot.android.crypto.*) or null.
static jobject AppClassLoader(JNIEnv *env)
{
    jclass activityThreadClass = env->FindClass("android/app/ActivityThread");
    if (!activityThreadClass) return nullptr;

    jmethodID currentAppMethod = env->GetStaticMethodID(
        activityThreadClass, "currentApplication", "()Landroid/app/Application;");
    if (!currentAppMethod) return nullptr;

    jobject app = env->CallStaticObjectMethod(activityThreadClass, currentAppMethod);
    if (!app) return nullptr;

    jclass contextClass = env->FindClass("android/content/Context");
    if (!contextClass) return nullptr;

    jmethodID getClassLoaderMethod = env->GetMethodID(
        contextClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (!getClassLoaderMethod) return nullptr;

    jobject classLoader = env->CallObjectMethod(app, getClassLoaderMethod);
    if (!classLoader) return nullptr;
    return env->NewGlobalRef(classLoader);
}

// Fallback: BootstrapActivity's own loader, used only when the app loader was
// not available yet.
static jobject BootstrapClassLoader(JNIEnv *env)
{
    jclass bootClass = env->FindClass("com/bepinex/android/BootstrapActivity");
    if (!bootClass) return nullptr;

    jclass classClass = env->FindClass("java/lang/Class");
    if (!classClass) return nullptr;

    jmethodID getClMethod = env->GetMethodID(
        classClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    if (!getClMethod) return nullptr;

    jobject classLoader = env->CallObjectMethod(bootClass, getClMethod);
    if (!classLoader) return nullptr;
    return env->NewGlobalRef(classLoader);
}

/*
 * Bootstrap the fusion chain. Called from libmain's load() after libunity.so and
 * libil2cpp.so are dlopen'd and before Unity calls il2cpp_init.
 *
 *   0. Capture the JavaVM and the app ClassLoader (CoreCLR starts later on a
 *      thread with no JNIEnv).
 *   1. Hook libunity's scripting_method_invoke (best effort: the game runs even
 *      if this fails).
 *   2. dlopen libil2cpp.so with RTLD_GLOBAL and bind the staged code cave.
 *   3. Point SafeHook at allocate_injected, so Dobby trampolines come from the
 *      1 MB cave stage_fusion_config() added to the patched copy.
 *   4. Install the one-shot DobbyHook on il2cpp_init.
 */
bool fusion_bootstrap_from_libmain(JNIEnv *env)
{
    if (!g_staged) {
        LOGE("fusion_bootstrap called before staging!");
        return false;
    }

    LOGI("=== fusion_bootstrap_from_libmain ===");

    /* 0. Keep the JavaVM for the rest of the process lifetime.
     *
     * We are on a JNI thread here and will not be one again: CoreCLR starts
     * later from the il2cpp_init hook callback, which has no JNIEnv. The
     * platform crypto PAL needs a JavaVM to initialise and keeps whatever it is
     * given, so it has to be captured at this point. */
    if (env && env->GetJavaVM(&g_javaVM) == JNI_OK && g_javaVM) {
        LOGI("captured JavaVM %p", g_javaVM);
    } else {
        LOGW("could not capture JavaVM; the crypto PAL will not be initialised");
        g_javaVM = nullptr;
    }

    /* Capture application ClassLoader so native threads (like UnityMain) can
     * resolve APK classes (such as net.dot.android.crypto.*) during PAL init. */
    if (env) {
        g_appClassLoader = AppClassLoader(env);
        if (env->ExceptionCheck()) env->ExceptionClear();

        if (!g_appClassLoader) {
            g_appClassLoader = BootstrapClassLoader(env);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }

        if (g_appClassLoader) {
            jclass clClass = env->FindClass("java/lang/ClassLoader");
            if (clClass) {
                g_loadClassMethod = env->GetMethodID(
                    clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
            }
            LOGI("captured app ClassLoader %p (loadClass mid %p)",
                 g_appClassLoader, g_loadClassMethod);
        } else {
            LOGW("could not capture app ClassLoader");
        }
    }

    /* 1. Hook libunity to prevent null method crashes */
    const char *unityPath = libmain_get_override_unity_path();
    const char *il2cppPath = libmain_get_override_il2cpp_path();
    LOGI("unity path: %s", unityPath);
    LOGI("il2cpp path: %s", il2cppPath);

    if (!try_hook_libunity(unityPath, unityPath)) {
        LOGW("try_hook_libunity failed —continuing anyway");
    }

    /* 2. Initialize IL2CPP —dlopen libil2cpp.so with RTLD_GLOBAL */

    if (!il2cpp_initialize(il2cppPath)) {
        LOGE("il2cpp_initialize failed");
        return false;
    }
    allocate_bind_loaded(il2cpp_get_handle());

    /* 3. Initialize SafeHook with injected code cave allocator */
    void *il2cppHandle = il2cpp_get_handle();
    uintptr_t il2cppBase = il2cpp_get_library_base();

    /*
     * Pass allocate_injected as the trampoline allocator.
     * This ensures Dobby trampolines are allocated within the patched
     * libil2cpp.so's code cave (bypassing Android W^X).
     */
    if (!safehook_initialize(il2cppHandle, il2cppBase, allocate_injected)) {
        LOGE("safehook_initialize failed");
        return false;
    }
    LOGI("SafeHook initialized");

    /* 4. Install il2cpp_init hook (one-shot, will fire when Unity calls il2cpp_init) */
    il2cpp_install_init_hook(reinterpret_cast<void *>(il2cpp_init_hook));

    LOGI("Fusion bootstrap complete —waiting for il2cpp_init...");
    return true;
}

} /* extern "C" */
