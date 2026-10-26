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
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <dirent.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cinttypes>
#include <climits>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <ucontext.h>
#include <unordered_map>
#include <vector>

#include "dobby.h"

#define LOG_TAG "PairIpShield"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" __attribute__((visibility("hidden"))) void pairip_append_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_append_trace_c(uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_append_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_dispatch_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_dispatch_trace_c(uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_dispatch_original;
extern "C" __attribute__((visibility("hidden"))) long pairip_syscall_hook(
    long number, long a1, long a2, long a3, long a4, long a5, long a6);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_syscall_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_copy_patch_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_copy_patch_c(uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_copy_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_class_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_class_trace_c(uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_class_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_name_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_name_trace_c(
    const char *expected, const char *actual, size_t length);

bool InstallExecuteProgramHook();

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
std::mutex g_install_mutex;
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

// Installs the dl_iterate_phdr inline hook once. Kept separate from
// JNI_OnLoad so the same library can also serve as a plain JNI bridge
// (loading it must not modify the process until a caller explicitly asks
// for the shield).
bool InstallShieldHook() {
    std::lock_guard<std::mutex> lock(g_install_mutex);
    if (g_original_iterate != nullptr) return true;

    auto *target = reinterpret_cast<void *>(
        dlsym(RTLD_DEFAULT, "dl_iterate_phdr"));
    if (target == nullptr) {
        LOGE("dl_iterate_phdr not found");
        return false;
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
        return false;
    }
    g_original_iterate = reinterpret_cast<IterateFn>(original);
    LOGI("dl_iterate_phdr shield active (self base %p)", reinterpret_cast<void *>(g_self_base));
    return true;
}

// Debug-only PairIP VM tracer.
//
// The startup program after YTw runs a string operation that crashes inside
// memmove with a non-canonical source pointer. Hooking the VM's append helper
// at libpairipcore.so+0x8d460 captures the caller's return address and the
// whole interpreter frame, which identifies the VM register that holds the
// bad value.
std::atomic<bool> g_trace_enabled{false};
std::atomic<uintptr_t> g_pairip_base{0};
std::mutex g_trace_mutex;

// Ring buffer of the last VM instructions executed by the klB program.
//
// executeVM's dispatcher is called with the interpreter state in x0 and the
// decoded opcode in w1. The state lives in executeVM's frame, so:
//   [state+0x88] -> address of the image pointer
//   [state+0x90] -> operand cursor (the value the klB handler treats as pc)
//   [state-0x40] -> packed image length (low 32) and fetch cursor (high 32)
// Only klB's image is recorded (length 0x44feb and the "IAP" magic), which
// keeps the ring free of YTw and of other VM programs.
constexpr uint32_t kKlBImageLength = 0x44feb;
constexpr size_t kVmRingSize = 8192;
struct VmRingEntry {
    uint32_t fetch_pc;
    uint32_t cursor;
    uint32_t opcode;
    uint32_t reserved;
};
VmRingEntry g_vm_ring[kVmRingSize] = {};
std::atomic<uint32_t> g_vm_ring_pos{0};
std::atomic<bool> g_vm_trace_enabled{false};

// Debug-only image-write attribution. The copy hook snapshots klB's writable
// image just before the interpreter starts; the dispatcher hook then compares
// the live image at every entry, so a changed range belongs to the instruction
// that ran immediately before that entry. This answers whether the two ZIP
// reader words are written at all and which VM instruction was responsible.
std::mutex g_vm_diff_mutex;
uintptr_t g_vm_diff_image = 0;
uint32_t g_vm_diff_last_pos = 0;
std::vector<uint8_t> g_vm_diff_snapshot;
std::atomic<int> g_vm_diff_ranges{0};

// Ring of image mutations, in execution order, dumped together with the
// instruction ring so the write attribution survives logcat volume limits.
constexpr size_t kVmWriteRingSize = 16384;
struct VmWriteEntry {
    uint32_t offset;
    uint32_t length;
    uint64_t old_value;
    uint64_t new_value;
    uint32_t prior_index;
    uint32_t reserved;
};
VmWriteEntry g_vm_write_ring[kVmWriteRingSize] = {};
std::atomic<uint32_t> g_vm_write_pos{0};

// Ring buffer of the last raw syscalls libpairipcore issued. klB reads the
// ZIP central directory through the same wrapper (base+0x17430), so the
// syscall stream identifies whether the repeating VM loop is polling a file,
// a futex, a clock or an anti-debug interface.
constexpr size_t kSyscallRingSize = 512;
struct SyscallRingEntry {
    int64_t number;
    int64_t args[6];
    int64_t result;
    uintptr_t caller;
    char path[128];
    uint8_t sample[16];
    uint32_t vm_fetch;
    uint32_t vm_op;
};
SyscallRingEntry g_syscall_ring[kSyscallRingSize] = {};
std::atomic<uint32_t> g_syscall_ring_pos{0};
std::atomic<bool> g_syscall_trace_enabled{false};

// Last VM instruction dispatched on this thread. The syscall wrapper is
// reached from VM handlers, so this attributes a procfs access to the bytecode
// instruction that issued it.
thread_local uint32_t t_last_vm_fetch = 0;
thread_local uint32_t t_last_vm_op = 0;

// Debug-only procfs sanitizer.
//
// The startup VM reads /proc/<pid>/maps through its own raw syscall wrapper
// (libpairipcore.so+0x17430), not through libc. A hosted launch necessarily
// adds the launcher's APK, libraries and data directory to that file. The
// wrapper hook serves a byte-for-byte same-sized copy of the file with every
// host-only line blanked, so size-based readers and line counts still agree.
std::atomic<bool> g_proc_sanitize_enabled{false};
std::atomic<int> g_game_uid{-1};
std::atomic<int> g_host_uid{-1};
using GetIdFn = unsigned int (*)();
GetIdFn g_getuid_original = nullptr;
GetIdFn g_geteuid_original = nullptr;
GetIdFn g_getgid_original = nullptr;
GetIdFn g_getegid_original = nullptr;

unsigned int SpoofedId(GetIdFn original, int value) {
    if (g_proc_sanitize_enabled.load() && value >= 0) {
        return static_cast<unsigned int>(value);
    }
    return original != nullptr ? original() : 0;
}

extern "C" __attribute__((visibility("hidden"))) unsigned int
pairip_getuid_hook() {
    return SpoofedId(g_getuid_original, g_game_uid.load());
}

extern "C" __attribute__((visibility("hidden"))) unsigned int
pairip_geteuid_hook() {
    return SpoofedId(g_geteuid_original, g_game_uid.load());
}

extern "C" __attribute__((visibility("hidden"))) unsigned int
pairip_getgid_hook() {
    return SpoofedId(g_getgid_original, g_game_uid.load());
}

extern "C" __attribute__((visibility("hidden"))) unsigned int
pairip_getegid_hook() {
    return SpoofedId(g_getegid_original, g_game_uid.load());
}
std::mutex g_proc_mutex;

using ReaddirFn = dirent *(*)(DIR *);
ReaddirFn g_readdir_original = nullptr;

// Debug-only klB ZIP-view repair.
//
// libpairipcore.so+0x6aa20 is the instruction after executeVM has copied the
// VM bytecode into its writable image. klB's first ZIP check builds a string
// view from the image literals at +0x3f874 and +0x1ba5c; a hosted launch
// leaves them holding the asset's ciphertext, so the check memmoves 0x2e
// bytes from a non-canonical pointer. Pointing the literals at the base APK's
// real central directory tests whether that unresolved view is what kills
// klB, without depending on the environment checks that precede it.
std::atomic<bool> g_fake_cd_enabled{false};
std::atomic<uintptr_t> g_fake_cd_data{0};
std::atomic<uintptr_t> g_fake_cd_size{0};
std::mutex g_fake_cd_mutex;

// Debug-only post-copy image dump. executeVM copies the Java byte[] into a
// malloc'd buffer at +0x6a9c8 and only then starts decoding it. Comparing that
// buffer with the APK asset shows every decryption/relocation the loader
// performs, and therefore which image literals are still unresolved when klB
// reaches its ZIP check.
std::atomic<bool> g_vm_dump_enabled{false};
std::mutex g_vm_dump_mutex;
std::string g_vm_dump_path;
std::mutex g_copy_hook_mutex;

// Debug-only class-resolution trace. The VM's resolver at +0x8c570 decodes a
// class name from a runtime string-table descriptor. Logging the descriptor
// and the first bytes of its decoded entry lets us tell a stale literal from
// a genuinely missing class.
std::atomic<bool> g_class_trace_enabled{false};
std::mutex g_class_trace_mutex;

struct ProcFileState {
    std::string content;
    size_t offset = 0;
};

std::unordered_map<int, ProcFileState> g_proc_files;
std::unordered_map<int, std::string> g_open_paths;

struct ModuleLookup {
    const char *needle;
    uintptr_t base;
};

int FindModuleCallback(dl_phdr_info *info, size_t, void *data) {
    auto *lookup = static_cast<ModuleLookup *>(data);
    if (info == nullptr || lookup == nullptr || info->dlpi_name == nullptr) return 0;
    if (std::strstr(info->dlpi_name, lookup->needle) == nullptr) return 0;
    lookup->base = static_cast<uintptr_t>(info->dlpi_addr);
    return 1;
}

using MemcmpFn = int (*)(const void *, const void *, size_t);
extern "C" __attribute__((visibility("hidden"))) int pairip_memcmp_hook(
    const void *a, const void *b, size_t n);
MemcmpFn g_memcmp_original = nullptr;

bool InstallPairIpTrace() {
    std::lock_guard<std::mutex> lock(g_trace_mutex);
    if (g_pairip_append_original != nullptr) return true;

    ModuleLookup lookup{"libpairipcore.so", 0};
    dl_iterate_phdr(FindModuleCallback, &lookup);
    if (lookup.base == 0) {
        LOGE("trace: libpairipcore.so is not mapped");
        return false;
    }
    g_pairip_base.store(lookup.base);

    auto *target = reinterpret_cast<void *>(lookup.base + 0x8d460);
    static const long page_size = sysconf(_SC_PAGESIZE);
    auto page = reinterpret_cast<uintptr_t>(target) &
                ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("trace: mprotect failed");
    }

    int result = DobbyHook(target, reinterpret_cast<void *>(&pairip_append_trace_entry),
                           &g_pairip_append_original);
    LOGI("trace: append hook rc=%d base=%p target=%p trampoline=%p",
         result, reinterpret_cast<void *>(lookup.base), target,
         g_pairip_append_original);
    if (result != 0 || g_pairip_append_original == nullptr) return false;

    // Record the klB instruction stream so the crash can be tied to the last
    // opcode that ran before the bad string view was read.
    auto *dispatcher = reinterpret_cast<void *>(lookup.base + 0x6e918);
    const int dispatch_result = DobbyHook(
        dispatcher, reinterpret_cast<void *>(&pairip_dispatch_trace_entry),
        &g_pairip_dispatch_original);
    LOGI("trace: dispatch hook rc=%d target=%p trampoline=%p", dispatch_result,
         dispatcher, g_pairip_dispatch_original);
    if (dispatch_result == 0 && g_pairip_dispatch_original != nullptr) {
        g_vm_trace_enabled.store(true);
    }

    g_trace_enabled.store(true);

    if (g_memcmp_original == nullptr) {
        auto *memcmp_target = dlsym(RTLD_DEFAULT, "memcmp");
        if (memcmp_target != nullptr) {
            void *original = nullptr;
            const int rc = DobbyHook(
                memcmp_target, reinterpret_cast<void *>(&pairip_memcmp_hook),
                &original);
            g_memcmp_original = reinterpret_cast<MemcmpFn>(original);
            LOGI("maps-scan: memcmp hook rc=%d original=%p", rc, original);
        }
    }
    return true;
}

// Maps-scan needle tracing. The klB startup program filters /proc/self/maps
// with a substring comparison (memchr + memcmp). When the scan is active, the
// first comparisons expose the needle the VM is looking for, which decides
// whether the missing ZIP view is an anti-tamper branch or a real lookup.
bool SafeReadMemory(uintptr_t address, void *out, size_t size);
std::atomic<bool> g_maps_scan_trace{false};
std::atomic<bool> g_klb_active{false};
std::atomic<int> g_maps_scan_log{0};

extern "C" __attribute__((visibility("hidden")))
int pairip_memcmp_hook(const void *a, const void *b, size_t n) {
    auto original = g_memcmp_original;
    if (original == nullptr) return 0;
    if (g_maps_scan_trace.load() && n < 512 &&
        g_maps_scan_log.fetch_add(1) < 24) {
        char left[65] = {};
        char right[65] = {};
        const size_t copy = n < 64 ? n : 64;
        SafeReadMemory(reinterpret_cast<uintptr_t>(a), left, copy);
        SafeReadMemory(reinterpret_cast<uintptr_t>(b), right, copy);
        for (size_t i = 0; i < copy; ++i) {
            if (left[i] < 0x20 || left[i] > 0x7e) left[i] = '.';
            if (right[i] < 0x20 || right[i] > 0x7e) right[i] = '.';
        }
        LOGI("maps-scan: memcmp n=%zu left=%s right=%s", n, left, right);
    }
    return original(a, b, n);
}

bool ShouldHideProcLine(const std::string &line) {
    std::lock_guard<std::mutex> lock(g_policy_mutex);
    for (const auto &prefix : g_allowed_prefixes) {
        if (Contains(line, prefix)) return false;
    }
    for (const auto &prefix : g_hidden_prefixes) {
        if (Contains(line, prefix)) return true;
    }
    for (const auto &needle : g_hidden_substrings) {
        if (Contains(line, needle)) return true;
    }
    return false;
}

bool IsProcMapsOrStatus(const char *path) {
    if (path == nullptr || std::strncmp(path, "/proc/", 6) != 0) {
        return false;
    }
    const size_t length = std::strlen(path);
    return (length >= 5 && std::strcmp(path + length - 5, "/maps") == 0) ||
           (length >= 7 && std::strcmp(path + length - 7, "/status") == 0);
}

std::string ReadWholeFile(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    std::string data;
    char buffer[8192];
    for (;;) {
        ssize_t count = read(fd, buffer, sizeof(buffer));
        if (count > 0) {
            data.append(buffer, static_cast<size_t>(count));
            continue;
        }
        if (count < 0 && errno == EINTR) continue;
        break;
    }
    close(fd);
    return data;
}

std::string BlankHiddenMapLines(std::string content) {
    size_t position = 0;
    while (position < content.size()) {
        size_t end = content.find('\n', position);
        if (end == std::string::npos) end = content.size();
        const std::string line = content.substr(position, end - position);
        if (!line.empty() && ShouldHideProcLine(line)) {
            std::fill(content.begin() + static_cast<std::ptrdiff_t>(position),
                      content.begin() + static_cast<std::ptrdiff_t>(end), ' ');
        }
        position = end + (end < content.size() ? 1 : 0);
    }
    return content;
}

std::string SanitizeStatus(std::string content) {
    const int game_uid = g_game_uid.load();
    if (game_uid < 0) return content;
    // Capture the real host uid before the getuid() hooks are installed;
    // asking libc here would return the spoofed game uid.
    const int host_uid = g_host_uid.load() >= 0
                             ? g_host_uid.load()
                             : static_cast<int>(syscall(SYS_getuid));
    if (game_uid == host_uid) return content;

    const std::string host_text = std::to_string(host_uid);
    const std::string game_text = std::to_string(game_uid);
    if (host_text.size() != game_text.size()) return content;

    char host_private[16];
    char game_private[16];
    char host_shared[16];
    char game_shared[16];
    std::snprintf(host_private, sizeof(host_private), "2%04d", host_uid % 10000);
    std::snprintf(game_private, sizeof(game_private), "2%04d", game_uid % 10000);
    std::snprintf(host_shared, sizeof(host_shared), "5%04d", host_uid % 10000);
    std::snprintf(game_shared, sizeof(game_shared), "5%04d", game_uid % 10000);

    size_t position = 0;
    while (position < content.size()) {
        size_t end = content.find('\n', position);
        if (end == std::string::npos) end = content.size();
        const bool identity_line =
            content.compare(position, 4, "Uid:") == 0 ||
            content.compare(position, 4, "Gid:") == 0;
        const bool groups_line =
            content.compare(position, 7, "Groups:") == 0;
        if (identity_line || groups_line) {
            std::string line = content.substr(position, end - position);
            std::string replaced = line;
            auto replace_all = [&replaced](const std::string &from,
                                           const std::string &to) {
                if (from.empty() || from.size() != to.size()) return;
                size_t at = 0;
                while ((at = replaced.find(from, at)) != std::string::npos) {
                    replaced.replace(at, from.size(), to);
                    at += to.size();
                }
            };
            if (identity_line) {
                replace_all(host_text, game_text);
            } else {
                replace_all(host_private, game_private);
                replace_all(host_shared, game_shared);
            }
            if (replaced != line) {
                content.replace(position, line.size(), replaced);
            }
        }
        position = end + (end < content.size() ? 1 : 0);
    }
    return content;
}

void MaybeDumpProcContent(const char *path, const std::string &content) {
    if (!g_vm_dump_enabled.load() || path == nullptr) return;
    const size_t length = std::strlen(path);
    const char *name = nullptr;
    std::atomic<bool> *done = nullptr;
    static std::atomic<bool> maps_done{false};
    static std::atomic<bool> status_done{false};
    if (length >= 5 && std::strcmp(path + length - 5, "/maps") == 0) {
        name = "proc-maps.txt";
        done = &maps_done;
    } else if (length >= 7 &&
               std::strcmp(path + length - 7, "/status") == 0) {
        name = "proc-status.txt";
        done = &status_done;
    } else {
        return;
    }
    if (done->exchange(true)) return;

    std::string directory;
    {
        std::lock_guard<std::mutex> lock(g_vm_dump_mutex);
        directory = g_vm_dump_path;
    }
    if (directory.empty()) return;
    const std::string output = directory + "/" + name;
    FILE *file = std::fopen(output.c_str(), "wb");
    if (file == nullptr) {
        LOGE("proc-dump: open %s failed: %s", output.c_str(),
             std::strerror(errno));
        return;
    }
    const size_t written = std::fwrite(content.data(), 1, content.size(), file);
    std::fclose(file);
    LOGI("proc-dump: wrote %zu bytes to %s", written, output.c_str());
}

std::string SanitizeProcContent(const char *path, std::string content) {
    const size_t length = std::strlen(path);
    if (length >= 5 && std::strcmp(path + length - 5, "/maps") == 0) {
        return BlankHiddenMapLines(std::move(content));
    }
    if (length >= 7 && std::strcmp(path + length - 7, "/status") == 0) {
        return SanitizeStatus(std::move(content));
    }
    return content;
}

bool ShouldHideFdTarget(const std::string &target) {
    std::lock_guard<std::mutex> lock(g_policy_mutex);
    for (const auto &prefix : g_allowed_prefixes) {
        if (StartsWith(target, prefix)) return false;
    }
    for (const auto &prefix : g_hidden_prefixes) {
        if (Contains(target, prefix)) return true;
    }
    for (const auto &needle : g_hidden_substrings) {
        if (Contains(target, needle)) return true;
    }
    return false;
}

bool IsProcFdDir(DIR *dirp) {
    const int fd = dirfd(dirp);
    if (fd < 0) return false;
    char link[64];
    std::snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    char target[PATH_MAX];
    const ssize_t length = readlink(link, target, sizeof(target) - 1);
    if (length <= 0) return false;
    target[length] = '\0';
    if (std::strncmp(target, "/proc/", 6) != 0) return false;
    return length >= 3 && std::strcmp(target + length - 3, "/fd") == 0;
}

bool HiddenProcFdEntry(const char *name) {
    if (name == nullptr) return false;
    char link[PATH_MAX];
    std::snprintf(link, sizeof(link), "/proc/self/fd/%s", name);
    char target[PATH_MAX];
    const ssize_t length = readlink(link, target, sizeof(target) - 1);
    if (length <= 0) return false;
    target[length] = '\0';
    return ShouldHideFdTarget(target);
}

extern "C" __attribute__((visibility("hidden")))
dirent *pairip_readdir_hook(DIR *dirp) {
    ReaddirFn original = g_readdir_original;
    if (original == nullptr) {
        errno = EBADF;
        return nullptr;
    }
    if (!g_proc_sanitize_enabled.load() || !IsProcFdDir(dirp)) {
        return original(dirp);
    }
    for (;;) {
        dirent *entry = original(dirp);
        if (entry == nullptr) return nullptr;
        if (HiddenProcFdEntry(entry->d_name)) {
            LOGI("proc: hide fd entry %s", entry->d_name);
            continue;
        }
        return entry;
    }
}

bool InstallSyscallTrace() {
    std::lock_guard<std::mutex> lock(g_trace_mutex);
    if (g_pairip_syscall_original == nullptr) {
        ModuleLookup lookup{"libpairipcore.so", 0};
        dl_iterate_phdr(FindModuleCallback, &lookup);
        if (lookup.base == 0) {
            LOGE("syscall-trace: libpairipcore.so is not mapped");
            return false;
        }
        g_pairip_base.store(lookup.base);

        auto *target = reinterpret_cast<void *>(lookup.base + 0x17430);
        static const long page_size = sysconf(_SC_PAGESIZE);
        auto page = reinterpret_cast<uintptr_t>(target) &
                    ~static_cast<uintptr_t>(page_size - 1);
        if (mprotect(reinterpret_cast<void *>(page),
                     static_cast<size_t>(page_size),
                     PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            LOGE("syscall-trace: mprotect failed");
        }

        const int result = DobbyHook(
            target, reinterpret_cast<void *>(&pairip_syscall_hook),
            &g_pairip_syscall_original);
        LOGI("syscall-trace: hook rc=%d base=%p target=%p trampoline=%p",
             result, reinterpret_cast<void *>(lookup.base), target,
             g_pairip_syscall_original);
        if (result != 0 || g_pairip_syscall_original == nullptr) return false;
    }
    g_syscall_trace_enabled.store(true);
    LOGI("syscall-trace: enabled");
    return true;
}

bool InstallProcSanitizer() {
    std::lock_guard<std::mutex> lock(g_trace_mutex);
    if (g_host_uid.load() < 0) {
        g_host_uid.store(static_cast<int>(syscall(SYS_getuid)));
    }
    if (g_pairip_syscall_original != nullptr) {
        g_proc_sanitize_enabled.store(true);
        return true;
    }

    ModuleLookup lookup{"libpairipcore.so", 0};
    dl_iterate_phdr(FindModuleCallback, &lookup);
    if (lookup.base == 0) {
        LOGE("proc: libpairipcore.so is not mapped");
        return false;
    }
    g_pairip_base.store(lookup.base);

    auto *target = reinterpret_cast<void *>(lookup.base + 0x17430);
    static const long page_size = sysconf(_SC_PAGESIZE);
    auto page = reinterpret_cast<uintptr_t>(target) &
                ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("proc: mprotect failed");
    }

    int result = DobbyHook(target, reinterpret_cast<void *>(&pairip_syscall_hook),
                           &g_pairip_syscall_original);
    LOGI("proc: syscall hook rc=%d base=%p target=%p trampoline=%p",
         result, reinterpret_cast<void *>(lookup.base), target,
         g_pairip_syscall_original);
    if (result != 0 || g_pairip_syscall_original == nullptr) return false;

    if (g_readdir_original == nullptr) {
        auto *readdir_target = dlsym(RTLD_DEFAULT, "readdir");
        if (readdir_target != nullptr) {
            void *original = nullptr;
            const int readdir_result = DobbyHook(
                readdir_target,
                reinterpret_cast<void *>(&pairip_readdir_hook), &original);
            g_readdir_original = reinterpret_cast<ReaddirFn>(original);
            LOGI("proc: readdir hook rc=%d original=%p", readdir_result, original);
        }
    }

    // The VM resolves getuid/geteuid directly from libc in addition to
    // parsing /proc. A hosted process runs under the launcher's uid, so those
    // calls would reveal a different uid than the game package it inspects.
    // Hook them only after the real host uid has been captured for the
    // /proc/self/status rewrite.
    const struct {
        const char *name;
        void *hook;
        GetIdFn *original;
    } id_hooks[] = {
        {"getuid", reinterpret_cast<void *>(&pairip_getuid_hook),
         &g_getuid_original},
        {"geteuid", reinterpret_cast<void *>(&pairip_geteuid_hook),
         &g_geteuid_original},
        {"getgid", reinterpret_cast<void *>(&pairip_getgid_hook),
         &g_getgid_original},
        {"getegid", reinterpret_cast<void *>(&pairip_getegid_hook),
         &g_getegid_original},
    };
    for (const auto &hook : id_hooks) {
        if (*hook.original != nullptr) continue;
        auto *id_target = dlsym(RTLD_DEFAULT, hook.name);
        if (id_target == nullptr) continue;
        void *original = nullptr;
        const int id_result = DobbyHook(id_target, hook.hook, &original);
        *hook.original = reinterpret_cast<GetIdFn>(original);
        LOGI("proc: %s hook rc=%d original=%p", hook.name, id_result, original);
    }

    g_proc_sanitize_enabled.store(true);
    return true;
}

uint32_t ReadLe32(const uint8_t *bytes) {
    return static_cast<uint32_t>(bytes[0]) |
           (static_cast<uint32_t>(bytes[1]) << 8) |
           (static_cast<uint32_t>(bytes[2]) << 16) |
           (static_cast<uint32_t>(bytes[3]) << 24);
}

// Maps the ZIP central directory of `apk_path` read-only. The VM only reads
// through the pointer, so an independent mapping of the same file bytes is
// equivalent to the mapping the platform's zip reader installs.
bool MapApkCentralDirectory(const char *apk_path, uintptr_t *data,
                            uintptr_t *size, std::string *error) {
    const int fd = open(apk_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "open failed";
        return false;
    }
    struct stat status {};
    if (fstat(fd, &status) != 0 || status.st_size < 22) {
        close(fd);
        *error = "stat failed";
        return false;
    }

    constexpr size_t kTailCapacity = 66 * 1024;
    size_t tail_size = static_cast<size_t>(status.st_size);
    if (tail_size > kTailCapacity) tail_size = kTailCapacity;
    std::vector<uint8_t> tail(tail_size);
    const off_t tail_offset = status.st_size - static_cast<off_t>(tail_size);
    const ssize_t got = pread(fd, tail.data(), tail_size, tail_offset);
    if (got != static_cast<ssize_t>(tail_size)) {
        close(fd);
        *error = "short read";
        return false;
    }

    ssize_t eocd = -1;
    for (ssize_t i = static_cast<ssize_t>(tail_size) - 22; i >= 0; --i) {
        if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 5 &&
            tail[i + 3] == 6) {
            eocd = i;
            break;
        }
    }
    if (eocd < 0) {
        close(fd);
        *error = "EOCD not found";
        return false;
    }

    const uint32_t cd_size = ReadLe32(tail.data() + eocd + 12);
    const uint32_t cd_offset = ReadLe32(tail.data() + eocd + 16);
    if (cd_size == 0 ||
        static_cast<uint64_t>(cd_offset) + cd_size >
            static_cast<uint64_t>(status.st_size)) {
        close(fd);
        *error = "invalid central directory range";
        return false;
    }

    static const long page_size = sysconf(_SC_PAGESIZE);
    const uint64_t aligned =
        cd_offset & ~(static_cast<uint64_t>(page_size) - 1);
    const size_t delta = static_cast<size_t>(cd_offset - aligned);
    void *map = mmap(nullptr, cd_size + delta, PROT_READ, MAP_PRIVATE, fd,
                     static_cast<off_t>(aligned));
    close(fd);
    if (map == MAP_FAILED) {
        *error = "mmap failed";
        return false;
    }

    const auto *cd = static_cast<const uint8_t *>(map) + delta;
    if (std::memcmp(cd, "PK\x01\x02", 4) != 0) {
        munmap(map, cd_size + delta);
        *error = "central directory signature mismatch";
        return false;
    }

    *data = reinterpret_cast<uintptr_t>(cd);
    *size = cd_size;
    return true;
}

