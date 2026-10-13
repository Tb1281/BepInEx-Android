/*
 * BepInEx.Android —.NET CoreCLR bootstrap
 *
 * Starts the .NET CoreCLR runtime and calls BepInEx entry points.
 * Called from the il2cpp_init hook callback, AFTER Unity's native init
 * but BEFORE Unity scripts start running.
 */

#include "fusion.h"
#include "fusion/src/trace_log.h"
#include "host_runtime_contract.h"
#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>
#include <cstdio>
#include <pthread.h>

#define LOG_TAG "Fusion/DotNet"
#define LOGI(...) ::bepinex::log_tag(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) ::bepinex::log_tag(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) ::bepinex::log_tag(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// CoreCLR host API types

typedef int (*coreclr_initialize_fn)(
    const char *exePath,
    const char *appDomainFriendlyName,
    int propertyCount,
    const char **propertyKeys,
    const char **propertyValues,
    void **hostHandle,
    unsigned int *domainId);

typedef int (*coreclr_create_delegate_fn)(
    void *hostHandle,
    unsigned int domainId,
    const char *assemblyName,
    const char *typeName,
    const char *methodName,
    void **delegate);

// Helpers

static bool str_ends_with(const char *str, const char *suffix)
{
    size_t slen = strlen(str);
    size_t xlen = strlen(suffix);
    if (xlen > slen) return false;
    return strcasecmp(str + slen - xlen, suffix) == 0;
}

static bool file_exists(const char *path)
{
    struct stat st;
    return stat(path, &st) == 0;
}

static std::vector<std::string> scan_dlls(const std::string &dir)
{
    std::vector<std::string> result;
    DIR *d = opendir(dir.c_str());
    if (!d) {
        LOGW("Cannot open dir: %s (%s)", dir.c_str(), strerror(errno));
        return result;
    }

    struct dirent *entry;
    while ((entry = readdir(d)) != nullptr) {
        if (entry->d_type != DT_REG && entry->d_type != DT_LNK
            && entry->d_type != DT_UNKNOWN)
            continue;
        if (!str_ends_with(entry->d_name, ".dll"))
            continue;
        result.push_back(dir + "/" + entry->d_name);
    }
    closedir(d);
    std::sort(result.begin(), result.end());
    return result;
}

// Platform PAL initialization
//
// CoreCLR on Android binds System.Security.Cryptography.Native to
// libSystem.Security.Cryptography.Native.Android.so, which implements every
// algorithm by calling into java.security / javax.crypto through JNI. It caches
// the JavaVM in a library-global (`gJvm`) and reads it on every entry point:
//
//   ldr  x0, [gJvm]
//   ldr  x8, [x0]            ; JavaVM->functions
//   ldr  x8, [x8, #0x30]     ;   ->GetEnv
//   blr  x8
//
// `gJvm` is written in exactly one place: AndroidCryptoNative_InitLibraryOnLoad,
// which JNI_OnLoad is a four-byte trampoline to. Nothing else fills it in.
//
// So the PAL must be dlopen'd and given a JavaVM before CoreCLR can P/Invoke
// into it. Android's own loader would normally do this through JNI_OnLoad, but
// here CoreCLR is the one loading the PAL — it resolves the shim by bare name
// from the dotnet directory — and Android never sees the dlopen. Observed
// without this step, on device:
//
//   Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
//     x0 0000000000000000   x4 0 x5 0 x11 0
//   Cause: null pointer dereference
//   backtrace:
//     #00 pc 0x14200  libSystem.Security.Cryptography.Native.Android.so
//     #01 pc 0x12320  libSystem.Security.Cryptography.Native.Android.so
//
// reached from Interop.Crypto..cctor() -> SHA1.Create() ->
// AssemblyDescriptor.ComputePublicKeyToken during Cpp2IL interop generation.
// pc 0x14200 is the `ldr x8, [x0]` above with gJvm still null.
//
// Failure here is reported and the caller continues: a PAL that cannot be
// initialised will throw a DllNotFoundException-shaped managed error at the
// first crypto call, which is diagnosable. Crashing the game here instead would
// not be.
static jclass (*s_originalFindClass)(JNIEnv *, const char *) = nullptr;

static jclass JNICALL pal_FindClass(JNIEnv *env, const char *name)
{
    if (s_originalFindClass) {
        jclass cls = s_originalFindClass(env, name);
        if (cls != nullptr) return cls;
    }

    jobject classLoader = fusion_get_app_class_loader();
    jmethodID loadClassMid = fusion_get_load_class_mid();
    if (classLoader && loadClassMid && name) {
        env->ExceptionClear();
        std::string dotName = name;
        for (char &c : dotName) {
            if (c == '/') c = '.';
        }
        jstring jname = env->NewStringUTF(dotName.c_str());
        if (jname) {
            jclass loaded = (jclass)env->CallObjectMethod(classLoader, loadClassMid, jname);
            env->DeleteLocalRef(jname);
            if (!env->ExceptionCheck() && loaded != nullptr) {
                LOGI("PAL FindClass redirect succeeded for %s", name);
                return loaded;
            }
            env->ExceptionClear();
        }
    }
    return nullptr;
}

