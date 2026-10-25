// libpairipshield.so — hide host-injected modules from integrity VMs.
//
// PairIP (and similar integrity layers) enumerate the process's loaded ELF
// objects through dl_iterate_phdr and treat any module that does not belong to
// the game or the platform as tampering. A hosted game necessarily has such
// modules: the launcher's own libraries, the Java hook engine, and (in debug
// builds) instrumentation agents.
//
// The shield is loaded before the game's protected code runs. It inline-hooks
// libc's dl_iterate_phdr and replays the iteration over a filtered snapshot:
// hidden modules never reach the caller's callback, and the per-entry
// dlpi_adds/dlpi_subs counters are normalized so the visible list is
// internally consistent.
//
// The policy is deliberately a denylist. System, vendor, APEX, game-APK and
// game-data modules stay visible; only host libraries and known
// instrumentation are removed. That keeps normal game and framework lookups
// working while a protected app sees the module set it would see without the
// host.
//
// This file has no dependency on LSPlant or the Java hook engine: it must be
// installable before any other hooking layer, because the protected app checks
// the environment during its very first VM program.

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "dobby.h"

#define LOG_TAG "PairIpShield"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using PhdrCallback = int (*)(dl_phdr_info *, size_t, void *);
using IterateFn = int (*)(PhdrCallback, void *);

struct ModuleEntry {
    dl_phdr_info info;
    size_t size;
    std::string name;
};

struct CollectContext {
    std::vector<ModuleEntry> *entries;
};

std::mutex g_policy_mutex;
std::vector<std::string> g_hidden_prefixes;
std::vector<std::string> g_hidden_substrings;
std::vector<std::string> g_allowed_prefixes;
uintptr_t g_self_base = 0;
IterateFn g_original_iterate = nullptr;

bool StartsWith(const std::string &value, const std::string &prefix) {
    return value.size() >= prefix.size() &&
           value.compare(0, prefix.size(), prefix) == 0;
}

bool Contains(const std::string &value, const std::string &needle) {
    return value.find(needle) != std::string::npos;
}