// Installs the executeVM copy-point shim once. Both the debug image dump and
// the fake-central-directory experiment need the same hook, so installation is
// shared and the C callback decides what to do with each image.
bool InstallCopyHook(std::string *error) {
    std::lock_guard<std::mutex> lock(g_copy_hook_mutex);
    if (g_pairip_copy_original != nullptr) return true;

    ModuleLookup lookup{"libpairipcore.so", 0};
    dl_iterate_phdr(FindModuleCallback, &lookup);
    if (lookup.base == 0) {
        *error = "libpairipcore.so is not mapped";
        return false;
    }

    auto *target = reinterpret_cast<void *>(lookup.base + 0x6aa20);
    static const long page_size = sysconf(_SC_PAGESIZE);
    auto page = reinterpret_cast<uintptr_t>(target) &
                ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page),
                 static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        *error = "mprotect failed";
        return false;
    }

    const int result = DobbyHook(
        target, reinterpret_cast<void *>(&pairip_copy_patch_entry),
        &g_pairip_copy_original);
    if (result != 0 || g_pairip_copy_original == nullptr) {
        *error = "DobbyHook failed";
        return false;
    }
    LOGI("copy-hook: base=%p target=%p", reinterpret_cast<void *>(lookup.base),
         target);
    if (!InstallExecuteProgramHook()) {
        LOGI("copy-hook: ExecuteProgram hook unavailable");
    }
    return true;
}