static bool init_platform_pal(const std::string &dotnetDir)
{
    std::string palPath = dotnetDir + "/libSystem.Security.Cryptography.Native.Android.so";
    if (!file_exists(palPath.c_str())) {
        palPath = g_config.appLibraryDir + "/libSystem.Security.Cryptography.Native.Android.so";
    }

    void *pal = dlopen(palPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!pal) {
        LOGW("PAL: dlopen failed for %s: %s", palPath.c_str(), dlerror());
        return false;
    }

    auto onLoad = (jint (*)(JavaVM *, void *))dlsym(pal, "JNI_OnLoad");
    if (!onLoad) {
        LOGW("PAL: no JNI_OnLoad in %s", palPath.c_str());
        return false;
    }

    JavaVM *vm = fusion_get_java_vm();
    if (!vm) {
        LOGW("PAL: no JavaVM captured at bootstrap; leaving gJvm unset");
        return false;
    }

    JNIEnv *env = nullptr;
    jint getEnvRc = vm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (getEnvRc != JNI_OK || !env) {
        JavaVMAttachArgs args{};
        args.version = JNI_VERSION_1_6;
        args.name = "UnityMain-PALInit";
        if (vm->AttachCurrentThread(&env, &args) == JNI_OK && env) {
            LOGI("PAL: attached current thread to JavaVM (env=%p)", env);
        } else {
            LOGW("PAL: could not obtain JNIEnv on current thread");
            return false;
        }
    }

    static JNINativeInterface customTable;
    const JNINativeInterface *realTable = env->functions;
    customTable = *realTable;
    s_originalFindClass = realTable->FindClass;
    customTable.FindClass = pal_FindClass;

    env->functions = &customTable;

    LOGI("PAL: calling JNI_OnLoad on %s with hooked FindClass...", palPath.c_str());
    jint rc = onLoad(vm, nullptr);

    env->functions = realTable;

    LOGI("PAL: JNI_OnLoad rc=%d (vm=%p) — AndroidCryptoNative_* now usable", rc, vm);
    return rc == JNI_VERSION_1_6 || rc >= JNI_OK;
}

// Managed entry invocation
//
// The entry point is invoked on a dedicated thread with an explicit stack
// rather than on the caller's.
//
// The caller here is UnityMain, whose stack is sized by the engine and the
// NDK for game script frames, not for a runtime that is about to generate
// interop assemblies, build wrappers and run a chainloader on top of it.
// Observed on device with the call made directly on UnityMain:
//
//   [Debug: Preloader] Runtime invoke patched
//   [Debug: Preloader] [depth] OnInvokeMethod depth=1 method=InitializeSynchronizationContext
//   [Debug: Preloader] [depth] OnInvokeMethod depth=2 method=.cctor
//   E DOTNET : Stack overflow.
//   F libc   : Fatal signal 6 (SIGABRT) tid 8779 (.NET SO Tracer)
//
// The recursion is entirely managed — the detour is entered twice and then the
// stack is gone — so this is stack headroom, not a hook loop. A crash that ends
// in CoreCLR's stack guard with no managed frames above it is indistinguishable
// from a genuine infinite recursion otherwise, hence the explicit thread.
//
// The thread is joined before returning: the caller is the il2cpp_init hook, and
// Unity expects the hook to chain back promptly.
typedef void (*entry_fn)();

#ifndef BEPINEX_MANAGED_STACK_BYTES
#define BEPINEX_MANAGED_STACK_BYTES (64 * 1024 * 1024)
#endif

struct entry_job {
    entry_fn fn;
    int rc;
};

static void *entry_thread(void *arg)
{
    entry_job *job = (entry_job *)arg;
    job->rc = 0;
    job->fn();
    return nullptr;
}

static void run_entry_on_worker(entry_fn fn)
{
    pthread_attr_t attr;
    entry_job job{fn, -1};

    if (pthread_attr_init(&attr) != 0) {
        LOGW("pthread_attr_init failed; calling the entry point inline");
        fn();
        return;
    }
    if (pthread_attr_setstacksize(&attr, BEPINEX_MANAGED_STACK_BYTES) != 0) {
        LOGW("could not request a %d-byte stack; calling inline", BEPINEX_MANAGED_STACK_BYTES);
        pthread_attr_destroy(&attr);
        fn();
        return;
    }

    pthread_t tid;
    int prc = pthread_create(&tid, &attr, entry_thread, &job);
    pthread_attr_destroy(&attr);
    if (prc != 0) {
        LOGW("pthread_create failed (%d); calling the entry point inline", prc);
        fn();
        return;
    }

    LOGI("BepInEx entry running on thread %u with a %d-byte stack", tid,
         BEPINEX_MANAGED_STACK_BYTES);
    pthread_join(tid, nullptr);
    LOGI("BepInEx entry thread finished");
}