// Called while the linker lock is held, so it must not re-enter the linker.
// It only copies descriptors and their names into our own storage.
int CollectCallback(dl_phdr_info *info, size_t size, void *data) {
    auto *context = static_cast<CollectContext *>(data);
    if (info == nullptr || context == nullptr || context->entries == nullptr) {
        return 0;
    }

    if (g_self_base != 0 && info->dlpi_addr == g_self_base) {
        return 0;
    }

    std::string name;
    if (info->dlpi_name != nullptr) {
        name.assign(info->dlpi_name);
    }

    if (!name.empty()) {
        std::lock_guard<std::mutex> lock(g_policy_mutex);
        // An explicit allow wins over the host-data prefix. This is what keeps
        // game libraries extracted out of an ABI split visible to the VM.
        bool allowed = false;
        for (const auto &prefix : g_allowed_prefixes) {
            if (StartsWith(name, prefix)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            for (const auto &prefix : g_hidden_prefixes) {
                if (StartsWith(name, prefix)) return 0;
            }
            for (const auto &needle : g_hidden_substrings) {
                if (Contains(name, needle)) return 0;
            }
        }
    }

    ModuleEntry entry{};
    std::memset(&entry.info, 0, sizeof(entry.info));
    std::memcpy(&entry.info, info,
                size < sizeof(entry.info) ? size : sizeof(entry.info));
    entry.size = size;
    entry.name = std::move(name);
    context->entries->push_back(std::move(entry));
    return 0;
}

// Replays dl_iterate_phdr over the filtered snapshot. The caller's callback
// runs outside the linker lock, so it may itself call dlopen/dlsym/dladdr.
int ShieldIterate(PhdrCallback callback, void *data) {
    if (callback == nullptr) return -1;
    IterateFn original = g_original_iterate;
    if (original == nullptr) return -1;

    std::vector<ModuleEntry> entries;
    entries.reserve(256);
    CollectContext context{&entries};
    original(CollectCallback, &context);

    // Stable names after the vector stops growing, and consistent counters:
    // bionic reports the same process-wide dlpi_adds/dlpi_subs on every
    // entry, and a caller can compare their difference with the number of
    // entries it just saw. Hiding modules without adjusting the counters
    // would make that comparison fail.
    const uint64_t visible = entries.size();
    for (auto &entry : entries) {
        entry.info.dlpi_name = entry.name.empty() ? "" : entry.name.c_str();
        entry.info.dlpi_adds = visible;
        entry.info.dlpi_subs = 0;
    }

    for (auto &entry : entries) {
        int result = callback(&entry.info, entry.size, data);
        if (result != 0) return result;
    }
    return 0;
}

void AddHiddenPrefix(const std::string &prefix) {
    if (prefix.empty()) return;
    std::lock_guard<std::mutex> lock(g_policy_mutex);
    for (const auto &existing : g_hidden_prefixes) {
        if (existing == prefix) return;
    }
    g_hidden_prefixes.push_back(prefix);
}

void AddHiddenSubstring(const std::string &needle) {
    if (needle.empty()) return;
    std::lock_guard<std::mutex> lock(g_policy_mutex);
    for (const auto &existing : g_hidden_substrings) {
        if (existing == needle) return;
    }
    g_hidden_substrings.push_back(needle);
}

void AddAllowedPrefix(const std::string &prefix) {
    if (prefix.empty()) return;
    std::lock_guard<std::mutex> lock(g_policy_mutex);
    for (const auto &existing : g_allowed_prefixes) {
        if (existing == prefix) return;
    }
    g_allowed_prefixes.push_back(prefix);
}

std::string JStringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeConfigure(
    JNIEnv *env, jclass, jobjectArray prefixes, jobjectArray substrings,
    jobjectArray allowed_prefixes) {
    if (prefixes != nullptr) {
        const jsize count = env->GetArrayLength(prefixes);
        for (jsize i = 0; i < count; ++i) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(prefixes, i));
            AddHiddenPrefix(JStringToUtf8(env, value));
            if (value != nullptr) env->DeleteLocalRef(value);
        }
    }
    if (substrings != nullptr) {
        const jsize count = env->GetArrayLength(substrings);
        for (jsize i = 0; i < count; ++i) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(substrings, i));
            AddHiddenSubstring(JStringToUtf8(env, value));
            if (value != nullptr) env->DeleteLocalRef(value);
        }
    }
    if (allowed_prefixes != nullptr) {
        const jsize count = env->GetArrayLength(allowed_prefixes);
        for (jsize i = 0; i < count; ++i) {
            auto value = static_cast<jstring>(
                env->GetObjectArrayElement(allowed_prefixes, i));
            AddAllowedPrefix(JStringToUtf8(env, value));
            if (value != nullptr) env->DeleteLocalRef(value);
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeIsActive(JNIEnv *, jclass) {
    return g_original_iterate != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
    Dl_info self{};
    if (dladdr(reinterpret_cast<void *>(&JNI_OnLoad), &self) != 0) {
        g_self_base = reinterpret_cast<uintptr_t>(self.dli_fbase);
    }

    // Defaults that are safe even before the host configures a policy: the
    // shield must hide itself and every common instrumentation module from
    // the first protected-VM call onward.
    AddHiddenSubstring("libpairipshield");
    AddHiddenSubstring("libaudiopolicy");   // renamed Frida gadget in debug builds
    AddHiddenSubstring("liblsplant");
    AddHiddenSubstring("libhookengine");
    AddHiddenSubstring("libdobby");
    AddHiddenSubstring("frida");
    AddHiddenSubstring("gadget");
    AddHiddenSubstring("xposed");
    AddHiddenSubstring("lsposed");
    AddHiddenSubstring("substrate");
    AddHiddenSubstring("magisk");
    AddHiddenSubstring("/data/local/tmp");
    AddHiddenSubstring("/data/adb");

    auto *target = reinterpret_cast<void *>(
        dlsym(RTLD_DEFAULT, "dl_iterate_phdr"));
    if (target == nullptr) {
        LOGE("dl_iterate_phdr not found");
        return JNI_ERR;
    }

    static const long page_size = sysconf(_SC_PAGESIZE);
    auto page = reinterpret_cast<uintptr_t>(target) &
                ~static_cast<uintptr_t>(page_size - 1);
    mprotect(reinterpret_cast<void *>(page),
             static_cast<size_t>(page_size) * 2,
             PROT_READ | PROT_WRITE | PROT_EXEC);

    void *original = nullptr;
    if (DobbyHook(target, reinterpret_cast<void *>(&ShieldIterate), &original) != 0 ||
        original == nullptr) {
        LOGE("failed to hook dl_iterate_phdr");
        return JNI_ERR;
    }
    g_original_iterate = reinterpret_cast<IterateFn>(original);
    LOGI("dl_iterate_phdr shield active (self base %p)", reinterpret_cast<void *>(g_self_base));
    return JNI_VERSION_1_6;
}