bool InstallFakeCentralDirectory(const char *apk_path, std::string *error) {
    std::lock_guard<std::mutex> lock(g_fake_cd_mutex);
    if (g_fake_cd_enabled.load()) return true;

    uintptr_t data = 0;
    uintptr_t size = 0;
    if (!MapApkCentralDirectory(apk_path, &data, &size, error)) {
        return false;
    }
    if (!InstallCopyHook(error)) return false;

    g_fake_cd_data.store(data);
    g_fake_cd_size.store(size);
    g_fake_cd_enabled.store(true);
    LOGI("fake-cd: cd=%p size=%#zx", reinterpret_cast<void *>(data), size);
    return true;
}

bool InstallVmDump(const char *directory, std::string *error) {
    if (directory == nullptr || directory[0] == '\0') {
        *error = "empty dump directory";
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(g_vm_dump_mutex);
        g_vm_dump_path = directory;
        g_vm_dump_enabled.store(true);
    }
    if (!InstallCopyHook(error)) {
        g_vm_dump_enabled.store(false);
        return false;
    }
    LOGI("vm-dump: directory=%s", directory);
    return true;
}

// process_vm_readv on our own process turns a bad VM pointer into EFAULT
// instead of a fault, which matters while tracing a decoy path that is
// deliberately feeding the resolver garbage.
bool SafeReadMemory(uintptr_t address, void *out, size_t size) {
    if (address == 0 || out == nullptr || size == 0) return false;
    struct iovec local{out, size};
    struct iovec remote{reinterpret_cast<void *>(address), size};
    for (;;) {
        const ssize_t got =
            process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
        if (got == static_cast<ssize_t>(size)) return true;
        if (got < 0 && errno == EINTR) continue;
        return false;
    }
}

// Debug-only: identify the code that mutates klB's image before the first
// dispatcher entry. The dispatcher diff proves 23 scattered ranges change in
// that window, but the visible prologue contains no image stores, so the
// writer must be a helper. Protecting the image's first page read-only turns
// the first such store into a SIGSEGV; the handler logs pc/lr/frame pointers
// and makes the page writable again, so the store retries and the run
// continues. Only the first fault is reported: that is the writer.
std::atomic<bool> g_image_watch_armed{false};
std::atomic<bool> g_image_watch_installed{false};
uintptr_t g_image_watch_page = 0;
size_t g_image_watch_size = 0;