// CoreCLR startup

bool dotnet_start_runtime()
{
    LOGI("%s", "dotnet_start_runtime enter");
    LOGI("=== Starting CoreCLR + BepInEx ===");

    /* Resolve CoreCLR */
    LOGI("%s", "dotnet: resolving coreclr");
    void *coreclr = dlopen("libcoreclr.so", RTLD_NOLOAD);
    if (!coreclr) {
        LOGI("%s", "dotnet: libcoreclr.so NOT loaded");
        return false;
    }
    LOGI("%s", "dotnet: coreclr found");

    auto init_fn = (coreclr_initialize_fn)dlsym(coreclr, "coreclr_initialize");
    auto delegate_fn = (coreclr_create_delegate_fn)dlsym(coreclr, "coreclr_create_delegate");

    /* Attach an error writer so CoreCLR's own error messages are captured
     * (they otherwise go to stderr which is not visible on Android). */
    using error_writer_fn = void (*)(const char *message);
    auto set_error_writer_fn =
        (void (*)(error_writer_fn))dlsym(coreclr, "coreclr_set_error_writer");
    if (set_error_writer_fn) {
        set_error_writer_fn([](const char *message) {
            if (message && *message) LOGI("coreclr-error: %s", message);
        });
    } else {
        LOGI("coreclr_set_error_writer not exported");
    }
    LOGI("%s", init_fn ? "coreclr_initialize found" : "coreclr_initialize MISSING");
    LOGI("%s", delegate_fn ? "coreclr_create_delegate found" : "coreclr_create_delegate MISSING");
    if (!init_fn || !delegate_fn) {
        return false;
    }

    /* Find BepInEx core directory */
    std::string bepInExCore = g_config.bepInExDir + "/core";
    std::string dotnetRuntime = g_config.dotnetDir;

    LOGI("BepInEx core: %s", bepInExCore.c_str());
    LOGI("DotNet runtime: %s", dotnetRuntime.c_str());

    if (!file_exists(bepInExCore.c_str())) {
        LOGE("BepInEx core dir not found: %s", bepInExCore.c_str());
        return false;
    }

    /* Build TPA list */
    auto coreDlls = scan_dlls(bepInExCore);
    auto rtDlls = scan_dlls(dotnetRuntime);

    LOGI("Assemblies: %zu in core, %zu in dotnet", coreDlls.size(), rtDlls.size());

    if (coreDlls.empty()) {
        LOGE("No .dll files in %s", bepInExCore.c_str());
        return false;
    }

    std::string tpa;
    for (size_t i = 0; i < coreDlls.size(); i++) {
        if (i > 0) tpa += ":";
        tpa += coreDlls[i];
    }
    for (size_t i = 0; i < rtDlls.size(); i++) {
        tpa += ":";
        tpa += rtDlls[i];
    }

    /* Runtime config */
    std::string runtimeconfig = bepInExCore +
        "/BepInEx.Unity.IL2CPP.runtimeconfig.json";
    if (!file_exists(runtimeconfig.c_str())) {
        runtimeconfig = dotnetRuntime + "/Microsoft.NETCore.App.runtimeconfig.json";
        LOGI("Using fallback runtimeconfig: %s", runtimeconfig.c_str());
    } else {
        LOGI("Runtime config: %s", runtimeconfig.c_str());
    }

    /* CoreCLR properties — minimal set. */
    /*
     * Only 3 keys are set. Enables needed crypto by letting CoreCLR
     * resolve native libraries through Android's default linker paths.
     */
#define NUM_KEYS 5
    const char *propertyKeys[NUM_KEYS] = {
        "TRUSTED_PLATFORM_ASSEMBLIES",
        "APP_PATHS",
        "APP_CONTEXT_BASE_DIRECTORY",
        "SYSTEM_CORELIB_DIRECTORY",
        "HOST_RUNTIME_CONTRACT"
    };

    std::string appPaths = bepInExCore + ":" + dotnetRuntime;
    std::string tpa_list = tpa;
    std::string systemCorelibDir = dotnetRuntime;

    /* Host runtime contract — the modern CoreCLR host looks up special
     * runtime properties (SYSTEM_CORELIB_DIRECTORY, ...) exclusively
     * through this callback. Without it the runtime searches for
     * System.Private.CoreLib.dll next to libcoreclr.so and fails with
     * COR_E_FILENOTFOUND (0x80070002). */
    struct HostContractContext {
        const char **keys;
        const char **values;
        int count;
    };
    static HostContractContext g_contractContext;
    static host_runtime_contract g_hostContract;

    auto get_runtime_property =
        [](const char *key, char *value_buffer, size_t value_buffer_size,
           void *contract_context) -> size_t {
        HostContractContext *ctx = static_cast<HostContractContext *>(contract_context);
        for (int i = 0; i < ctx->count; ++i) {
            if (strcmp(ctx->keys[i], key) == 0) {
                size_t len = strlen(ctx->values[i]) + 1;
                if (value_buffer_size < len) return len;
                memcpy(value_buffer, ctx->values[i], len);
                return len;
            }
        }
        return (size_t)-1;
    };

    g_hostContract = {};
    g_hostContract.size = sizeof(host_runtime_contract);
    g_hostContract.context = &g_contractContext;
    g_hostContract.get_runtime_property = get_runtime_property;

    char contractAddress[64];
    snprintf(contractAddress, sizeof(contractAddress), "0x%zx",
             (size_t)&g_hostContract);

    const char *propertyValues[NUM_KEYS] = {
        tpa_list.c_str(),
        appPaths.c_str(),
        bepInExCore.c_str(),
        systemCorelibDir.c_str(),
        contractAddress
    };
    g_contractContext.keys = propertyKeys;
    g_contractContext.values = propertyValues;
    g_contractContext.count = NUM_KEYS - 1;  // contract address itself excluded

    setenv("DOTNET_ReadyToRun", "0", 1);

    /* Hand the platform crypto PAL a JavaVM before any managed code can
     * P/Invoke into it. CoreCLR resolves the shim lazily on first use, which is
     * long after Android's loader would have called its JNI_OnLoad, so nothing
     * else will. See init_platform_pal(). */
    if (!init_platform_pal(dotnetRuntime)) {
        LOGW("PAL: not initialised; crypto calls will fail at first use");
    }

    /* Initialize CoreCLR — with W^X retry. */
    setenv("DOTNET_EnableWriteXorExecute", "0", 1);

    /* CoreCLR identifies the entry assembly by exePath — must be the
     * actual managed assembly, not the runtimeconfig file. */
    std::string exePath = bepInExCore + "/BepInEx.Unity.IL2CPP.dll";

    void *host = nullptr;
    unsigned int dom = 0;
    int rc = -1;

    for (int attempt = 1; attempt <= 2; ++attempt) {
        host = nullptr;
        dom = 0;
        rc = init_fn(exePath.c_str(), "BepInExHost",
                     NUM_KEYS, propertyKeys, propertyValues, &host, &dom);

        LOGI("coreclr_initialize attempt %d rc=%d", attempt, rc);

        if (rc >= 0) break;

        if (attempt == 1) {
            setenv("DOTNET_EnableWriteXorExecute", "1", 1);
            LOGI("Retrying CoreCLR with W^X enabled");
            usleep(75000);
        }
    }

    if (rc >= 0) {
        LOGI("CoreCLR started (host=%p, domain=%u)", host, dom);
        LOGI("%s", "CoreCLR started!");
    } else {
        LOGE("coreclr_initialize failed: 0x%x", rc);
        return false;
    }

    /* Call BepInEx entry point */
    // AndroidEntrypoint.Start reads the NEXT_* env vars via EnvVars.LoadVars()
    // and calls UnityPreloaderRunner.PreloaderMain internally.
    void *bepInExEntry = nullptr;
    int entryRc = delegate_fn(host, dom,
        "BepInEx.Unity.IL2CPP",
        "BepInEx.Unity.IL2CPP.AndroidEntrypoint", "Start",
        &bepInExEntry);
    LOGI("delegate AndroidEntrypoint.Start rc=%d", entryRc);
    if (entryRc == 0 && bepInExEntry) {
        LOGI("%s", "Found entry point!");
    }

    if (bepInExEntry) {
        LOGI("%s", "Calling BepInEx entry...");
        /* Managed crashes / unhandled exceptions print to stdout/stderr.
         * Redirect them to files so they persist on device. Path comes
         * dynamically from Android Context (FusionConfig.externalFilesDirectory). */
        std::string externalFiles = !g_config.externalFilesDir.empty()
            ? g_config.externalFilesDir
            : bepinex::external_files_dir();
        freopen((externalFiles + "/coreclr_stdout.log").c_str(), "w", stdout);
        freopen((externalFiles + "/coreclr_stderr.log").c_str(), "w", stderr);
        std::string bepinRoot = g_config.bepInExDir;
        chdir(bepinRoot.c_str());
        mkdir((bepinRoot + "/logs").c_str(), 0755);
        typedef void (*entry_fn)();
        run_entry_on_worker((entry_fn)bepInExEntry);
        LOGI("%s", "BepInEx done");
    } else {
        LOGI("%s", "No BepInEx entry found");
    }

    return true;
}