void ImageWatchHandler(int signo, siginfo_t *info, void *context) {
    auto *uc = static_cast<ucontext_t *>(context);
    // Android's arm64 heap pointers carry a top-byte tag while the kernel
    // reports the fault address with the tag stripped, so compare the low 56
    // bits on both sides.
    constexpr uintptr_t kAddressMask = 0x00ffffffffffffffULL;
    const uintptr_t address =
        reinterpret_cast<uintptr_t>(info != nullptr ? info->si_addr : nullptr) &
        kAddressMask;
    const uintptr_t page = g_image_watch_page & kAddressMask;
    const size_t size = g_image_watch_size;
    if (!g_image_watch_armed.load() || address < page ||
        address >= page + size) {
        // Not our watch. Put the previous disposition back and retry, which
        // gives a normal tombstone instead of looping inside the handler.
        signal(signo, SIG_DFL);
        return;
    }

    const uintptr_t base = g_pairip_base.load();
    const uintptr_t pc = static_cast<uintptr_t>(uc->uc_mcontext.pc);
    const uintptr_t lr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
    const uintptr_t fp = static_cast<uintptr_t>(uc->uc_mcontext.regs[29]);
    const uintptr_t sp = static_cast<uintptr_t>(uc->uc_mcontext.sp);
    LOGI("image-watch: write to %p pc=%p(+%#" PRIxPTR ") lr=%p(+%#" PRIxPTR
         ") sp=%p fp=%p",
         reinterpret_cast<void *>(address), reinterpret_cast<void *>(pc),
         pc >= base ? pc - base : 0, reinterpret_cast<void *>(lr),
         lr >= base ? lr - base : 0, reinterpret_cast<void *>(sp),
         reinterpret_cast<void *>(fp));
    LOGI("image-watch: regs x0=%#" PRIxPTR " x1=%#" PRIxPTR " x8=%#" PRIxPTR
         " x9=%#" PRIxPTR " x10=%#" PRIxPTR " x19=%#" PRIxPTR
         " x20=%#" PRIxPTR " x28=%#" PRIxPTR,
         static_cast<uintptr_t>(uc->uc_mcontext.regs[0]),
         static_cast<uintptr_t>(uc->uc_mcontext.regs[1]),
         static_cast<uintptr_t>(uc->uc_mcontext.regs[8]),
         static_cast<uintptr_t>(uc->uc_mcontext.regs[9]),
         static_cast<uintptr_t>(uc->uc_mcontext.regs[10]),
         static_cast<uintptr_t>(uc->uc_mcontext.regs[19]),
         static_cast<uintptr_t>(uc->uc_mcontext.regs[20]),
         static_cast<uintptr_t>(uc->uc_mcontext.regs[28]));

    // The handler has no usable frame pointer (its fp holds a dispatch word),
    // so scan the stack for return addresses into libpairipcore instead.
    for (int i = 0; i < 96; ++i) {
        uint64_t word = 0;
        if (!SafeReadMemory(sp + static_cast<uintptr_t>(i) * 8, &word,
                            sizeof(word))) {
            break;
        }
        if (word >= base && word < base + 0x100000) {
            LOGI("image-watch: stack[%#x] = +%#" PRIxPTR, i * 8,
                 static_cast<uintptr_t>(word) - base);
        }
    }

    mprotect(reinterpret_cast<void *>(g_image_watch_page), size,
             PROT_READ | PROT_WRITE);
    g_image_watch_armed.store(false);
}

void ArmImageWatch(uintptr_t image) {
    if (!g_image_watch_installed.exchange(true)) {
        struct sigaction action = {};
        action.sa_sigaction = ImageWatchHandler;
        action.sa_flags = SA_SIGINFO | SA_RESTART;
        sigemptyset(&action.sa_mask);
        struct sigaction previous = {};
        if (sigaction(SIGSEGV, &action, &previous) != 0) {
            LOGE("image-watch: sigaction failed: %s", std::strerror(errno));
            return;
        }
        LOGI("image-watch: handler installed (previous=%p flags=%#x)",
             reinterpret_cast<void *>(previous.sa_sigaction),
             static_cast<unsigned>(previous.sa_flags));
    }
    const uintptr_t page = image & ~static_cast<uintptr_t>(0xfff);
    if (mprotect(reinterpret_cast<void *>(page), 0x1000, PROT_READ) != 0) {
        LOGE("image-watch: mprotect failed: %s", std::strerror(errno));
        return;
    }
    g_image_watch_page = page;
    g_image_watch_size = 0x1000;
    g_image_watch_armed.store(true);
    LOGI("image-watch: armed page=%p (image=%p)",
         reinterpret_cast<void *>(page), reinterpret_cast<void *>(image));
}

void DisarmImageWatch(const char *reason) {
    if (!g_image_watch_armed.exchange(false)) return;
    mprotect(reinterpret_cast<void *>(g_image_watch_page), g_image_watch_size,
             PROT_READ | PROT_WRITE);
    LOGI("image-watch: disarmed (%s)", reason);
}

bool InstallNameTrace(std::string *error);

bool InstallClassTrace() {
    std::lock_guard<std::mutex> lock(g_class_trace_mutex);
    if (g_pairip_class_original != nullptr) return true;

    ModuleLookup lookup{"libpairipcore.so", 0};
    dl_iterate_phdr(FindModuleCallback, &lookup);
    if (lookup.base == 0) {
        LOGE("class-trace: libpairipcore.so is not mapped");
        return false;
    }
    g_pairip_base.store(lookup.base);

    auto *target = reinterpret_cast<void *>(lookup.base + 0x8c570);
    static const long page_size = sysconf(_SC_PAGESIZE);
    auto page = reinterpret_cast<uintptr_t>(target) &
                ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page),
                 static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGE("class-trace: mprotect failed");
    }

    const int result = DobbyHook(
        target, reinterpret_cast<void *>(&pairip_class_trace_entry),
        &g_pairip_class_original);
    LOGI("class-trace: hook rc=%d base=%p target=%p trampoline=%p", result,
         reinterpret_cast<void *>(lookup.base), target,
         g_pairip_class_original);
    if (result != 0 || g_pairip_class_original == nullptr) return false;

    g_class_trace_enabled.store(true);

    std::string name_error;
    if (!InstallNameTrace(&name_error)) {
        LOGE("name-trace: install failed: %s", name_error.c_str());
    }
    return true;
}

bool PatchCallSite(uintptr_t site, void *target, std::string *error) {
    const intptr_t delta = reinterpret_cast<intptr_t>(target) -
                           static_cast<intptr_t>(site);
    if (delta < -0x8000000 || delta > 0x7fffffc) {
        *error = "call target out of range";
        return false;
    }
    auto *insn = reinterpret_cast<uint32_t *>(site);
    *insn = 0x94000000u | ((static_cast<uint32_t>(delta) >> 2) & 0x03ffffffu);
    __builtin___clear_cache(reinterpret_cast<char *>(site),
                            reinterpret_cast<char *>(site + 4));
    return true;
}

// Debug-only: an absolute-jump island inside bl range of libpairipcore.
// libpairipshield is mapped hundreds of megabytes away from the game's split
// APK, so a direct bl from the ZIP reader cannot reach our shim.
uintptr_t AllocateJumpIsland(uintptr_t site) {
    static const size_t page_size = 0x1000;
    const uintptr_t page = site & ~static_cast<uintptr_t>(page_size - 1);
    for (int i = 1; i < 64; ++i) {
        const int directions[] = {-1, 1};
        for (int direction : directions) {
            const uintptr_t hint = page + static_cast<uintptr_t>(
                                              direction * i * 0x100000);
            void *mapped = mmap(
                reinterpret_cast<void *>(hint), page_size,
                PROT_READ | PROT_WRITE | PROT_EXEC,
                MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
            if (mapped == reinterpret_cast<void *>(hint)) return hint;
            if (mapped != MAP_FAILED) munmap(mapped, page_size);
        }
    }
    return 0;
}

// Writes [ldr x16, #8; br x16; .quad target] at [stub].
void WriteAbsoluteJump(uintptr_t stub, void *target) {
    auto *code = reinterpret_cast<uint32_t *>(stub);
    code[0] = 0x58000050u;  // ldr x16, #8
    code[1] = 0xd61f0200u;  // br x16
    *reinterpret_cast<void **>(stub + 8) = target;
    __builtin___clear_cache(reinterpret_cast<char *>(stub),
                            reinterpret_cast<char *>(stub + 16));
}

// Debug-only: replace the two memcmp calls in the ZIP reader's name lookup
// with the logging shim. They compare the hashed central-directory entry name
// against names the VM expects to find in the APK.
bool InstallNameTrace(std::string *error) {
    ModuleLookup lookup{"libpairipcore.so", 0};
    dl_iterate_phdr(FindModuleCallback, &lookup);
    if (lookup.base == 0) {
        *error = "libpairipcore.so is not mapped";
        return false;
    }

    static const long page_size = sysconf(_SC_PAGESIZE);
    const uintptr_t page = (lookup.base + 0x3e000) &
                           ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page),
                 static_cast<size_t>(page_size * 2),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        *error = "mprotect failed";
        return false;
    }

    const uintptr_t sites[] = {lookup.base + 0x3e4d0,
                               lookup.base + 0x3e544};
    const uintptr_t island = AllocateJumpIsland(sites[0]);
    if (island == 0) {
        *error = "no jump island within bl range";
        return false;
    }
    for (size_t i = 0; i < sizeof(sites) / sizeof(sites[0]); ++i) {
        const uintptr_t stub = island + i * 16;
        WriteAbsoluteJump(stub,
                          reinterpret_cast<void *>(&pairip_name_trace_entry));
        if (!PatchCallSite(sites[i], reinterpret_cast<void *>(stub), error)) {
            return false;
        }
    }
    LOGI("name-trace: patched %zu sites base=%p island=%p target=%p",
         sizeof(sites) / sizeof(sites[0]),
         reinterpret_cast<void *>(lookup.base),
         reinterpret_cast<void *>(island),
         reinterpret_cast<void *>(&pairip_name_trace_entry));
    return true;
}

}  // namespace

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_copy_original = nullptr;

// Debug-only: log every ExecuteProgram(name, args, argc) call before it runs.
// libunity's DT_INIT is the only static reference to the import, but the
// argument array it passes is the one thing that decides what klB sees.
using ExecuteProgramFn = jobject (*)(const char *name, jobject *args,
                                     uintptr_t argc);
ExecuteProgramFn g_execute_program_original = nullptr;
std::mutex g_execute_program_mutex;

jobject ExecuteProgramTrace(const char *name, jobject *args, uintptr_t argc) {
    const uintptr_t caller =
        reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    LOGI("exec-prog: name=%s args=%p argc=%zu caller=%p",
         name != nullptr ? name : "(null)", reinterpret_cast<void *>(args),
         static_cast<size_t>(argc), reinterpret_cast<void *>(caller));
    if (args != nullptr) {
        for (uintptr_t i = 0; i < argc && i < 4; ++i) {
            LOGI("exec-prog: arg[%zu]=%p", static_cast<size_t>(i),
                 reinterpret_cast<void *>(args[i]));
        }
    }
    if (g_execute_program_original == nullptr) return nullptr;
    return g_execute_program_original(name, args, argc);
}

bool InstallExecuteProgramHook() {
    std::lock_guard<std::mutex> lock(g_execute_program_mutex);
    if (g_execute_program_original != nullptr) return true;

    ModuleLookup lookup{"libpairipcore.so", 0};
    dl_iterate_phdr(FindModuleCallback, &lookup);
    if (lookup.base == 0) return false;

    auto *target = reinterpret_cast<void *>(lookup.base + 0x6b3d0);
    static const long page_size = sysconf(_SC_PAGESIZE);
    auto page = reinterpret_cast<uintptr_t>(target) &
                ~static_cast<uintptr_t>(page_size - 1);
    if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return false;
    }
    const int result = DobbyHook(target,
                                 reinterpret_cast<void *>(&ExecuteProgramTrace),
                                 reinterpret_cast<void **>(
                                     &g_execute_program_original));
    LOGI("exec-prog: hook rc=%d target=%p trampoline=%p", result, target,
         reinterpret_cast<void *>(g_execute_program_original));
    return result == 0 && g_execute_program_original != nullptr;
}

// executeVM's Java-call arguments are still in its frame at the copy point:
// x3 (the jobjectArray) was spilled to [sp+0x48] on entry and the byte[]
// length to [sp+0x60]; x22 holds the JNIEnv. Reading them here shows what
// VMRunner.invoke handed to executeVM before the VM starts (debug-only).
void LogExecuteVmArgs(uint64_t *regs) {
    static std::atomic<int> dumps{0};
    const int entry = dumps.fetch_add(1);
    if (entry >= 8) return;

    const uintptr_t frame = reinterpret_cast<uintptr_t>(regs) + 0x110;
    auto *env = reinterpret_cast<JNIEnv *>(regs[22]);
    jobjectArray args = nullptr;
    uint32_t image_length = 0;
    SafeReadMemory(frame + 0x48, &args, sizeof(args));
    SafeReadMemory(frame + 0x60, &image_length, sizeof(image_length));

    if (env == nullptr) {
        LOGI("vm-args: #%d len=%#x env=null args=%p", entry, image_length,
             reinterpret_cast<void *>(args));
        return;
    }
    if (args == nullptr) {
        LOGI("vm-args: #%d len=%#x args=null", entry, image_length);
        return;
    }
    if (env->PushLocalFrame(32) != 0) {
        env->ExceptionClear();
        return;
    }
    const jsize count = env->GetArrayLength(args);
    LOGI("vm-args: #%d len=%#x count=%d", entry, image_length,
         static_cast<int>(count));

    jclass class_class = env->FindClass("java/lang/Class");
    jmethodID class_get_name =
        class_class != nullptr
            ? env->GetMethodID(class_class, "getName", "()Ljava/lang/String;")
            : nullptr;
    for (jsize i = 0; i < count && i < 4; ++i) {
        jobject element = env->GetObjectArrayElement(args, i);
        if (element == nullptr) {
            LOGI("vm-args: [%d] null", static_cast<int>(i));
            continue;
        }
        jclass element_class = env->GetObjectClass(element);
        std::string class_name = "?";
        if (element_class != nullptr && class_get_name != nullptr) {
            auto name = static_cast<jstring>(
                env->CallObjectMethod(element_class, class_get_name));
            if (name != nullptr) {
                class_name = JStringToUtf8(env, name);
                env->DeleteLocalRef(name);
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (class_name == "java.lang.Long" && element_class != nullptr) {
            jmethodID long_value =
                env->GetMethodID(element_class, "longValue", "()J");
            if (long_value != nullptr) {
                const jlong value = env->CallLongMethod(element, long_value);
                LOGI("vm-args: [%d] %s=%#" PRIx64 " (%" PRId64 ")",
                     static_cast<int>(i), class_name.c_str(),
                     static_cast<uint64_t>(value),
                     static_cast<int64_t>(value));
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        } else {
            std::string text = class_name;
            if (element_class != nullptr) {
                jmethodID to_string = env->GetMethodID(
                    element_class, "toString", "()Ljava/lang/String;");
                if (to_string != nullptr) {
                    auto value = static_cast<jstring>(
                        env->CallObjectMethod(element, to_string));
                    if (value != nullptr) {
                        text = JStringToUtf8(env, value);
                        env->DeleteLocalRef(value);
                    }
                }
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGI("vm-args: [%d] %s text=%s", static_cast<int>(i),
                 class_name.c_str(), text.c_str());
        }
        if (element_class != nullptr) env->DeleteLocalRef(element_class);
    }
    env->PopLocalFrame(nullptr);
}

// Called from the executeVM copy-point shim with the saved register file.
// regs[20] is x20, the writable VM image. Only klB carries this exact
// unresolved ZIP view; leaving YTw and later programs untouched keeps the
// experiment independent of the startup program.
extern "C" __attribute__((visibility("hidden")))
void pairip_copy_patch_c(uint64_t *regs) {
    if (regs == nullptr) return;
    const uintptr_t image = static_cast<uintptr_t>(regs[20]);
    if (image == 0) return;

    if (g_vm_dump_enabled.load()) LogExecuteVmArgs(regs);

    static std::atomic<bool> logged_base{false};
    if (!logged_base.exchange(true)) {
        LOGI("copy-hook: vm image=%p", reinterpret_cast<void *>(image));
    }

    auto *bytes = reinterpret_cast<uint8_t *>(image);
    if (bytes[0] != 0 || bytes[1] != 'I' || bytes[2] != 'A' ||
        bytes[3] != 'P' || bytes[4] != 2) {
        return;
    }

    // klB is the 282603-byte image carrying this exact ZIP-view literal. The
    // marker also keeps the dump to the program under investigation; YTw and
    // the license programs have different images.
    uint64_t old_data = 0;
    uint64_t old_end = 0;
    std::memcpy(&old_data, bytes + 0x3f874, sizeof(old_data));
    std::memcpy(&old_end, bytes + 0x1ba5c, sizeof(old_end));
    if (old_data != 0xbbb9eb6daed20ed0ULL ||
        old_end != 0x015370f87525d57bULL) {
        return;
    }
    g_klb_active.store(true);

    if (g_vm_dump_enabled.load()) {
        static std::atomic<int> dump_count{0};
        if (dump_count.fetch_add(1) == 0) {
            std::string directory;
            {
                std::lock_guard<std::mutex> lock(g_vm_dump_mutex);
                directory = g_vm_dump_path;
            }
            const std::string path = directory + "/klB-image.bin";
            FILE *file = std::fopen(path.c_str(), "wb");
            if (file != nullptr) {
                static const size_t kImageSize = 0x44feb;
                const size_t written =
                    std::fwrite(bytes, 1, kImageSize, file);
                std::fclose(file);
                LOGI("vm-dump: wrote %zu bytes to %s (marker @ +0x3f874)",
                     written, path.c_str());
            } else {
                LOGE("vm-dump: open %s failed: %s", path.c_str(),
                     std::strerror(errno));
            }

            // Capture the maps klB is about to inspect. The process has
            // already mapped libunity/libil2cpp here, so this shows the
            // module path klB will derive from argv[0].
            const std::string maps = ReadWholeFile("/proc/self/maps");
            const std::string maps_path = directory + "/klB-maps.txt";
            FILE *maps_file = std::fopen(maps_path.c_str(), "wb");
            if (maps_file != nullptr) {
                const size_t written =
                    std::fwrite(maps.data(), 1, maps.size(), maps_file);
                std::fclose(maps_file);
                LOGI("vm-dump: wrote %zu bytes of maps to %s", written,
                     maps_path.c_str());
            }
        }
    }

    // Start the syscall stream at klB's first instruction so the ring is not
    // diluted by libmain/Fusion setup calls.
    if (g_syscall_trace_enabled.load()) {
        g_syscall_ring_pos.store(0, std::memory_order_relaxed);
    }

    // Baseline for the write attribution, taken before the interpreter (and
    // before the fake-CD diagnostic) modifies the image.
    if (g_vm_dump_enabled.load()) {
        std::lock_guard<std::mutex> lock(g_vm_diff_mutex);
        static const size_t kImageSize = 0x44feb;
        g_vm_diff_image = image;
        g_vm_diff_last_pos = g_vm_ring_pos.load(std::memory_order_relaxed);
        g_vm_diff_snapshot.assign(bytes, bytes + kImageSize);
        g_vm_write_pos.store(0, std::memory_order_relaxed);
        LOGI("trace: image-diff baseline=%p pos=%u", reinterpret_cast<void *>(image),
             g_vm_diff_last_pos);
        static std::atomic<bool> watch_once{false};
        if (!watch_once.exchange(true)) ArmImageWatch(image);
    }

    if (!g_fake_cd_enabled.load()) return;

    const uint64_t data = g_fake_cd_data.load();
    const uint64_t size = g_fake_cd_size.load();
    std::memcpy(bytes + 0x3f874, &data, sizeof(data));
    std::memcpy(bytes + 0x1ba5c, &size, sizeof(size));

    static std::atomic<int> log_count{0};
    if (log_count.fetch_add(1) < 8) {
        LOGI("fake-cd: klB view base=%p size=%#" PRIx64
             " (was %016" PRIx64 " / %016" PRIx64 ")",
             reinterpret_cast<void *>(data), size, old_data, old_end);
    }
}

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_class_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void pairip_name_trace_c(const char *expected, const char *actual,
                         size_t length) {
    if (expected == nullptr || actual == nullptr) return;
    static thread_local bool in_trace = false;
    if (in_trace) return;
    in_trace = true;

    static std::atomic<int> log_count{0};
    const int entry = log_count.fetch_add(1);
    if (entry < 64 && length < 256) {
        char expected_text[97] = {};
        char actual_text[97] = {};
        const size_t cap = length < 96 ? length : 96;
        SafeReadMemory(reinterpret_cast<uintptr_t>(expected), expected_text,
                       cap);
        SafeReadMemory(reinterpret_cast<uintptr_t>(actual), actual_text, cap);
        for (size_t i = 0; i < cap; ++i) {
            const unsigned char value =
                static_cast<unsigned char>(expected_text[i]);
            if (value < 0x20 || value >= 0x7f) expected_text[i] = '.';
            const unsigned char other =
                static_cast<unsigned char>(actual_text[i]);
            if (other < 0x20 || other >= 0x7f) actual_text[i] = '.';
        }
        LOGI("name-trace: #%d len=%zu expected=%s actual=%s", entry, length,
             expected_text, actual_text);
    }
    in_trace = false;
}

// Called from the class-resolver shim with the saved register file.
// regs[0] is the descriptor, regs[30] the interpreter's return address.
// The decoder is the one at +0x6774c: the entry at [table+index] encodes two
// 32-bit offsets; each offset is reduced modulo the per-table key, and the
// two 16-bit lengths at those offsets XOR to the string length. The string
// bytes are table[data+i] XOR table[key+i].
extern "C" __attribute__((visibility("hidden")))
void pairip_class_trace_c(uint64_t *regs) {
    if (regs == nullptr || !g_class_trace_enabled.load()) return;
    static std::atomic<int> log_count{0};
    const int entry = log_count.fetch_add(1);
    if (entry >= 300) return;

    const uintptr_t desc = static_cast<uintptr_t>(regs[0]);
    const uintptr_t raw_caller = static_cast<uintptr_t>(regs[31]);
    // Return addresses are PAC-signed on Android 17; the virtual address is
    // in the low 48 bits.
    const uintptr_t caller = raw_caller & 0x0000ffffffffffffULL;
    const uintptr_t base = g_pairip_base.load();
    if (desc == 0) return;

    uintptr_t table_desc = 0;
    uintptr_t cached = 0;
    uint32_t index = 0;
    SafeReadMemory(desc, &table_desc, sizeof(table_desc));
    SafeReadMemory(desc + 0x0c, &index, sizeof(index));
    SafeReadMemory(desc + 0x10, &cached, sizeof(cached));
    if (cached != 0) return;

    uintptr_t table = 0;
    uint32_t key = 0;
    if (table_desc != 0) {
        SafeReadMemory(table_desc, &table, sizeof(table));
        SafeReadMemory(table_desc + 0x08, &key, sizeof(key));
    }

    uint32_t encoded = 0;
    uint32_t data_offset = 0;
    uint32_t key_offset = 0;
    uint32_t length = 0;
    char decoded[256] = {};
    bool decoded_ok = false;
    if (table != 0 && key != 0 &&
        SafeReadMemory(table + index, &encoded, sizeof(encoded))) {
        const uint32_t scrambled = encoded ^ ~key;
        const uint32_t reduced = scrambled % key;
        uint32_t first = 0;
        uint32_t second = 0;
        if (SafeReadMemory(table + reduced, &first, sizeof(first)) &&
            SafeReadMemory(table + reduced + 4, &second, sizeof(second))) {
            first ^= ~key;
            second ^= ~key;
            data_offset = first % key;
            key_offset = second % key;
            uint16_t data_length = 0;
            uint16_t key_length = 0;
            if (SafeReadMemory(table + data_offset, &data_length,
                               sizeof(data_length)) &&
                SafeReadMemory(table + key_offset, &key_length,
                               sizeof(key_length))) {
                length = static_cast<uint32_t>(data_length ^ key_length);
                const size_t cap =
                    length < sizeof(decoded) - 1 ? length : sizeof(decoded) - 1;
                std::vector<uint8_t> data(cap);
                std::vector<uint8_t> key_bytes(cap);
                if (SafeReadMemory(table + data_offset, data.data(), cap) &&
                    SafeReadMemory(table + key_offset, key_bytes.data(), cap)) {
                    for (size_t i = 0; i < cap; ++i) {
                        const uint8_t value = data[i] ^ key_bytes[i];
                        if (value >= 0x20 && value < 0x7f && value != '\\') {
                            decoded[i] = static_cast<char>(value);
                        } else if (value == '\\') {
                            decoded[i] = '/';
                        } else {
                            decoded[i] = '.';
                        }
                    }
                    decoded[cap] = '\0';
                    decoded_ok = true;
                }
            }
        }
    }

    uint32_t words[8] = {};
    SafeReadMemory(desc, words, sizeof(words));
    LOGI("class-trace: #%d caller=+%#lx vm=%#x/%#x raw=%#lx desc=%p "
         "words=%08x,%08x,%08x,"
         "%08x,%08x,%08x table_desc=%p table=%p key=%#x idx=%#x enc=%#x "
         "data=%#x keyoff=%#x len=%u ok=%d text=%s",
         entry, caller >= base ? static_cast<unsigned long>(caller - base) : 0,
         t_last_vm_fetch, t_last_vm_op,
         static_cast<unsigned long>(raw_caller),
         reinterpret_cast<void *>(desc), words[0], words[1], words[2],
         words[3], words[4], words[5],
         reinterpret_cast<void *>(table_desc),
         reinterpret_cast<void *>(table), key, index, encoded, data_offset,
         key_offset, length, decoded_ok ? 1 : 0,
         decoded_ok ? decoded : "");
    if (length > 256) {
        LOGI("class-trace: #%d regs x8=%#lx x9=%#lx x10=%#lx x19=%#lx "
             "x20=%#lx x21=%#lx x22=%#lx x24=%#lx x25=%#lx x26=%#lx "
             "x27=%#lx x28=%#lx x29=%#lx",
             entry, static_cast<unsigned long>(regs[8]),
             static_cast<unsigned long>(regs[9]),
             static_cast<unsigned long>(regs[10]),
             static_cast<unsigned long>(regs[19]),
             static_cast<unsigned long>(regs[20]),
             static_cast<unsigned long>(regs[21]),
             static_cast<unsigned long>(regs[22]),
             static_cast<unsigned long>(regs[24]),
             static_cast<unsigned long>(regs[25]),
             static_cast<unsigned long>(regs[26]),
             static_cast<unsigned long>(regs[27]),
             static_cast<unsigned long>(regs[28]),
             static_cast<unsigned long>(regs[29]));
    }
}

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_append_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_syscall_original = nullptr;

constexpr uint32_t kInvalidSyscallSlot = 0xffffffffu;

// Copy the path argument of the few syscalls klB uses to open files. The
// pointer is only read for the duration of the entry; the trace stores a copy
// because the VM frees its own buffer before the ring is dumped.
uint32_t TraceSyscallEnter(long number, long a1, long a2, long a3) {
    if (!g_syscall_trace_enabled.load()) return kInvalidSyscallSlot;
    const uint32_t pos = g_syscall_ring_pos.fetch_add(1, std::memory_order_relaxed);
    SyscallRingEntry &entry = g_syscall_ring[pos % kSyscallRingSize];
    std::memset(&entry, 0, sizeof(entry));
    entry.number = number;
    entry.args[0] = a1;
    entry.args[1] = a2;
    entry.args[2] = a3;
    entry.result = INT64_MIN;
    entry.caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    entry.vm_fetch = t_last_vm_fetch;
    entry.vm_op = t_last_vm_op;

    const char *path = nullptr;
    switch (number) {
        case 48:   // faccessat
        case 56:   // openat
        case 78:   // readlinkat
        case 79:   // newfstatat
        case 221:  // execve
            path = reinterpret_cast<const char *>(a2);
            break;
        case 279:  // memfd_create
            path = reinterpret_cast<const char *>(a1);
            break;
        default:
            break;
    }
    if (path != nullptr) {
        SafeReadMemory(reinterpret_cast<uintptr_t>(path), entry.path,
                       sizeof(entry.path) - 1);
        if (number == 56 && std::strstr(entry.path, "/proc/self/maps") !=
                                nullptr &&
            t_last_vm_op == 0x6d && g_klb_active.load()) {
            g_maps_scan_log.store(0);
            g_maps_scan_trace.store(true);
        }
    }
    return pos;
}

void TraceSyscallLeave(uint32_t slot, long result) {
    if (slot == kInvalidSyscallSlot) return;
    SyscallRingEntry &entry = g_syscall_ring[slot % kSyscallRingSize];
    entry.result = result;
    if (entry.number == 63 && result > 0) {
        SafeReadMemory(static_cast<uintptr_t>(entry.args[1]), entry.sample,
                       sizeof(entry.sample));
    }
}

// Entry point for the libpairipcore raw-syscall wrapper (base+0x17430).
// The wrapper's ABI is syscall(number, a1..a6), so the C signature maps
// directly onto x0..x6. Everything except the procfs read path is forwarded
// unchanged; in particular the anti-debug syscalls keep their exact
// semantics and errno-free negative return convention.
extern "C" __attribute__((visibility("hidden")))
long pairip_syscall_hook(long number, long a1, long a2, long a3, long a4,
                        long a5, long a6) {
    auto original = reinterpret_cast<long (*)(
        long, long, long, long, long, long, long)>(g_pairip_syscall_original);
    if (original == nullptr) return -ENOSYS;

    const uint32_t trace_slot = TraceSyscallEnter(number, a1, a2, a3);
    if (!g_proc_sanitize_enabled.load()) {
        const long result = original(number, a1, a2, a3, a4, a5, a6);
        TraceSyscallLeave(trace_slot, result);
        return result;
    }

    switch (number) {
        case 56: {  // openat
            const char *path = reinterpret_cast<const char *>(a2);
            const long fd = original(number, a1, a2, a3, a4, a5, a6);
            if (path != nullptr) {
                LOGI("proc: openat path=%.200s flags=%#lx fd=%ld",
                     path, static_cast<unsigned long>(a3), fd);
            }
            if (fd >= 0 && path != nullptr) {
                std::lock_guard<std::mutex> lock(g_proc_mutex);
                g_open_paths[static_cast<int>(fd)] = path;
            }
            if (fd >= 0 && IsProcMapsOrStatus(path)) {
                std::string content = ReadWholeFile(path);
                if (!content.empty()) {
                    content = SanitizeProcContent(path, std::move(content));
                    MaybeDumpProcContent(path, content);
                    const size_t bytes = content.size();
                    std::lock_guard<std::mutex> lock(g_proc_mutex);
                    g_proc_files[static_cast<int>(fd)] =
                        ProcFileState{std::move(content), 0};
                    LOGI("proc: sanitized %s fd=%ld bytes=%zu", path, fd, bytes);
                }
            }
            TraceSyscallLeave(trace_slot, fd);
            return fd;
        }
        case 61: {  // getdents64
            {
                std::lock_guard<std::mutex> lock(g_proc_mutex);
                auto iterator = g_open_paths.find(static_cast<int>(a1));
                LOGI("proc: getdents64 fd=%ld path=%s", a1,
                     iterator == g_open_paths.end()
                         ? "?"
                         : iterator->second.c_str());
            }
            break;
        }
        case 63: {  // read
            std::lock_guard<std::mutex> lock(g_proc_mutex);
            auto iterator = g_proc_files.find(static_cast<int>(a1));
            if (iterator == g_proc_files.end()) break;
            ProcFileState &state = iterator->second;
            if (state.offset >= state.content.size()) {
                TraceSyscallLeave(trace_slot, 0);
                return 0;
            }
            size_t count = static_cast<size_t>(a3);
            const size_t available = state.content.size() - state.offset;
            if (count > available) count = available;
            if (count == 0) {
                TraceSyscallLeave(trace_slot, 0);
                return 0;
            }
            std::memcpy(reinterpret_cast<void *>(a2),
                        state.content.data() + state.offset, count);
            state.offset += count;
            TraceSyscallLeave(trace_slot, static_cast<long>(count));
            return static_cast<long>(count);
        }
        case 62: {  // lseek
            std::lock_guard<std::mutex> lock(g_proc_mutex);
            auto iterator = g_proc_files.find(static_cast<int>(a1));
            if (iterator == g_proc_files.end()) break;
            ProcFileState &state = iterator->second;
            const long size = static_cast<long>(state.content.size());
            long position = 0;
            if (a3 == SEEK_SET) {
                position = a2;
            } else if (a3 == SEEK_CUR) {
                position = static_cast<long>(state.offset) + a2;
            } else if (a3 == SEEK_END) {
                position = size + a2;
            } else {
                TraceSyscallLeave(trace_slot, -EINVAL);
                return -EINVAL;
            }
            if (position < 0) {
                TraceSyscallLeave(trace_slot, -EINVAL);
                return -EINVAL;
            }
            state.offset = static_cast<size_t>(position);
            TraceSyscallLeave(trace_slot, position);
            return position;
        }
        case 57: {  // close
            {
                std::lock_guard<std::mutex> lock(g_proc_mutex);
                g_proc_files.erase(static_cast<int>(a1));
                g_open_paths.erase(static_cast<int>(a1));
            }
            break;
        }
        default:
            break;
    }

    const long result = original(number, a1, a2, a3, a4, a5, a6);
    TraceSyscallLeave(trace_slot, result);
    return result;
}

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_dispatch_original = nullptr;

// One read of executeVM's frame covers the image pointer, the length/fetch
// cursor pair and the operand cursor. Reading them directly after the
// process_vm_readv probe keeps the per-instruction cost to a single syscall.
extern "C" __attribute__((visibility("hidden")))
void pairip_dispatch_trace_c(uint64_t *regs) {
    if (regs == nullptr || !g_vm_trace_enabled.load()) return;

    const uintptr_t state = static_cast<uintptr_t>(regs[0]);
    if (state == 0) return;

    // Diagnostics: the dispatcher is shared by every VM program, so show the
    // first calls' raw frame words until the klB filter is confirmed.
    static std::atomic<uint32_t> g_dispatch_calls{0};
    const uint32_t call_index = g_dispatch_calls.fetch_add(1);
    if (call_index < 8) {
        uint64_t slot = 0;
        uint64_t lenpc = 0;
        uint64_t cursor_slot = 0;
        uint64_t cursor_value = 0;
        SafeReadMemory(state - 0x48, &slot, sizeof(slot));
        SafeReadMemory(state - 0x40, &lenpc, sizeof(lenpc));
        SafeReadMemory(state + 0x88, &cursor_slot, sizeof(cursor_slot));
        SafeReadMemory(state + 0x90, &cursor_value, sizeof(cursor_value));
        LOGI("trace: dispatch[%u] state=%p slot=%016" PRIx64
             " lenpc=%016" PRIx64 " s88=%016" PRIx64 " s90=%016" PRIx64
             " op=%#x x1=%016" PRIx64,
             call_index, reinterpret_cast<void *>(state), slot, lenpc,
             cursor_slot, cursor_value,
             static_cast<unsigned>(regs[1] & 0xffffu), regs[1]);
    }

    uint8_t head[0xe0] = {};
    if (!SafeReadMemory(state - 0x48, head, sizeof(head))) return;
    uint64_t image_slot = 0;
    uint32_t length = 0;
    uint32_t fetch_pc = 0;
    uint32_t cursor = 0;
    std::memcpy(&image_slot, head, sizeof(image_slot));
    std::memcpy(&length, head + 0x08, sizeof(length));
    std::memcpy(&fetch_pc, head + 0x0c, sizeof(fetch_pc));
    std::memcpy(&cursor, head + 0xd8, sizeof(cursor));
    if (length != kKlBImageLength) return;

    // [state-0x48] is the image pointer itself; state+0x88 points at that slot.
    const uintptr_t image = static_cast<uintptr_t>(image_slot);
    if (image == 0) return;
    uint32_t magic = 0;
    if (!SafeReadMemory(image, &magic, sizeof(magic))) return;
    if (magic != 0x50414900u) return;  // "\0IAP"

    // The write watch only covers the window before the interpreter starts.
    if (g_image_watch_armed.load()) DisarmImageWatch("first dispatch");

    const uint32_t pos = g_vm_ring_pos.fetch_add(1, std::memory_order_relaxed);
    VmRingEntry &entry = g_vm_ring[pos % kVmRingSize];
    entry.fetch_pc = fetch_pc;
    entry.cursor = cursor;
    entry.opcode = static_cast<uint32_t>(regs[1]) & 0xffffu;
    entry.reserved = 0;
    t_last_vm_fetch = fetch_pc;
    t_last_vm_op = entry.opcode;

    // Attribute image mutations to the instruction that produced them. The
    // dispatcher is entered before the handler runs, so a difference seen at
    // entry N was written by entry N-1.
    if (g_vm_dump_enabled.load() && g_vm_diff_image == image &&
        g_vm_diff_snapshot.size() == kKlBImageLength) {
        std::lock_guard<std::mutex> lock(g_vm_diff_mutex);
        const auto *live = reinterpret_cast<const uint8_t *>(image);
        uint8_t *snap = g_vm_diff_snapshot.data();
        const size_t size = g_vm_diff_snapshot.size();
        size_t i = 0;
        while (i < size) {
            if (live[i] == snap[i]) {
                ++i;
                continue;
            }
            const size_t start = i;
            while (i < size && live[i] != snap[i]) ++i;
            const size_t length = i - start;
            uint64_t old_value = 0;
            uint64_t new_value = 0;
            const size_t value_size = length < 8 ? length : 8;
            std::memcpy(&old_value, snap + start, value_size);
            std::memcpy(&new_value, live + start, value_size);

            const uint32_t write_pos =
                g_vm_write_pos.fetch_add(1, std::memory_order_relaxed);
            if (write_pos < kVmWriteRingSize) {
                VmWriteEntry &write = g_vm_write_ring[write_pos];
                write.offset = static_cast<uint32_t>(start);
                write.length = static_cast<uint32_t>(length);
                write.old_value = old_value;
                write.new_value = new_value;
                write.prior_index =
                    pos > g_vm_diff_last_pos ? pos - 1 : 0xffffffffu;
                write.reserved = 0;
            }

            if (g_vm_diff_ranges.fetch_add(1, std::memory_order_relaxed) <
                16) {
                LOGI("trace: image-write off=%#zx len=%zu old=%016" PRIx64
                     " new=%016" PRIx64 " by=%d",
                     start, length, old_value, new_value,
                     pos > g_vm_diff_last_pos
                         ? static_cast<int>(pos - 1)
                         : -1);
            }
            std::memcpy(snap + start, live + start, length);
        }
    }
}

void DumpSyscallRing() {
    if (!g_syscall_trace_enabled.load()) return;
    const uintptr_t base = g_pairip_base.load();
    const uint32_t pos = g_syscall_ring_pos.load(std::memory_order_relaxed);
    const uint32_t available = pos < kSyscallRingSize ? pos : kSyscallRingSize;
    const uint32_t count = available < 256 ? available : 256;
    LOGI("trace: syscall ring pos=%u dumping last %u", pos, count);
    for (uint32_t i = count; i > 0; --i) {
        const uint32_t index = pos - i;
        const SyscallRingEntry &entry = g_syscall_ring[index % kSyscallRingSize];
        LOGI("trace: syscall[%u] n=%" PRId64 " a=%" PRId64 ",%" PRId64
             ",%" PRId64 " ret=%" PRId64 " lr=%#lx vm=%#x/%#x path=%s "
             "head=%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x"
             "%02x%02x%02x%02x",
             index, entry.number, entry.args[0], entry.args[1], entry.args[2],
             entry.result,
             entry.caller >= base ? static_cast<unsigned long>(
                                        entry.caller - base)
                                  : 0UL,
             entry.vm_fetch, entry.vm_op, entry.path, entry.sample[0],
             entry.sample[1], entry.sample[2],
             entry.sample[3], entry.sample[4], entry.sample[5],
             entry.sample[6], entry.sample[7], entry.sample[8],
             entry.sample[9], entry.sample[10], entry.sample[11],
             entry.sample[12], entry.sample[13], entry.sample[14],
             entry.sample[15]);
    }
}

// Dump the most recent klB instructions. Called from the append hook when the
// reader is about to read from an unmapped base, i.e. immediately before the
// crash that the trace is meant to explain.
void DumpVmRing() {
    DumpSyscallRing();
    const uint32_t pos = g_vm_ring_pos.load(std::memory_order_relaxed);
    const uint32_t available = pos < kVmRingSize ? pos : kVmRingSize;
    const uint32_t count = available < kVmRingSize ? available : kVmRingSize;
    LOGI("trace: vm ring pos=%u dumping last %u", pos, count);

    const uint32_t write_pos =
        g_vm_write_pos.load(std::memory_order_relaxed);
    const uint32_t write_count =
        write_pos < kVmWriteRingSize ? write_pos : kVmWriteRingSize;
    LOGI("trace: image write ring pos=%u dumping last %u", write_pos,
         write_count);
    for (uint32_t i = 0; i < write_count; ++i) {
        const VmWriteEntry &write = g_vm_write_ring[i];
        uint32_t fetch = 0;
        uint32_t opcode = 0;
        if (write.prior_index != 0xffffffffu) {
            const VmRingEntry &prior =
                g_vm_ring[write.prior_index % kVmRingSize];
            fetch = prior.fetch_pc;
            opcode = prior.opcode;
        }
        LOGI("trace: imagew[%u] off=%#x len=%u old=%016" PRIx64
             " new=%016" PRIx64 " by=%d fetch=%#x op=%#x",
             i, write.offset, write.length, write.old_value, write.new_value,
             write.prior_index == 0xffffffffu
                 ? -1
                 : static_cast<int>(write.prior_index),
             fetch, opcode);
    }

    for (uint32_t i = count; i > 0; --i) {
        const VmRingEntry &entry = g_vm_ring[(pos - i) % kVmRingSize];
        LOGI("trace: vminsn[%u] fetch=%#x cursor=%#x op=%#x", pos - i,
             entry.fetch_pc, entry.cursor, entry.opcode);
    }
}

extern "C" __attribute__((visibility("hidden")))
void pairip_append_trace_c(uint64_t *regs) {
    if (regs == nullptr || !g_trace_enabled.load()) return;
    static thread_local bool in_trace = false;
    if (in_trace) return;
    in_trace = true;

    const uintptr_t base = g_pairip_base.load();
    const uintptr_t lr = static_cast<uintptr_t>(regs[31]);
    const uintptr_t src = static_cast<uintptr_t>(regs[0]);
    const uintptr_t count = static_cast<uintptr_t>(regs[1]);
    const uintptr_t dst = static_cast<uintptr_t>(regs[8]);
    const uintptr_t caller_sp = reinterpret_cast<uintptr_t>(regs) + 0x110;

    // The helper reads a string view from the object in x0:
    //   [0] tag/vtable, [8] cursor, [0x10] base pointer, [0x18] size.
    // Only a bad base near a large read is interesting; logging every
    // append call floods the trace and perturbs klB.
    uint64_t fields[4] = {};
    const bool fields_ok = SafeReadMemory(src, fields, sizeof(fields));
    uint8_t probe = 0;
    const bool base_mapped =
        fields_ok && SafeReadMemory(static_cast<uintptr_t>(fields[2]), &probe,
                                    sizeof(probe));
    const bool interesting =
        fields_ok && (count >= 0x20 || !base_mapped) && count <= 0x1000;

    static std::atomic<int> log_count{0};
    if (!interesting || log_count.fetch_add(1) >= 24) {
        in_trace = false;
        return;
    }

    LOGI("trace: append src=%p n=%zu dst=%p lr=%p off=%#zx fields=%016"
         PRIx64 ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64 " mapped=%d "
         "x16=%p x17=%p x19=%p x20=%p x24=%p x25=%p x26=%p x27=%p x28=%p",
         reinterpret_cast<void *>(src), static_cast<size_t>(count),
         reinterpret_cast<void *>(dst), reinterpret_cast<void *>(lr),
         lr >= base ? lr - base : 0, fields[0], fields[1], fields[2], fields[3],
         base_mapped ? 1 : 0,
         reinterpret_cast<void *>(regs[16]), reinterpret_cast<void *>(regs[17]),
         reinterpret_cast<void *>(regs[19]),
         reinterpret_cast<void *>(regs[20]), reinterpret_cast<void *>(regs[24]),
         reinterpret_cast<void *>(regs[25]), reinterpret_cast<void *>(regs[26]),
         reinterpret_cast<void *>(regs[27]), reinterpret_cast<void *>(regs[28]));

    // 0x3e030 loads the string view from [x16], indexed by two VM-derived
    // offsets. Log the context pointer and the head of that table so the
    // offsets can be recovered from the stored data/capacity values.
    uint64_t context_words[4] = {};
    const uintptr_t context = static_cast<uintptr_t>(regs[16]);
    if (context != 0 && SafeReadMemory(context, context_words,
                                       sizeof(context_words))) {
        LOGI("trace: vmctx=%p [0]=%p [8]=%p [10]=%p [18]=%p "
             "len=%#x pc=%#x",
             reinterpret_cast<void *>(context),
             reinterpret_cast<void *>(context_words[0]),
             reinterpret_cast<void *>(context_words[1]),
             reinterpret_cast<void *>(context_words[2]),
             reinterpret_cast<void *>(context_words[3]),
             static_cast<unsigned>(context_words[1] & 0xffffffffu),
             static_cast<unsigned>(context_words[1] >> 32));
    }

    // Capture the VM image as the program leaves it at the failing read: a
    // diff against the APK asset shows every scratch slot the program wrote
    // before it treated the two ZIP-view literals as pointers.
    if (!base_mapped && context != 0) {
        static std::atomic<int> dump_count{0};
        if (dump_count.fetch_add(1) == 0) {
            DumpVmRing();
            uint64_t image_words[1] = {};
            const uintptr_t image =
                SafeReadMemory(context, image_words, sizeof(image_words))
                    ? static_cast<uintptr_t>(image_words[0])
                    : 0;
            uint8_t magic[5] = {};
            if (image != 0 && SafeReadMemory(image, magic, sizeof(magic)) &&
                std::memcmp(magic, "\0IAP\x02", sizeof(magic)) == 0) {
                std::string directory;
                {
                    std::lock_guard<std::mutex> lock(g_vm_dump_mutex);
                    directory = g_vm_dump_path;
                }
                if (!directory.empty()) {
                    const std::string path =
                        directory + "/klB-image-at-crash.bin";
                    FILE *file = std::fopen(path.c_str(), "wb");
                    if (file != nullptr) {
                        constexpr size_t kImageSize = 0x44feb;
                        std::vector<uint8_t> copy(kImageSize);
                        const bool ok =
                            SafeReadMemory(image, copy.data(), copy.size());
                        const size_t written =
                            ok ? std::fwrite(copy.data(), 1, copy.size(), file)
                               : 0;
                        std::fclose(file);
                        LOGI("trace: image dump %s bytes=%zu ok=%d",
                             path.c_str(), written, ok ? 1 : 0);
                    }
                }
            }
        }
    }

    // Dump the interpreter frame around the stack object so the VM register
    // that supplied the bad base can be recovered.
    for (size_t offset = 0; offset < 0x800; offset += 0x40) {
        uint64_t row[8] = {};
        if (!SafeReadMemory(caller_sp + offset, row, sizeof(row))) break;
        LOGI("trace: vm[%#04zx] %016" PRIx64 " %016" PRIx64 " %016" PRIx64
             " %016" PRIx64 " %016" PRIx64 " %016" PRIx64 " %016" PRIx64
             " %016" PRIx64,
             offset, row[0], row[1], row[2], row[3], row[4], row[5], row[6],
             row[7]);
    }

    in_trace = false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallTrace(JNIEnv *, jclass) {
    return InstallPairIpTrace() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallClassTrace(
    JNIEnv *, jclass) {
    return InstallClassTrace() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallSyscallTrace(
    JNIEnv *, jclass) {
    return InstallSyscallTrace() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallProcSanitizer(
    JNIEnv *, jclass) {
    return InstallProcSanitizer() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallFakeCentralDirectory(
    JNIEnv *env, jclass, jstring apk_path) {
    const std::string path = JStringToUtf8(env, apk_path);
    std::string error;
    if (!InstallFakeCentralDirectory(path.c_str(), &error)) {
        return env->NewStringUTF(error.empty() ? "install failed" : error.c_str());
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallVmDump(
    JNIEnv *env, jclass, jstring directory) {
    const std::string path = JStringToUtf8(env, directory);
    std::string error;
    if (!InstallVmDump(path.c_str(), &error)) {
        return env->NewStringUTF(error.empty() ? "install failed" : error.c_str());
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeSetGameUid(
    JNIEnv *, jclass, jint uid) {
    g_game_uid.store(uid);
}

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

    InstallShieldHook();
}

/*
 * Loads a native library into an explicit ClassLoader's linker namespace.
 *
 * `System.load` picks the caller's class loader, which for this library is
 * always the launcher. `Runtime.nativeLoad` takes the caller class and its
 * class loader as arguments, so plain JNI (which is not subject to Java's
 * caller-sensitivity or hidden-API checks) can put libmain.so into the game's
 * namespace before UnityPlayer asks for it. Returns null on success, or the
 * platform's error string on failure.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeLoadIntoClassLoader(
    JNIEnv *env, jclass, jobject class_loader, jstring library_path) {
    if (class_loader == nullptr || library_path == nullptr) {
        return env->NewStringUTF("missing class loader or path");
    }

    jclass runtime_class = env->FindClass("java/lang/Runtime");
    if (runtime_class == nullptr) {
        env->ExceptionClear();
        return env->NewStringUTF("java/lang/Runtime not found");
    }

    // Resolve the game's NativeLoader first: it is the class whose linker
    // namespace must own libmain.so, and `Runtime.nativeLoad` takes it as the
    // caller-class argument on current platforms.
    jclass class_loader_class = env->FindClass("java/lang/ClassLoader");
    if (class_loader_class == nullptr) {
        env->ExceptionClear();
        return env->NewStringUTF("java/lang/ClassLoader not found");
    }
    jmethodID load_class = env->GetMethodID(
        class_loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (load_class == nullptr) {
        env->ExceptionClear();
        return env->NewStringUTF("ClassLoader.loadClass not found");
    }

    jstring name = env->NewStringUTF("com.unity3d.player.NativeLoader");
    auto native_loader_class = static_cast<jclass>(
        env->CallObjectMethod(class_loader, load_class, name));
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck() || native_loader_class == nullptr) {
        env->ExceptionClear();
        return env->NewStringUTF("could not resolve com.unity3d.player.NativeLoader");
    }

    auto exception_text = [env](const char *fallback) -> jstring {
        std::string message = fallback;
        jthrowable thrown = env->ExceptionOccurred();
        env->ExceptionClear();
        if (thrown != nullptr) {
            jclass throwable_class = env->FindClass("java/lang/Throwable");
            jmethodID to_string = throwable_class != nullptr
                ? env->GetMethodID(throwable_class, "toString",
                                   "()Ljava/lang/String;")
                : nullptr;
            if (to_string != nullptr) {
                auto text = static_cast<jstring>(
                    env->CallObjectMethod(thrown, to_string));
                if (text != nullptr && !env->ExceptionCheck()) {
                    message = JStringToUtf8(env, text);
                    env->DeleteLocalRef(text);
                }
                env->ExceptionClear();
            }
            env->DeleteLocalRef(thrown);
        }
        return env->NewStringUTF(message.c_str());
    };

    // Preferred path: the platform's own loading entry point. Android 14+
    // declares the third argument as the caller Class; older releases used a
    // library-search-path String. `Runtime.load0` in the framework shows the
    // current shape: nativeLoad(path, fromClass.getClassLoader(), fromClass).
    jmethodID native_load = env->GetStaticMethodID(
        runtime_class, "nativeLoad",
        "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;");
    if (native_load != nullptr) {
        auto result = static_cast<jstring>(env->CallStaticObjectMethod(
            runtime_class, native_load, library_path, class_loader,
            native_loader_class));
        if (env->ExceptionCheck()) {
            return exception_text("Runtime.nativeLoad threw");
        }
        return result;
    }
    env->ExceptionClear();

    native_load = env->GetStaticMethodID(
        runtime_class, "nativeLoad",
        "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/String;");
    if (native_load != nullptr) {
        auto result = static_cast<jstring>(env->CallStaticObjectMethod(
            runtime_class, native_load, library_path, class_loader, nullptr));
        if (env->ExceptionCheck()) {
            return exception_text("Runtime.nativeLoad threw");
        }
        return result;
    }
    env->ExceptionClear();

    // Fallback: `Runtime.load0(Class, String)` is an instance method that
    // derives the linker namespace from the given class and reports failure
    // as UnsatisfiedLinkError. Pass the game's NativeLoader so libmain lands
    // in the game's namespace.
    jmethodID load0 = env->GetMethodID(
        runtime_class, "load0", "(Ljava/lang/Class;Ljava/lang/String;)V");
    if (load0 == nullptr) {
        env->ExceptionClear();
        return env->NewStringUTF("Runtime.nativeLoad/load0 not available");
    }

    jmethodID get_runtime = env->GetStaticMethodID(
        runtime_class, "getRuntime", "()Ljava/lang/Runtime;");
    if (get_runtime == nullptr) {
        env->ExceptionClear();
        return env->NewStringUTF("Runtime.getRuntime not found");
    }
    jobject runtime = env->CallStaticObjectMethod(runtime_class, get_runtime);
    if (runtime == nullptr || env->ExceptionCheck()) {
        return exception_text("Runtime.getRuntime returned null");
    }

    env->CallVoidMethod(runtime, load0, native_loader_class, library_path);
    env->DeleteLocalRef(runtime);
    if (env->ExceptionCheck()) {
        return exception_text("Runtime.load0 threw");
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeSpoofProcessName(
    JNIEnv *env, jclass, jstring name) {
    const std::string target = JStringToUtf8(env, name);
    if (target.empty()) {
        return env->NewStringUTF("empty process name");
    }

    // Reuse the kernel's original argv[0] buffer. Android's app_process
    // allocates it on the process stack and keeps it writable; rewriting it
    // changes both /proc/self/cmdline and what getprogname() returns. Only
    // the task name (the comm truncated to 15 bytes) is writable through
    // prctl, so use the same suffix rule as AndroidRuntime::setArgv0.
    const char *prog = getprogname();
    void *full_symbol = dlsym(RTLD_DEFAULT, "__progname_full");
    if (full_symbol != nullptr) {
        const char *full = *reinterpret_cast<const char **>(full_symbol);
        if (full != nullptr) prog = full;
    }
    if (prog == nullptr) {
        return env->NewStringUTF("getprogname() returned null");
    }
    const size_t capacity = std::strlen(prog);
    if (target.size() > capacity) {
        return env->NewStringUTF(
            "argv0 buffer is shorter than the requested process name");
    }
    std::memcpy(const_cast<char *>(prog), target.c_str(), target.size() + 1);

    const char *comm = target.c_str();
    if (target.size() >= 16) {
        comm += target.size() - 15;
    }
    if (prctl(PR_SET_NAME, comm) != 0) {
        return env->NewStringUTF("prctl(PR_SET_NAME) failed");
    }
    LOGI("process name spoofed: comm=%s", comm);
    return nullptr;
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

    // The inline hook is installed by nativeConfigure, not here. This keeps a
    // bare `System.loadLibrary("pairipshield")` side-effect free when the same
    // library is used as the class-loader bridge for the PairIP preload.
    return JNI_VERSION_1_6;
}
