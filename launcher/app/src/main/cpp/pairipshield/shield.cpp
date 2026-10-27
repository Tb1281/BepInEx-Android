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
extern "C" __attribute__((visibility("hidden"))) void pairip_builtin_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_builtin_trace_c(
    uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_builtin_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_state_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_state_trace_c(
    uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_state_original;
extern "C" __attribute__((visibility("hidden"))) void
pairip_reader_source_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_reader_source_c(
    uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *
g_pairip_reader_source_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_hash_input_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_hash_input_c(
    uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_hash_input_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_hash_check_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_hash_check_c(
    uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_hash_check_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_router_load_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_router_load_c(
    uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_router_load_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_gate_len_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_gate_len_c(
    uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_gate_len_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_gate_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_gate_trace_c(
    uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_gate_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_fnv_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_fnv_trace_c(
    uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_fnv_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_class_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_class_trace_c(uint64_t *regs);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_class_original;
extern "C" __attribute__((visibility("hidden"))) void pairip_name_trace_entry();
extern "C" __attribute__((visibility("hidden"))) void pairip_name_trace_c(
    const char *expected, const char *actual, size_t length);
extern "C" __attribute__((visibility("hidden"))) void pairip_desc_trace(
    uint32_t *out, uint64_t *ctx, uint32_t operand_pc);
extern "C" __attribute__((visibility("hidden"))) uint32_t pairip_branch_trace(
    uint8_t *image, uint32_t *descriptor);
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_desc_original;
extern "C" __attribute__((visibility("hidden"))) void *g_pairip_branch_original;

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
std::atomic<bool> g_builtin_trace{false};
std::atomic<bool> g_reader_guard{false};
std::atomic<uintptr_t> g_pairip_base{0};
std::mutex g_trace_mutex;

// Ring buffer of the last VM instructions executed by the klB program.
//
// executeVM's dispatcher is called with the interpreter state in x0 and the
// decoded opcode in w1. The state lives in executeVM's frame, so:
//   [state+0x88] -> address of the image pointer
//   [state+0x90] -> operand cursor (the value the klB handler treats as pc)
//   [state-0x40] -> packed image length (low 32) and next pc (high 32)
//   [state-0x68] -> fetch pc of the current instruction
//   [state-0x60] -> raw halfword fetched at fetch pc
//   [state-0x5c] -> decoded opcode word passed in w1
// Only klB's image is recorded (length 0x44feb and the "IAP" magic), which
// keeps the ring free of YTw and of other VM programs.
constexpr uint32_t kKlBImageLength = 0x44feb;
constexpr size_t kVmRingSize = 8192;
struct VmRingEntry {
    uint32_t fetch_pc;
    uint32_t cursor;
    uint32_t opcode;
    uint32_t raw_word;
    uint32_t next_pc;
    uint32_t reserved[3];
    uint64_t seq;
};
VmRingEntry g_vm_ring[kVmRingSize] = {};
std::atomic<uint32_t> g_vm_ring_pos{0};
std::atomic<bool> g_vm_trace_enabled{false};

// Shared entry counter for the dispatcher and builtin hooks. The two rings
// are dumped independently, so this is the only way to order a dispatcher
// entry against a builtin entry in the crash dump.
std::atomic<uint64_t> g_trace_seq{0};

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

// Ring of the last builtin interpreter entries. The one-line-per-call log is
// capped, and klB runs hundreds of builtins before the failing ZIP read, so
// the crash call is normally past the cap. Keeping a small ring and dumping it
// from the append hook ties the failing reader to the builtin that built it.
constexpr size_t kBuiltinRingSize = 32;
struct BuiltinRingEntry {
    uint64_t arg;
    uint64_t out;
    uint64_t ctx;
    uint64_t image;
    uint64_t arg_words[6];
    uint32_t id;
    uint32_t pc;
    uint32_t length;
    uint32_t lr_offset;
    uint64_t seq;
};
BuiltinRingEntry g_builtin_ring[kBuiltinRingSize] = {};
std::atomic<uint32_t> g_builtin_ring_pos{0};

// Ring of state ids passed to the obfuscated builtin state dispatcher
// (+0x32c6c). The state machine selects the next id from a hash check, so a
// hosted-only data value shows up as a different id sequence.
constexpr size_t kStateRingSize = 8192;
struct StateRingEntry {
    uint32_t state;
    uint32_t selected;
    uint32_t reader;
    uint32_t reserved;
};
StateRingEntry g_state_ring[kStateRingSize] = {};
std::atomic<uint32_t> g_state_ring_pos{0};

// Ring of the state-selection hash checks at +0x3dc0c/+0x3dc50. The hosted
// path hashes a string before choosing the next state id; the candidate ids
// (w24/w23) and the comparison flags identify which branch selected the
// reader state. Keeping the last entries survives logcat volume limits.
constexpr size_t kHashRingSize = 64;
struct HashRingEntry {
    uint64_t computed;
    uint64_t expected;
    uint32_t length;
    uint32_t offset;
    uint32_t index;
    uint32_t equal;
    uint32_t selected;
    uint32_t candidate_eq;
    uint32_t candidate_ne;
    uint32_t reserved;
    uint8_t sample[32];
};
HashRingEntry g_hash_ring[kHashRingSize] = {};
std::atomic<uint32_t> g_hash_ring_pos{0};

// Ring of the main state-router hash at +0x32c20..+0x32c54. This is the
// dispatcher return path that computes the selector (w5) used by the next
// state. The first-`ne` sample is kept separately so a single fallback
// transition is not lost after the last-64 window fills.
constexpr size_t kRouterRingSize = 64;
struct RouterRingEntry {
    uint64_t table;
    uint64_t entry;
    uint64_t computed;
    uint64_t expected;
    uint64_t mask;
    uint32_t index;
    uint32_t equal;
    uint32_t candidate_eq;
    uint32_t candidate_ne;
    uint32_t mix;
    uint32_t modulus;
    uint32_t selected;
    uint32_t reserved;
};
RouterRingEntry g_router_ring[kRouterRingSize] = {};
std::atomic<uint32_t> g_router_ring_pos{0};
std::atomic<uint32_t> g_router_eq_count{0};
std::atomic<uint32_t> g_router_ne_count{0};
std::atomic<bool> g_router_first_ne_valid{false};
RouterRingEntry g_router_first_ne = {};

// Ring of the string-hash selector gate at +0x383f4/+0x38420. The block
// interns a string, hashes it, then hashes an image range and compares
// `FNV(image bytes) ^ image[w23]` with a 64-bit immediate. Its `csel` at
// +0x38430 writes w5, but the selector-gate run recorded zero hits: this
// block is not on the crash path, and the crash state ring's `sel` is not a
// dispatcher-selected state. The gate-length hook stashes the 16-bit range
// length before the loop consumes w26; the gate hook records the final hash,
// the expected word, both candidates, and the string and image bytes.
constexpr size_t kGateRingSize = 64;
struct GateRingEntry {
    uint64_t string_ptr;
    uint64_t string_len;
    uint64_t image;
    uint64_t computed;
    uint64_t expected;
    uint64_t expected_word;
    uint64_t xored;
    uint32_t data_offset;
    uint32_t data_length;
    uint32_t expected_offset;
    uint32_t candidate_eq;
    uint32_t candidate_ne;
    uint32_t equal;
    uint32_t selected;
    uint32_t fetch;
    uint32_t op;
    uint32_t reserved[3];
    uint8_t string_bytes[48];
    uint8_t data[32];
};
GateRingEntry g_gate_ring[kGateRingSize] = {};
std::atomic<uint32_t> g_gate_ring_pos{0};
std::atomic<uint32_t> g_gate_eq_count{0};
std::atomic<uint32_t> g_gate_ne_count{0};
std::atomic<bool> g_gate_first_ne_valid{false};
GateRingEntry g_gate_first_ne = {};

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

// Debug-only offset-0 mapping of the installed base APK. A stock process
// opens the game APK as an archive at file offset 0; the hosted process only
// has non-zero-offset mappings. Kept for the process lifetime so the VM's
// /proc/self/maps reader can see the range while klB runs.
std::mutex g_apk_zero_map_mutex;
void *g_apk_zero_map = nullptr;
size_t g_apk_zero_map_size = 0;

// Debug-only low mapping of the base APK's real central directory. The maps
// reader consumes only a 143 KB prefix, while the real CD line sits past
// 316 KB; this duplicate makes the `r--s 074f5000` line visible early.
std::mutex g_apk_cd_map_mutex;
void *g_apk_cd_map = nullptr;
size_t g_apk_cd_map_size = 0;
constexpr off_t kBaseApkCdOffset = 0x74f5000;
constexpr size_t kBaseApkCdMapSize = 0x13000;
constexpr size_t kBaseApkCdEocdOffset = 0x1281c;

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

bool FindPairIpBase(uintptr_t *base) {
    ModuleLookup lookup{"libpairipcore.so", 0};
    dl_iterate_phdr(FindModuleCallback, &lookup);
    if (lookup.base == 0) {
        LOGE("trace: libpairipcore.so is not mapped");
        return false;
    }
    g_pairip_base.store(lookup.base);
    if (base != nullptr) *base = lookup.base;
    return true;
}

bool InstallAppendHookLocked(uintptr_t base) {
    if (g_pairip_append_original != nullptr) return true;

    auto *target = reinterpret_cast<void *>(base + 0x8d460);
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
         result, reinterpret_cast<void *>(base), target,
         g_pairip_append_original);
    return result == 0 && g_pairip_append_original != nullptr;
}

bool InstallPairIpTrace() {
    std::lock_guard<std::mutex> lock(g_trace_mutex);
    if (g_pairip_append_original != nullptr && g_trace_enabled.load()) return true;

    uintptr_t base = 0;
    if (!FindPairIpBase(&base)) return false;
    if (!InstallAppendHookLocked(base)) return false;

    // Record the klB instruction stream so the crash can be tied to the last
    // opcode that ran before the bad string view was read.
    auto *dispatcher = reinterpret_cast<void *>(base + 0x6e918);
    const int dispatch_result = DobbyHook(
        dispatcher, reinterpret_cast<void *>(&pairip_dispatch_trace_entry),
        &g_pairip_dispatch_original);
    LOGI("trace: dispatch hook rc=%d target=%p trampoline=%p", dispatch_result,
         dispatcher, g_pairip_dispatch_original);
    if (dispatch_result == 0 && g_pairip_dispatch_original != nullptr) {
        g_vm_trace_enabled.store(true);
    }

    // Log the builtin interpreter entry so the ZIP-view builtin can be tied
    // to a specific id and caller.
    if (g_pairip_builtin_original == nullptr) {
        auto *builtin = reinterpret_cast<void *>(base + 0x31de4);
        void *original = nullptr;
        const int rc = DobbyHook(
            builtin, reinterpret_cast<void *>(&pairip_builtin_trace_entry),
            &original);
        g_pairip_builtin_original = original;
        LOGI("trace: builtin hook rc=%d target=%p trampoline=%p", rc, builtin,
             original);
        g_builtin_trace.store(rc == 0 && original != nullptr);
    }

    // Log the state-transition hash input and result. The original +0x3dc08
    // hook sat on `cbz w25`; Dobby relocates that branch and the callback is
    // never reached. Hook the non-branch `add` at +0x3dc0c instead, and the
    // `csel` at +0x3dc50 for the comparison and candidate ids.
    if (g_pairip_hash_input_original == nullptr) {
        auto *target = reinterpret_cast<void *>(base + 0x3dc0c);
        void *original = nullptr;
        const int rc = DobbyHook(
            target, reinterpret_cast<void *>(&pairip_hash_input_entry),
            &original);
        g_pairip_hash_input_original = original;
        LOGI("trace: hash-input hook rc=%d target=%p trampoline=%p", rc, target,
             original);
    }
    if (g_pairip_hash_check_original == nullptr) {
        auto *target = reinterpret_cast<void *>(base + 0x3dc50);
        void *original = nullptr;
        const int rc = DobbyHook(
            target, reinterpret_cast<void *>(&pairip_hash_check_entry),
            &original);
        g_pairip_hash_check_original = original;
        LOGI("trace: hash-check hook rc=%d target=%p trampoline=%p", rc, target,
             original);
    }
    // Log the main state-router hash at +0x32c20. The trampoline entry is a
    // non-branch site; the shim tails through x9 (dead because `eor w9, w21,
    // w23` writes it). The callback records the table base, mask, expected
    // word, and candidates, then computes the comparison offline from the
    // table word, so the csel at +0x32c48 is not hooked.
    if (g_pairip_router_load_original == nullptr) {
        auto *target = reinterpret_cast<void *>(base + 0x32c20);
        void *original = nullptr;
        const int rc = DobbyHook(
            target, reinterpret_cast<void *>(&pairip_router_load_entry),
            &original);
        g_pairip_router_load_original = original;
        LOGI("trace: router-load hook rc=%d target=%p trampoline=%p", rc, target,
             original);
    }
    // Capture the range length of the string-hash selector gate before the
    // loop at +0x38404 consumes w26. The gate hook sits on the following
    // `ldrsw` at +0x38420 and reads the stashed length; the length load is
    // skipped when the range is empty, so the gate hook treats a missing
    // stash as length zero.
    if (g_pairip_gate_len_original == nullptr) {
        auto *target = reinterpret_cast<void *>(base + 0x383f4);
        void *original = nullptr;
        const int rc = DobbyHook(
            target, reinterpret_cast<void *>(&pairip_gate_len_entry),
            &original);
        g_pairip_gate_len_original = original;
        LOGI("trace: gate-len hook rc=%d target=%p trampoline=%p", rc, target,
             original);
    }
    // Log the selector gate itself. The shim runs before the expected-word
    // load at +0x38420 and the callback reads that word directly; the csel at
    // +0x38430 is not hooked. The tail branches through x9, which the next
    // instruction (+0x38424) overwrites.
    if (g_pairip_gate_original == nullptr) {
        auto *target = reinterpret_cast<void *>(base + 0x38420);
        void *original = nullptr;
        const int rc = DobbyHook(
            target, reinterpret_cast<void *>(&pairip_gate_trace_entry),
            &original);
        g_pairip_gate_original = original;
        LOGI("trace: gate hook rc=%d target=%p trampoline=%p", rc, target,
             original);
    }
    // Capture the FNV-1 input of the 0xf656/0x1b hash phase. The hook sits on
    // the `add x11, x9, w11, uxtw` before the loop at +0x7fde0; x11 is the
    // input range and w26 its length.
    if (g_pairip_fnv_original == nullptr) {
        auto *target = reinterpret_cast<void *>(base + 0x7fddc);
        void *original = nullptr;
        const int rc = DobbyHook(
            target, reinterpret_cast<void *>(&pairip_fnv_trace_entry),
            &original);
        g_pairip_fnv_original = original;
        LOGI("trace: fnv-input hook rc=%d target=%p trampoline=%p", rc, target,
             original);
    }
    if (g_pairip_state_original == nullptr) {
        auto *target = reinterpret_cast<void *>(base + 0x32c6c);
        void *original = nullptr;
        const int rc = DobbyHook(
            target, reinterpret_cast<void *>(&pairip_state_trace_entry),
            &original);
        g_pairip_state_original = original;
        LOGI("trace: state hook rc=%d target=%p trampoline=%p", rc, target,
             original);
    }
    // Record the two image offsets and words that become the reader view's
    // data/size. The hook sits on the first image-word load at +0x3e034;
    // x10 already holds the VM image base and w8/w9 are the offsets. The
    // callback does not modify the saved registers.
    if (g_pairip_reader_source_original == nullptr) {
        auto *target = reinterpret_cast<void *>(base + 0x3e034);
        void *original = nullptr;
        const int rc = DobbyHook(
            target, reinterpret_cast<void *>(&pairip_reader_source_entry),
            &original);
        g_pairip_reader_source_original = original;
        LOGI("trace: reader-source hook rc=%d target=%p trampoline=%p", rc,
             target, original);
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

// Logs the /proc/self/maps line that starts at `base`, so the experiment
// record proves the mapping is visible to PairIP's maps reader.
bool LogMapRange(uintptr_t base, const char *tag) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) return false;

    char prefix[32];
    std::snprintf(prefix, sizeof(prefix), "%" PRIxPTR "-", base);
    const size_t prefix_len = std::strlen(prefix);
    char line[512];
    bool found = false;
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        if (std::strncmp(line, prefix, prefix_len) != 0) continue;
        const size_t length = std::strlen(line);
        if (length > 0 && line[length - 1] == '\n') line[length - 1] = '\0';
        LOGI("%s: maps %s", tag, line);
        found = true;
        break;
    }
    std::fclose(maps);
    return found;
}

// Debug-only: maps the whole installed base APK read-only at file offset 0.
// Stock has an offset-0 archive mapping; the hosted process does not, so
// PairIP's central-directory resolver may fail to initialize klB's reader
// slots. The low hint keeps the range below the dalvik/linear-alloc shadow
// map and below every mapping observed in the hosted process.
bool InstallApkZeroMap(const char *apk_path, std::string *error) {
    std::lock_guard<std::mutex> lock(g_apk_zero_map_mutex);
    if (g_apk_zero_map != nullptr) return true;

    const int fd = open(apk_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "open failed";
        return false;
    }
    struct stat status {};
    if (fstat(fd, &status) != 0 || status.st_size <= 0) {
        close(fd);
        *error = "stat failed";
        return false;
    }

    const size_t length = static_cast<size_t>(status.st_size);
    constexpr uintptr_t kHint = 0x7900000000ULL;
    void *map = mmap(reinterpret_cast<void *>(kHint), length, PROT_READ,
                     MAP_PRIVATE | MAP_FIXED_NOREPLACE, fd, 0);
    int fallback_errno = 0;
    bool fixed = map != MAP_FAILED;
    if (map == MAP_FAILED) {
        fallback_errno = errno;
        map = mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0);
        if (map == MAP_FAILED) {
            const int map_errno = errno;
            close(fd);
            *error = "mmap failed: " + std::to_string(map_errno);
            return false;
        }
    }
    close(fd);

    const auto *bytes = static_cast<const uint8_t *>(map);
    if (length < 4 || std::memcmp(bytes, "PK\x03\x04", 4) != 0) {
        munmap(map, length);
        *error = "APK signature mismatch";
        return false;
    }

    g_apk_zero_map = map;
    g_apk_zero_map_size = length;
    LOGI("apk-zero-map: base=%p size=%zu hint=%p fixed=%d fallback_errno=%d "
         "path=%s",
         map, length, reinterpret_cast<void *>(kHint), fixed ? 1 : 0,
         fallback_errno, apk_path);
    if (!LogMapRange(reinterpret_cast<uintptr_t>(map), "apk-zero-map")) {
        LOGI("apk-zero-map: range not found in /proc/self/maps");
    }
    return true;
}

// Debug-only: maps the installed base APK's real central directory at a low
// address. The VM's /proc/self/maps reader consumes only the first 146,432
// bytes; the real `r--s 074f5000` line starts at byte 316,554. This duplicate
// puts an identical CD line inside the scanned prefix without touching the
// APK. MAP_SHARED mirrors the real mapping's `r--s` flags.
bool InstallApkCdMap(const char *apk_path, std::string *error) {
    std::lock_guard<std::mutex> lock(g_apk_cd_map_mutex);
    if (g_apk_cd_map != nullptr) return true;

    const int fd = open(apk_path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *error = "open failed";
        return false;
    }
    struct stat status {};
    if (fstat(fd, &status) != 0 || status.st_size <= 0) {
        close(fd);
        *error = "stat failed";
        return false;
    }
    const size_t file_size = static_cast<size_t>(status.st_size);
    // The real mapping is page-rounded and extends past EOF; only the CD and
    // its EOCD have to be backed by the file.
    const size_t required =
        static_cast<size_t>(kBaseApkCdOffset) + kBaseApkCdEocdOffset + 22;
    if (file_size < required) {
        close(fd);
        *error = "APK too small for the expected central directory";
        return false;
    }

    constexpr uintptr_t kHint = 0x7900000000ULL;
    void *map = mmap(reinterpret_cast<void *>(kHint), kBaseApkCdMapSize,
                     PROT_READ, MAP_SHARED | MAP_FIXED_NOREPLACE, fd,
                     kBaseApkCdOffset);
    const int map_errno = errno;
    close(fd);
    if (map == MAP_FAILED) {
        *error = "mmap failed: " + std::to_string(map_errno);
        return false;
    }

    const auto *bytes = static_cast<const uint8_t *>(map);
    if (std::memcmp(bytes, "PK\x01\x02", 4) != 0) {
        munmap(map, kBaseApkCdMapSize);
        *error = "central directory signature mismatch";
        return false;
    }
    if (std::memcmp(bytes + kBaseApkCdEocdOffset, "PK\x05\x06", 4) != 0) {
        munmap(map, kBaseApkCdMapSize);
        *error = "end-of-central-directory signature mismatch";
        return false;
    }

    g_apk_cd_map = map;
    g_apk_cd_map_size = kBaseApkCdMapSize;
    LOGI("apk-cd-map: base=%p size=%zu hint=%p fixed=1 offset=%#lx "
         "eocd=%#lx path=%s",
         map, kBaseApkCdMapSize, reinterpret_cast<void *>(kHint),
         static_cast<unsigned long>(kBaseApkCdOffset),
         static_cast<unsigned long>(kBaseApkCdOffset + kBaseApkCdEocdOffset),
         apk_path);
    if (!LogMapRange(reinterpret_cast<uintptr_t>(map), "apk-cd-map")) {
        LOGI("apk-cd-map: range not found in /proc/self/maps");
    }
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

// Debug-only: skip the klB startup program instead of letting its VM read a
// ZIP view from image literals that a hosted launch never resolves. Enabled
// only by PairIpExperiment.skipKlb and only for the tested target.
std::atomic<bool> g_skip_klb{false};

constexpr char kKlbProgramName[] = "klB1wZSojiVvDvCN";

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
    if (g_skip_klb.load() && name != nullptr &&
        std::strcmp(name, kKlbProgramName) == 0) {
        LOGI("exec-prog: skipped %s (pairip_skip_klb)", name);
        return nullptr;
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
void *g_pairip_builtin_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_hash_input_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_hash_check_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_router_load_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_gate_len_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_gate_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_fnv_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_state_original = nullptr;

extern "C" __attribute__((visibility("hidden")))
void *g_pairip_reader_source_original = nullptr;

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
            g_klb_active.load()) {
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
        uint64_t true_pc = 0;
        uint32_t raw_word = 0;
        uint32_t decoded_word = 0;
        uint16_t fetched_insn = 0;
        SafeReadMemory(state - 0x48, &slot, sizeof(slot));
        SafeReadMemory(state - 0x40, &lenpc, sizeof(lenpc));
        SafeReadMemory(state + 0x88, &cursor_slot, sizeof(cursor_slot));
        SafeReadMemory(state + 0x90, &cursor_value, sizeof(cursor_value));
        SafeReadMemory(state - 0x68, &true_pc, sizeof(true_pc));
        SafeReadMemory(state - 0x60, &raw_word, sizeof(raw_word));
        SafeReadMemory(state - 0x5c, &decoded_word, sizeof(decoded_word));
        if (slot != 0) {
            SafeReadMemory(static_cast<uintptr_t>(slot) +
                               static_cast<uint32_t>(true_pc),
                           &fetched_insn, sizeof(fetched_insn));
        }
        LOGI("trace: dispatch[%u] state=%p slot=%016" PRIx64
             " lenpc=%016" PRIx64 " s88=%016" PRIx64 " s90=%016" PRIx64
             " truepc=%#x raw=%#x dec=%#x insn=%#x op=%#x x1=%016" PRIx64,
             call_index, reinterpret_cast<void *>(state), slot, lenpc,
             cursor_slot, cursor_value, static_cast<uint32_t>(true_pc),
             raw_word, decoded_word, fetched_insn,
             static_cast<unsigned>(regs[1] & 0xffffu), regs[1]);
    }

    uint8_t head[0xe0] = {};
    if (!SafeReadMemory(state - 0x48, head, sizeof(head))) return;
    uint64_t image_slot = 0;
    uint32_t length = 0;
    uint32_t next_pc = 0;
    uint32_t cursor = 0;
    uint64_t fetch_pc = 0;
    uint32_t raw_word = 0;
    std::memcpy(&image_slot, head, sizeof(image_slot));
    std::memcpy(&length, head + 0x08, sizeof(length));
    std::memcpy(&next_pc, head + 0x0c, sizeof(next_pc));
    std::memcpy(&cursor, head + 0xd8, sizeof(cursor));
    // The fetch pc, raw halfword and decoded word live just below the block
    // that starts at state-0x48, not inside it.
    SafeReadMemory(state - 0x68, &fetch_pc, sizeof(fetch_pc));
    SafeReadMemory(state - 0x60, &raw_word, sizeof(raw_word));
    if (length != kKlBImageLength) return;

    // [state-0x48] is the image pointer itself; state+0x88 points at that slot.
    const uintptr_t image = static_cast<uintptr_t>(image_slot);
    if (image == 0) return;
    uint32_t magic = 0;
    if (!SafeReadMemory(image, &magic, sizeof(magic))) return;
    if (magic != 0x50414900u) return;  // "\0IAP"

    const uint64_t seq = g_trace_seq.fetch_add(1, std::memory_order_relaxed);
    const uintptr_t lr = reinterpret_cast<uintptr_t>(regs[31]);
    const uintptr_t base = g_pairip_base.load();
    const uint32_t lr_offset =
        lr >= base ? static_cast<uint32_t>(lr - base) : 0;
    if (fetch_pc == 0x1f811u &&
        (static_cast<uint32_t>(regs[1]) & 0xffffu) == 0x7eu) {
        uint64_t q[8] = {};
        SafeReadMemory(state, q, sizeof(q));
        LOGI("trace: seqev D seq=%" PRIu64 " state=%p image=%p fetch=%#x "
             "next=%#x cursor=%#x lr=+%#x q=%016" PRIx64 ",%016" PRIx64
             ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64
             ",%016" PRIx64 ",%016" PRIx64,
             seq, reinterpret_cast<void *>(state),
             reinterpret_cast<void *>(image), static_cast<unsigned>(fetch_pc),
             next_pc, cursor, lr_offset, q[0], q[1], q[2], q[3], q[4], q[5],
             q[6], q[7]);
    }

    // The write watch only covers the window before the interpreter starts.
    if (g_image_watch_armed.load()) DisarmImageWatch("first dispatch");

    const uint32_t pos = g_vm_ring_pos.fetch_add(1, std::memory_order_relaxed);
    VmRingEntry &entry = g_vm_ring[pos % kVmRingSize];
    entry.fetch_pc = static_cast<uint32_t>(fetch_pc);
    entry.cursor = cursor;
    entry.opcode = static_cast<uint32_t>(regs[1]) & 0xffffu;
    entry.raw_word = raw_word;
    entry.next_pc = next_pc;
    entry.seq = seq;
    std::fill(std::begin(entry.reserved), std::end(entry.reserved), 0);
    t_last_vm_fetch = entry.fetch_pc;
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

// Dump the most recent state-selection hash checks. The last entries show
// whether the reader state was chosen by a matching or a failing hash and
// which input string was hashed.
void DumpHashRing() {
    const uint32_t pos = g_hash_ring_pos.load(std::memory_order_relaxed);
    const uint32_t available = pos < kHashRingSize ? pos : kHashRingSize;
    const uint32_t count = available < 32 ? available : 32;
    LOGI("trace: hash ring pos=%u dumping last %u", pos, count);
    for (uint32_t i = count; i > 0; --i) {
        const HashRingEntry &entry = g_hash_ring[(pos - i) % kHashRingSize];
        char hex[sizeof(entry.sample) * 2 + 1] = {};
        char text[sizeof(entry.sample) + 1] = {};
        for (size_t j = 0; j < sizeof(entry.sample); ++j) {
            std::snprintf(hex + j * 2, 3, "%02x", entry.sample[j]);
            text[j] = entry.sample[j] >= 0x20 && entry.sample[j] <= 0x7e
                          ? entry.sample[j]
                          : '.';
        }
        LOGI("trace: hash[%u] %s computed=%016" PRIx64 " expected=%016" PRIx64
             " off=%#x len=%u idx=%#x sel=%#x eq=%#x ne=%#x data=%s text=%s",
             pos - i, entry.equal ? "ok" : "FAIL", entry.computed,
             entry.expected, entry.offset, entry.length, entry.index,
             entry.selected, entry.candidate_eq, entry.candidate_ne, hex, text);
    }
}

// Dump the main state-router hash. The last entries show the table index,
// loaded entry, comparison value, candidates, and the selector that the
// router produced; the first-`ne` sample survives a full ring.
void DumpRouterRing() {
    const uint32_t pos = g_router_ring_pos.load(std::memory_order_relaxed);
    const uint32_t available = pos < kRouterRingSize ? pos : kRouterRingSize;
    const uint32_t count = available < 32 ? available : 32;
    LOGI("trace: router ring pos=%u eq=%u ne=%u dumping last %u", pos,
         g_router_eq_count.load(std::memory_order_relaxed),
         g_router_ne_count.load(std::memory_order_relaxed), count);
    for (uint32_t i = count; i > 0; --i) {
        const RouterRingEntry &entry =
            g_router_ring[(pos - i) % kRouterRingSize];
        LOGI("trace: router[%u] %s table=%#" PRIx64 " idx=%#x entry=%#" PRIx64
             " computed=%#" PRIx64 " expected=%#" PRIx64 " eq=%#x ne=%#x"
             " mix=%#x mod=%#x sel=%#x",
             pos - i, entry.equal ? "ok" : "FAIL", entry.table, entry.index,
             entry.entry, entry.computed, entry.expected, entry.candidate_eq,
             entry.candidate_ne, entry.mix, entry.modulus, entry.selected);
    }
    if (g_router_first_ne_valid.load(std::memory_order_relaxed)) {
        const RouterRingEntry &entry = g_router_first_ne;
        LOGI("trace: router-first-ne table=%#" PRIx64 " idx=%#x entry=%#" PRIx64
             " computed=%#" PRIx64 " expected=%#" PRIx64 " eq=%#x ne=%#x"
             " mix=%#x mod=%#x sel=%#x",
             entry.table, entry.index, entry.entry, entry.computed,
             entry.expected, entry.candidate_eq, entry.candidate_ne, entry.mix,
             entry.modulus, entry.selected);
    }
}

// Dump the string-hash selector gate. The last entries show the interned
// string, the computed and expected hashes, the expected word, the hashed
// image range, the candidates, and the selector that the csel produced; the
// first-`ne` sample survives a full ring.
void DumpGateRing() {
    const uint32_t pos = g_gate_ring_pos.load(std::memory_order_relaxed);
    const uint32_t available = pos < kGateRingSize ? pos : kGateRingSize;
    const uint32_t count = available < 32 ? available : 32;
    LOGI("trace: gate ring pos=%u eq=%u ne=%u dumping last %u", pos,
         g_gate_eq_count.load(std::memory_order_relaxed),
         g_gate_ne_count.load(std::memory_order_relaxed), count);
    for (uint32_t i = count; i > 0; --i) {
        const GateRingEntry &entry = g_gate_ring[(pos - i) % kGateRingSize];
        char text[sizeof(entry.string_bytes) + 1] = {};
        for (size_t j = 0; j < sizeof(entry.string_bytes); ++j) {
            text[j] = entry.string_bytes[j] >= 0x20 &&
                              entry.string_bytes[j] <= 0x7e
                          ? static_cast<char>(entry.string_bytes[j])
                          : '.';
        }
        char hex[sizeof(entry.data) * 2 + 1] = {};
        for (size_t j = 0; j < sizeof(entry.data); ++j) {
            std::snprintf(hex + j * 2, 3, "%02x", entry.data[j]);
        }
        LOGI("trace: gate[%u] %s fetch=%#x op=%#x img=%p off=%#x len=%u "
             "expoff=%#x word=%016" PRIx64 " computed=%016" PRIx64
             " xor=%016" PRIx64 " expected=%016" PRIx64 " eqc=%#x nec=%#x "
             "sel=%#x str=%p/%zu text=%s data=%s",
             pos - i, entry.equal ? "ok" : "FAIL", entry.fetch, entry.op,
             reinterpret_cast<void *>(entry.image), entry.data_offset,
             entry.data_length, entry.expected_offset, entry.expected_word,
             entry.computed, entry.xored, entry.expected, entry.candidate_eq,
             entry.candidate_ne, entry.selected,
             reinterpret_cast<void *>(entry.string_ptr),
             static_cast<size_t>(entry.string_len), text, hex);
    }
    if (g_gate_first_ne_valid.load(std::memory_order_relaxed)) {
        const GateRingEntry &entry = g_gate_first_ne;
        LOGI("trace: gate-first-ne fetch=%#x op=%#x img=%p off=%#x len=%u "
             "expoff=%#x word=%016" PRIx64 " computed=%016" PRIx64
             " xor=%016" PRIx64 " expected=%016" PRIx64 " eqc=%#x nec=%#x "
             "sel=%#x str=%p/%zu",
             entry.fetch, entry.op, reinterpret_cast<void *>(entry.image),
             entry.data_offset, entry.data_length, entry.expected_offset,
             entry.expected_word, entry.computed, entry.xored, entry.expected,
             entry.candidate_eq, entry.candidate_ne, entry.selected,
             reinterpret_cast<void *>(entry.string_ptr),
             static_cast<size_t>(entry.string_len));
    }
}

// Dump the most recent state ids seen by the builtin state dispatcher. The
// last ids show which state path the failing builtin took; a fallback state
// appears as a different id than the pass path would have selected.
void DumpStateRing() {
    const uint32_t pos = g_state_ring_pos.load(std::memory_order_relaxed);
    const uint32_t available = pos < kStateRingSize ? pos : kStateRingSize;
    const uint32_t count = available < 512 ? available : 512;
    LOGI("trace: state ring pos=%u dumping last %u", pos, count);
    for (uint32_t i = count; i > 0; --i) {
        const StateRingEntry &entry = g_state_ring[(pos - i) % kStateRingSize];
        LOGI("trace: state[%u] id=%#x sel=%#x reader=%#x", pos - i, entry.state,
             entry.selected, entry.reader);
    }
}

// Dump the most recent builtin interpreter entries. Called with the VM ring
// when the append hook sees the failing ZIP read, so the last entry is the
// builtin (or builtin chain) that produced the bad reader view.
void DumpBuiltinRing() {
    const uint32_t pos = g_builtin_ring_pos.load(std::memory_order_relaxed);
    const uint32_t count = pos < kBuiltinRingSize ? pos : kBuiltinRingSize;
    LOGI("trace: builtin ring pos=%u dumping last %u", pos, count);
    for (uint32_t i = count; i > 0; --i) {
        const BuiltinRingEntry &entry = g_builtin_ring[(pos - i) % kBuiltinRingSize];
        LOGI("trace: builtin[%u] seq=%" PRIu64 " id=%#x pc=%#x arg=%p "
             "out=%p img=%p len=%#x lr=+%#x args=%016" PRIx64 ",%016" PRIx64
             ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64,
             pos - i, entry.seq, entry.id, entry.pc,
             reinterpret_cast<void *>(entry.arg),
             reinterpret_cast<void *>(entry.out),
             reinterpret_cast<void *>(entry.image), entry.length,
             entry.lr_offset, entry.arg_words[0], entry.arg_words[1],
             entry.arg_words[2], entry.arg_words[3], entry.arg_words[4],
             entry.arg_words[5]);
    }
}

// Dump the most recent klB instructions. Called from the append hook when the
// reader is about to read from an unmapped base, i.e. immediately before the
// crash that the trace is meant to explain.
void DumpVmRing() {
    DumpSyscallRing();
    DumpBuiltinRing();
    DumpHashRing();
    DumpRouterRing();
    DumpGateRing();
    DumpStateRing();
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
        LOGI("trace: vminsn[%u] seq=%" PRIu64 " fetch=%#x next=%#x "
             "cursor=%#x op=%#x raw=%#x",
             pos - i, entry.seq, entry.fetch_pc, entry.next_pc, entry.cursor,
             entry.opcode, entry.raw_word);
    }
}

extern "C" __attribute__((visibility("hidden")))
// Debug-only: log the giant builtin interpreter entry. x0 is the argument
// block, w1 the builtin id and x2 the output slot. The argument block's +8
// word points at the VM context, so the image length and current pc identify
// the program without needing a stack walk.
void pairip_builtin_trace_c(uint64_t *regs) {
    if (regs == nullptr || !g_builtin_trace.load()) return;
    const uintptr_t arg = static_cast<uintptr_t>(regs[0]);
    const uint32_t id = static_cast<uint32_t>(regs[1]);
    const uintptr_t out = static_cast<uintptr_t>(regs[2]);
    uint64_t ctx = 0;
    uint64_t lenslot[3] = {};
    if (!SafeReadMemory(arg + 0x8, &ctx, sizeof(ctx)) || ctx == 0) return;
    if (!SafeReadMemory(ctx, lenslot, sizeof(lenslot))) return;
    const uint32_t length = static_cast<uint32_t>(lenslot[1]);
    if (length != kKlBImageLength) return;

    const uintptr_t lr = reinterpret_cast<uintptr_t>(regs[31]);
    const uint32_t pc = static_cast<uint32_t>(lenslot[1] >> 32);
    const uint64_t seq = g_trace_seq.fetch_add(1, std::memory_order_relaxed);
    const uintptr_t base = g_pairip_base.load();
    const uint32_t lr_offset =
        lr >= base ? static_cast<uint32_t>(lr - base) : 0;
    if ((id & 0xffffu) == 0x7eu && pc == 0x1f813u) {
        uint64_t q[8] = {};
        SafeReadMemory(ctx, q, sizeof(q));
        LOGI("trace: seqev B seq=%" PRIu64 " arg=%p out=%p ctx=%p "
             "img=%016" PRIx64 " id=%#x pc=%#x lr=+%#x q=%016" PRIx64
             ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64
             ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64,
             seq, reinterpret_cast<void *>(arg), reinterpret_cast<void *>(out),
             reinterpret_cast<void *>(ctx), lenslot[0], id,
             static_cast<unsigned>(pc), lr_offset, q[0], q[1], q[2], q[3],
             q[4], q[5], q[6], q[7]);
    }

    // Always keep the last entries; the text log below stops at 128 calls and
    // would otherwise hide the builtin that runs immediately before the crash.
    {
        const uint32_t index =
            g_builtin_ring_pos.fetch_add(1, std::memory_order_relaxed);
        BuiltinRingEntry &entry = g_builtin_ring[index % kBuiltinRingSize];
        entry.arg = arg;
        entry.out = out;
        entry.ctx = ctx;
        entry.image = lenslot[0];
        entry.id = id;
        entry.pc = pc;
        entry.length = length;
        entry.lr_offset = lr_offset;
        entry.seq = seq;
        for (size_t i = 0; i < 6; ++i) {
            entry.arg_words[i] = 0;
            SafeReadMemory(arg + i * 8, &entry.arg_words[i], sizeof(uint64_t));
        }
    }

    static std::atomic<int> log_count{0};
    if (log_count.fetch_add(1) >= 128) return;
    LOGI("trace: builtin id=%#x arg=%p out=%p ctx=%p img=%016" PRIx64
         " len=%#x pc=%#x lr=%p off=%#zx",
         id, reinterpret_cast<void *>(arg), reinterpret_cast<void *>(out),
         reinterpret_cast<void *>(ctx), lenslot[0], length,
         static_cast<unsigned>(pc),
         reinterpret_cast<void *>(lr),
         lr >= g_pairip_base.load() ? lr - g_pairip_base.load() : 0);
}

extern "C" __attribute__((visibility("hidden")))
void pairip_state_trace_c(uint64_t *regs) {
    if (regs == nullptr || !g_trace_enabled.load()) return;
    const uint32_t index = g_state_ring_pos.fetch_add(1, std::memory_order_relaxed);
    StateRingEntry &entry = g_state_ring[index % kStateRingSize];
    entry.state = static_cast<uint32_t>(regs[10]);
    entry.selected = static_cast<uint32_t>(regs[5]);
    entry.reader = static_cast<uint32_t>(regs[9]);
    entry.reserved = 0;
}

// Debug-only reader-source observation. The hook at +0x3e034 runs on the
// first of the two image-word loads that become the reader's data/size.
// x10 is the VM image base, w8/w9 are the two image offsets, and x16 is the
// interpreter state. The descriptor at [sp+0x538] is the object the reader
// builder used; [sp+0x540] holds w29. Nothing is written to the saved
// register file, so the relocated load and the rest of the handler see the
// unmodified state.
extern "C" __attribute__((visibility("hidden")))
void pairip_reader_source_c(uint64_t *regs) {
    if (regs == nullptr || !g_trace_enabled.load()) return;

    const uintptr_t image = static_cast<uintptr_t>(regs[10]);
    const uint32_t off_data = static_cast<uint32_t>(regs[8]);
    const uint32_t off_size = static_cast<uint32_t>(regs[9]);
    const uintptr_t ctx = static_cast<uintptr_t>(regs[16]);
    const uint32_t fetch = t_last_vm_fetch;

    uint64_t data = 0;
    uint64_t size = 0;
    const bool data_ok =
        image != 0 && SafeReadMemory(image + off_data, &data, sizeof(data));
    const bool size_ok =
        image != 0 && SafeReadMemory(image + off_size, &size, sizeof(size));

    // The ZIP reader is the only caller at pc 0x40a73 with this pair. Also
    // keep a line if either known ciphertext word appears, so a different
    // offset/word pair is still visible.
    const bool interesting =
        fetch == 0x40a73u || off_data == 0x3f874u || off_size == 0x1ba5cu ||
        (data_ok && data == 0xbbb9eb6daed20ed0ULL) ||
        (size_ok && size == 0x015370f87525d57bULL);
    if (!interesting) return;

    static std::atomic<int> log_count{0};
    if (log_count.load() >= 8) return;
    if (log_count.fetch_add(1) >= 8) return;

    uint64_t ctx_words[4] = {};
    const bool ctx_ok = ctx != 0 && SafeReadMemory(ctx, ctx_words,
                                                   sizeof(ctx_words));

    const uintptr_t caller_sp = reinterpret_cast<uintptr_t>(regs) + 0x110;
    uint64_t desc = 0;
    uint32_t desc_index = 0;
    SafeReadMemory(caller_sp + 0x538, &desc, sizeof(desc));
    SafeReadMemory(caller_sp + 0x540, &desc_index, sizeof(desc_index));
    uint64_t desc_words[4] = {};
    const bool desc_ok =
        desc != 0 && SafeReadMemory(desc, desc_words, sizeof(desc_words));

    // The descriptor index is also the image offset the decoder reads first.
    // Capture that entry and the bytes at the current fetch so a runtime
    // patch is visible in the log.
    uint32_t entry_words[4] = {};
    const bool entry_ok =
        image != 0 && SafeReadMemory(image + desc_index, entry_words,
                                     sizeof(entry_words));
    uint8_t fetch_bytes[8] = {};
    const bool fetch_ok =
        image != 0 && SafeReadMemory(image + fetch, fetch_bytes,
                                     sizeof(fetch_bytes));

    LOGI("reader-source: ctx=%p image=%p off_data=%#x off_size=%#x "
         "data=%016" PRIx64 " size=%016" PRIx64 " data_ok=%d size_ok=%d "
         "ctxw=%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64
         " ctx_ok=%d desc=%p descw=%016" PRIx64 ",%016" PRIx64 ",%016" PRIx64
         ",%016" PRIx64 " desc_ok=%d index=%#x entry_ok=%d "
         "entry=%08x,%08x,%08x,%08x fetch=%#x fetch_ok=%d "
         "fetch_bytes=%02x%02x%02x%02x%02x%02x%02x%02x "
         "lr=%p off=%#zx",
         reinterpret_cast<void *>(ctx), reinterpret_cast<void *>(image),
         off_data, off_size, data, size, data_ok ? 1 : 0, size_ok ? 1 : 0,
         ctx_words[0], ctx_words[1], ctx_words[2], ctx_words[3],
         ctx_ok ? 1 : 0, reinterpret_cast<void *>(desc), desc_words[0],
         desc_words[1], desc_words[2], desc_words[3], desc_ok ? 1 : 0,
         desc_index, entry_ok ? 1 : 0, entry_words[0], entry_words[1],
         entry_words[2], entry_words[3], fetch, fetch_ok ? 1 : 0,
         fetch_bytes[0], fetch_bytes[1], fetch_bytes[2], fetch_bytes[3],
         fetch_bytes[4], fetch_bytes[5], fetch_bytes[6], fetch_bytes[7],
         reinterpret_cast<void *>(regs[31]),
         regs[31] >= g_pairip_base.load() ? regs[31] - g_pairip_base.load()
                                          : 0);
}

// Debug-only state-transition hash tracing.
//
// The giant klB handler performs a hash check before selecting the next state
// id. The original hook at +0x3dc08 sat on the leading `cbz w25`; Dobby's
// branch relocation bypassed the callback, so the check never logged. The
// probe now hooks the non-branch sites: +0x3dc0c (`add x9, x8, w29, uxtw`)
// records the `w25`-byte input at `image + w29`, and +0x3dc50
// (`csel w5, w24, w23, eq`) records the hash comparison and the two candidate
// state ids. The shims tail through x9, which the relocated instruction
// overwrites (input) or which is dead after the csel (check).
thread_local uint32_t t_hash_length = 0;
thread_local uint32_t t_hash_offset = 0;
thread_local uint64_t t_hash_image = 0;
thread_local bool t_hash_have_input = false;

extern "C" __attribute__((visibility("hidden")))
void pairip_hash_input_c(uint64_t *regs) {
    if (regs == nullptr || !g_trace_enabled.load()) return;
    t_hash_length = static_cast<uint32_t>(regs[25]);
    t_hash_offset = static_cast<uint32_t>(regs[29]);
    t_hash_image = regs[8];
    t_hash_have_input = true;
    static std::atomic<int> log_count{0};
    if (t_hash_length == 0 || log_count.fetch_add(1) >= 8) return;

    uint8_t sample[32] = {};
    const size_t copy = t_hash_length < sizeof(sample) ? t_hash_length
                                                       : sizeof(sample);
    SafeReadMemory(static_cast<uintptr_t>(t_hash_image + t_hash_offset), sample,
                   copy);
    char hex[sizeof(sample) * 2 + 1] = {};
    char text[sizeof(sample) + 1] = {};
    for (size_t i = 0; i < copy; ++i) {
        std::snprintf(hex + i * 2, 3, "%02x", sample[i]);
        text[i] = sample[i] >= 0x20 && sample[i] <= 0x7e ? sample[i] : '.';
    }
    LOGI("hash: input off=%#x len=%u img=%p data=%s text=%s", t_hash_offset,
         t_hash_length, reinterpret_cast<void *>(t_hash_image), hex, text);
}

extern "C" __attribute__((visibility("hidden")))
void pairip_hash_check_c(uint64_t *regs) {
    if (regs == nullptr || !g_trace_enabled.load()) return;
    const uint64_t computed = regs[8];
    const uint64_t expected = regs[9];
    // regs[30] is the saved NZCV; the Z flag decides the csel at +0x3dc50.
    const uint32_t nzcv = static_cast<uint32_t>(regs[30]);
    const uint32_t equal = (nzcv >> 30) & 1u;
    const uint32_t candidate_eq = static_cast<uint32_t>(regs[24]);
    const uint32_t candidate_ne = static_cast<uint32_t>(regs[23]);
    const uint32_t selected = equal ? candidate_eq : candidate_ne;
    const uint32_t length = t_hash_have_input ? t_hash_length : 0;
    const uint32_t offset = t_hash_have_input ? t_hash_offset : 0;

    uint8_t sample[32] = {};
    if (length != 0) {
        const size_t copy = length < sizeof(sample) ? length : sizeof(sample);
        SafeReadMemory(static_cast<uintptr_t>(t_hash_image + offset), sample,
                       copy);
    }

    const uint32_t index = g_hash_ring_pos.fetch_add(1, std::memory_order_relaxed);
    HashRingEntry &entry = g_hash_ring[index % kHashRingSize];
    entry.computed = computed;
    entry.expected = expected;
    entry.length = length;
    entry.offset = offset;
    entry.index = static_cast<uint32_t>(regs[26]);
    entry.equal = equal;
    entry.selected = selected;
    entry.candidate_eq = candidate_eq;
    entry.candidate_ne = candidate_ne;
    entry.reserved = 0;
    for (size_t i = 0; i < sizeof(entry.sample); ++i) {
        entry.sample[i] = i < sizeof(sample) ? sample[i] : 0;
    }
    t_hash_have_input = false;

    char hex[sizeof(sample) * 2 + 1] = {};
    char text[sizeof(sample) + 1] = {};
    for (size_t i = 0; i < sizeof(sample); ++i) {
        std::snprintf(hex + i * 2, 3, "%02x", sample[i]);
        text[i] = sample[i] >= 0x20 && sample[i] <= 0x7e ? sample[i] : '.';
    }
    LOGI("hash: %s computed=%016" PRIx64 " expected=%016" PRIx64
         " off=%#x len=%u idx=%#x sel=%#x eq=%#x ne=%#x z=%u data=%s text=%s",
         equal ? "ok" : "FAIL", computed, expected, offset, length,
         static_cast<uint32_t>(regs[26]), selected, candidate_eq, candidate_ne,
         equal, hex, text);
}

// Main state-router hash tracing.
//
// The handler return trampoline at +0x32c20 computes the next selector (w5).
// The shim runs before `eor w9, w21, w23` and records the caller's table base
// (x8), mask (x12), expected word (x22), candidates (w24/w25), mix (w21), and
// modulus (w19). The table word is read directly here (the VM executes the
// same load at +0x32c2c), and the comparison and selected selector are
// computed exactly as the native block does, so the csel is not hooked.
extern "C" __attribute__((visibility("hidden")))
void pairip_router_load_c(uint64_t *regs) {
    if (regs == nullptr || !g_trace_enabled.load()) return;
    RouterRingEntry entry{};
    entry.table = regs[8];
    entry.mask = regs[12];
    entry.expected = regs[22];
    entry.candidate_eq = static_cast<uint32_t>(regs[24]);
    entry.candidate_ne = static_cast<uint32_t>(regs[25]);
    entry.mix = static_cast<uint32_t>(regs[21]);
    entry.modulus = static_cast<uint32_t>(regs[19]);
    const uint32_t mixed =
        static_cast<uint32_t>(regs[21] ^ regs[23]);
    entry.index = entry.modulus != 0 ? mixed % entry.modulus : mixed;

    uint32_t raw = 0;
    if (entry.table != 0) {
        SafeReadMemory(static_cast<uintptr_t>(entry.table +
                                              entry.index * 4ULL),
                       &raw, sizeof(raw));
    }
    entry.entry = static_cast<uint64_t>(
        static_cast<int64_t>(static_cast<int32_t>(raw)));
    const uint64_t mask = entry.mask;
    const uint64_t word = entry.entry;
    uint64_t tmp = ~(mask & word);
    tmp = word + tmp;
    const uint64_t combined = word | ~mask;
    entry.computed = tmp - combined;
    entry.equal = entry.computed == entry.expected ? 1u : 0u;
    const uint32_t picked =
        entry.equal ? entry.candidate_eq : entry.candidate_ne;
    entry.selected = entry.modulus != 0
                         ? (entry.mix ^ picked) % entry.modulus
                         : (entry.mix ^ picked);

    if (entry.equal) {
        g_router_eq_count.fetch_add(1, std::memory_order_relaxed);
    } else {
        g_router_ne_count.fetch_add(1, std::memory_order_relaxed);
        if (!g_router_first_ne_valid.load(std::memory_order_relaxed)) {
            g_router_first_ne = entry;
            g_router_first_ne_valid.store(true, std::memory_order_relaxed);
        }
    }

    const uint32_t index =
        g_router_ring_pos.fetch_add(1, std::memory_order_relaxed);
    g_router_ring[index % kRouterRingSize] = entry;

    static std::atomic<int> log_count{0};
    if (log_count.fetch_add(1) >= 8) return;
    LOGI("router: %s table=%#" PRIx64 " idx=%#x entry=%#" PRIx64
         " computed=%#" PRIx64 " expected=%#" PRIx64 " mask=%#" PRIx64
         " eq=%#x ne=%#x mix=%#x mod=%#x sel=%#x",
         entry.equal ? "ok" : "FAIL", entry.table, entry.index, entry.entry,
         entry.computed, entry.expected, entry.mask, entry.candidate_eq,
         entry.candidate_ne, entry.mix, entry.modulus, entry.selected);
}

// String-hash selector gate tracing.
//
// The handler interns the string at x19, hashes it with FNV-1, and stores the
// hash in the image. It then hashes `len` bytes at image+off, XORs the result
// with the sign-extended word at image+w23, and compares that with x25. The
// two candidate selectors are w29 (equal) and w27 (not equal). The length
// hook stashes w26 before the loop consumes it; the gate hook records the
// final state and computes the csel exactly as +0x3842c/+0x38430 do.
thread_local uint32_t t_gate_length = 0;
thread_local bool t_gate_length_valid = false;

extern "C" __attribute__((visibility("hidden")))
void pairip_gate_len_c(uint64_t *regs) {
    if (regs == nullptr || !g_trace_enabled.load()) return;
    t_gate_length = static_cast<uint32_t>(regs[26]);
    t_gate_length_valid = true;
}

extern "C" __attribute__((visibility("hidden")))
void pairip_gate_trace_c(uint64_t *regs) {
    if (regs == nullptr || !g_trace_enabled.load()) return;
    GateRingEntry entry{};
    entry.image = regs[8];
    entry.computed = regs[12];
    entry.expected = regs[25];
    entry.expected_offset = static_cast<uint32_t>(regs[23]);
    entry.candidate_eq = static_cast<uint32_t>(regs[29]);
    entry.candidate_ne = static_cast<uint32_t>(regs[27]);
    entry.string_ptr = regs[19];
    entry.string_len = regs[0];
    entry.data_length = t_gate_length_valid ? t_gate_length : 0;
    t_gate_length_valid = false;
    if (entry.data_length != 0 && regs[9] >= entry.image) {
        entry.data_offset = static_cast<uint32_t>(regs[9] - entry.image);
    }
    entry.fetch = t_last_vm_fetch;
    entry.op = t_last_vm_op;

    int32_t word = 0;
    if (entry.image != 0 &&
        SafeReadMemory(static_cast<uintptr_t>(entry.image) +
                           entry.expected_offset,
                       &word, sizeof(word))) {
        entry.expected_word =
            static_cast<uint64_t>(static_cast<int64_t>(word));
    }
    entry.xored = entry.computed ^ entry.expected_word;
    entry.equal = entry.xored == entry.expected ? 1u : 0u;
    entry.selected = entry.equal ? entry.candidate_eq : entry.candidate_ne;

    if (entry.string_ptr != 0 && entry.string_len != 0) {
        const size_t copy = entry.string_len < sizeof(entry.string_bytes)
                                ? static_cast<size_t>(entry.string_len)
                                : sizeof(entry.string_bytes);
        SafeReadMemory(static_cast<uintptr_t>(entry.string_ptr),
                       entry.string_bytes, copy);
    }
    if (entry.data_length != 0 && entry.image != 0) {
        const size_t copy = entry.data_length < sizeof(entry.data)
                                ? entry.data_length
                                : sizeof(entry.data);
        SafeReadMemory(static_cast<uintptr_t>(entry.image) +
                           entry.data_offset,
                       entry.data, copy);
    }

    if (entry.equal) {
        g_gate_eq_count.fetch_add(1, std::memory_order_relaxed);
    } else {
        g_gate_ne_count.fetch_add(1, std::memory_order_relaxed);
        if (!g_gate_first_ne_valid.load(std::memory_order_relaxed)) {
            g_gate_first_ne = entry;
            g_gate_first_ne_valid.store(true, std::memory_order_relaxed);
        }
    }
    const uint32_t index =
        g_gate_ring_pos.fetch_add(1, std::memory_order_relaxed);
    g_gate_ring[index % kGateRingSize] = entry;

    static std::atomic<int> log_count{0};
    if (log_count.fetch_add(1) >= 16) return;
    char text[sizeof(entry.string_bytes) + 1] = {};
    for (size_t i = 0; i < sizeof(entry.string_bytes); ++i) {
        text[i] = entry.string_bytes[i] >= 0x20 && entry.string_bytes[i] <= 0x7e
                      ? static_cast<char>(entry.string_bytes[i])
                      : '.';
    }
    LOGI("gate: %s fetch=%#x op=%#x img=%p off=%#x len=%u expoff=%#x "
         "word=%016" PRIx64 " computed=%016" PRIx64 " xor=%016" PRIx64
         " expected=%016" PRIx64 " eqc=%#x nec=%#x sel=%#x str=%p/%zu "
         "text=%s",
         entry.equal ? "ok" : "FAIL", entry.fetch, entry.op,
         reinterpret_cast<void *>(entry.image), entry.data_offset,
         entry.data_length, entry.expected_offset, entry.expected_word,
         entry.computed, entry.xored, entry.expected, entry.candidate_eq,
         entry.candidate_ne, entry.selected,
         reinterpret_cast<void *>(entry.string_ptr),
         static_cast<size_t>(entry.string_len), text);
}

// Debug-only capture of the FNV-1 input used by the klB 0x1b handler.
//
// The handler's default branch at +0x7fd64 hashes a byte range from the
// writable image. The shim runs instead of the `add x11, x9, w11, uxtw` at
// +0x7fddc, before the loop at +0x7fde0. Because the add is relocated into
// Dobby's trampoline and executes after this callback, x11 at entry is still
// the zero-extended offset; the input byte is at x9 + offset and w26 is the
// length. The 0xf656 loop calls this path once per byte; accumulate the bytes
// until the image counter at +0x266f2 reaches its phase end, then dump them.
static constexpr size_t kFnvInputMax = 0x400;
static uint8_t g_fnv_input_bytes[kFnvInputMax] = {};
static uint32_t g_fnv_input_offsets[kFnvInputMax] = {};
static size_t g_fnv_input_len = 0;
static uint32_t g_fnv_input_calls = 0;
static uint32_t g_fnv_input_outside = 0;
static uint32_t g_fnv_input_start = 0;
static uint32_t g_fnv_input_min = 0xffffffffu;
static uint32_t g_fnv_input_max = 0;
static uint64_t g_fnv_input_checksum = 0xcbf29ce484222325ull;
static uint32_t g_fnv_seen_calls = 0;
static uint32_t g_fnv_bad_counter = 0;
static uint32_t g_fnv_nonunit_length = 0;
static uint32_t g_fnv_first_length = 0;
static uint32_t g_fnv_counter_last = 0xffffffffu;
static uint32_t g_fnv_counter_min = 0xffffffffu;
static uint32_t g_fnv_counter_max = 0;
static uint32_t g_fnv_counter_bumps = 0;

static void DumpFnvInput(uintptr_t image) {
    if (g_fnv_input_len == 0) return;
    const bool contiguous =
        g_fnv_input_min != 0xffffffffu &&
        g_fnv_input_max - g_fnv_input_min + 1 == g_fnv_input_len;
    LOGI("fnv-input: image=%p calls=%u seen=%u bytes=%zu start=%#x min=%#x "
         "max=%#x contiguous=%d outside=%u checksum=%016" PRIx64
         " bad_counter=%u nonunit_len=%u first_len=%u counter_range=%#x..%#x "
         "bumps=%u",
         reinterpret_cast<void *>(image), g_fnv_input_calls, g_fnv_seen_calls,
         g_fnv_input_len, g_fnv_input_start, g_fnv_input_min,
         g_fnv_input_max, contiguous ? 1 : 0, g_fnv_input_outside,
         g_fnv_input_checksum, g_fnv_bad_counter, g_fnv_nonunit_length,
         g_fnv_first_length,
         g_fnv_counter_min == 0xffffffffu ? 0 : g_fnv_counter_min,
         g_fnv_counter_max, g_fnv_counter_bumps);

    // Log the address runs, not 419 individual lines. A contiguous capture has
    // one run; a scattered one shows exactly where the probe read.
    size_t run = 0;
    size_t i = 0;
    while (i < g_fnv_input_len) {
        const uint32_t start = g_fnv_input_offsets[i];
        size_t end = i + 1;
        if (start != 0xffffffffu) {
            while (end < g_fnv_input_len &&
                   g_fnv_input_offsets[end] == start + (end - i)) {
                ++end;
            }
        }
        if (run < 96) {
            LOGI("fnv-input run[%zu]: off=%#x len=%zu", run, start, end - i);
        } else if (run == 96) {
            LOGI("fnv-input: further runs omitted");
        }
        ++run;
        i = end;
    }

    const size_t edge = 16;
    for (size_t j = 0; j < g_fnv_input_len && j < edge; ++j) {
        LOGI("fnv-input head[%zu] off=%#x byte=%02x", j,
             g_fnv_input_offsets[j], g_fnv_input_bytes[j]);
    }
    for (size_t j = g_fnv_input_len > edge ? g_fnv_input_len - edge : 0;
         j < g_fnv_input_len && j >= edge; ++j) {
        LOGI("fnv-input tail[%zu] off=%#x byte=%02x", j,
             g_fnv_input_offsets[j], g_fnv_input_bytes[j]);
    }

    for (size_t base = 0; base < g_fnv_input_len; base += 128) {
        const size_t count =
            g_fnv_input_len - base < 128 ? g_fnv_input_len - base : 128;
        char hex[128 * 2 + 1] = {};
        char text[128 + 1] = {};
        for (size_t j = 0; j < count; ++j) {
            const uint8_t byte = g_fnv_input_bytes[base + j];
            std::snprintf(hex + j * 2, 3, "%02x", byte);
            text[j] =
                byte >= 0x20 && byte <= 0x7e ? static_cast<char>(byte) : '.';
        }
        LOGI("fnv-input[%#zx]: %s | %s", base, hex, text);
    }

    g_fnv_input_len = 0;
    g_fnv_input_calls = 0;
    g_fnv_input_outside = 0;
    g_fnv_input_start = 0;
    g_fnv_input_min = 0xffffffffu;
    g_fnv_input_max = 0;
    g_fnv_input_checksum = 0xcbf29ce484222325ull;
}

extern "C" __attribute__((visibility("hidden")))
void pairip_fnv_trace_c(uint64_t *regs) {
    if (regs == nullptr || !g_trace_enabled.load()) return;
    if (t_last_vm_fetch != 0xf656) return;
    const uintptr_t image = static_cast<uintptr_t>(regs[9]);
    const uint32_t offset = static_cast<uint32_t>(regs[11]);
    const uintptr_t buffer = image + offset;
    const uint32_t length = static_cast<uint32_t>(regs[26]);
    if (image == 0 || length == 0 || length > 0x1000) return;
    ++g_fnv_seen_calls;
    if (g_fnv_first_length == 0) g_fnv_first_length = length;
    if (length != 1) ++g_fnv_nonunit_length;

    uint32_t counter = 0;
    const bool readable = SafeReadMemory(image + 0x266f2, &counter, 4);
    if (g_fnv_counter_last != 0xffffffffu && counter > g_fnv_counter_last) {
        ++g_fnv_counter_bumps;
    }
    g_fnv_counter_last = counter;
    if (counter < g_fnv_counter_min) g_fnv_counter_min = counter;
    if (counter > g_fnv_counter_max) g_fnv_counter_max = counter;
    if (!readable || counter > 0x1000) {
        ++g_fnv_bad_counter;
        static std::atomic<int> diag_log{0};
        if (diag_log.fetch_add(1) < 8) {
            LOGI("fnv-input: skip readable=%d image=%p off=%#x buffer=%p "
                 "len=%u counter=%#x",
                 readable ? 1 : 0, reinterpret_cast<void *>(image), offset,
                 reinterpret_cast<void *>(buffer), length, counter);
        }
        return;
    }

    if (g_fnv_input_len == 0) g_fnv_input_start = counter;
    ++g_fnv_input_calls;

    const bool in_image =
        buffer >= image && buffer - image < kKlBImageLength;
    if (!in_image) {
        static std::atomic<int> outside_log{0};
        if (outside_log.fetch_add(1) < 8) {
            LOGI("fnv-input: OUTSIDE image=%p buffer=%p len=%u counter=%#x "
                 "fetch=%#x",
                 reinterpret_cast<void *>(image),
                 reinterpret_cast<void *>(buffer), length, counter,
                 t_last_vm_fetch);
        }
    }

    for (uint32_t i = 0; i < length && g_fnv_input_len < kFnvInputMax; ++i) {
        uint8_t byte = 0;
        if (!SafeReadMemory(buffer + i, &byte, 1)) break;
        const bool byte_in_image =
            buffer + i >= image && buffer + i - image < kKlBImageLength;
        const uint32_t byte_offset =
            byte_in_image ? static_cast<uint32_t>(buffer + i - image)
                          : 0xffffffffu;
        g_fnv_input_bytes[g_fnv_input_len] = byte;
        g_fnv_input_offsets[g_fnv_input_len] = byte_offset;
        if (byte_in_image) {
            if (byte_offset < g_fnv_input_min) g_fnv_input_min = byte_offset;
            if (byte_offset > g_fnv_input_max) g_fnv_input_max = byte_offset;
        } else {
            ++g_fnv_input_outside;
        }
        g_fnv_input_checksum =
            (g_fnv_input_checksum * 0x100000001b3ull) ^ byte;
        ++g_fnv_input_len;
    }

    // The observed 0xf656 loop totals 419 bytes. Dump at the phase end
    // (counter <= 1) or when that total is reached, whichever comes first.
    if (counter <= 1 || g_fnv_input_len >= 419) DumpFnvInput(image);
}

void pairip_append_trace_c(uint64_t *regs) {
    if (regs == nullptr) return;
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
    uint64_t fields[4] = {};
    const bool fields_ok = SafeReadMemory(src, fields, sizeof(fields));
    uint8_t probe = 0;
    const bool base_mapped =
        fields_ok && SafeReadMemory(static_cast<uintptr_t>(fields[2]), &probe,
                                    sizeof(probe));

    // Debug-only safe-failure guard. klB's ZIP probe asks this reader for a
    // 46-byte central-directory record whose source word is still the asset's
    // ciphertext in a hosted process. Substituting a zero-length view makes
    // the helper take its normal overflow path (an empty string) instead of
    // dereferencing the unmapped word. The VM then sees the record as absent;
    // no pointer is forged and no image slot is pre-seeded.
    if (g_reader_guard.load() && fields_ok && count == 0x2e && !base_mapped) {
        struct GuardReader {
            uint64_t vtable;
            uint64_t cursor;
            uint64_t data;
            uint64_t size;
        };
        static const uint8_t kGuardZero[8] = {};
        static thread_local GuardReader guard_reader;
        guard_reader.vtable = fields[0];
        guard_reader.cursor = 0;
        guard_reader.data =
            reinterpret_cast<uint64_t>(&kGuardZero[0]);
        guard_reader.size = 0;
        regs[0] = reinterpret_cast<uint64_t>(&guard_reader);
        regs[1] = count;
        static std::atomic<int> guard_log{0};
        if (guard_log.fetch_add(1) < 4) {
            LOGI("reader-guard: n=%zu src=%p data=%016" PRIx64
                 " size=%016" PRIx64 " lr=%p off=%#zx -> empty",
                 static_cast<size_t>(count), reinterpret_cast<void *>(src),
                 fields[2], fields[3], reinterpret_cast<void *>(lr),
                 lr >= base ? lr - base : 0);
        }
    }

    if (!g_trace_enabled.load()) {
        in_trace = false;
        return;
    }

    // Only a bad base near a large read is interesting; logging every
    // append call floods the trace and perturbs klB.
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
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallApkZeroMap(
    JNIEnv *env, jclass, jstring apk_path) {
    const std::string path = JStringToUtf8(env, apk_path);
    std::string error;
    if (!InstallApkZeroMap(path.c_str(), &error)) {
        return env->NewStringUTF(error.empty() ? "install failed" : error.c_str());
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallApkCdMap(
    JNIEnv *env, jclass, jstring apk_path) {
    const std::string path = JStringToUtf8(env, apk_path);
    std::string error;
    if (!InstallApkCdMap(path.c_str(), &error)) {
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallSkipKlb(JNIEnv *,
                                                                jclass) {
    // Only the ExecuteProgram hook is needed: skipping happens inside the
    // trampoline, so the real libpairipcore code and libunity DT_INIT stub
    // stay unmodified.
    if (!InstallExecuteProgramHook()) return JNI_FALSE;
    g_skip_klb.store(true);
    LOGI("exec-prog: klB skip enabled");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bepinex_android_hooks_PairIpShield_nativeInstallReaderGuard(
    JNIEnv *, jclass) {
    // Only the reader helper is hooked; the guard fires inside the trampoline
    // for a 46-byte read whose source word is still ciphertext, so no PairIP
    // code or image byte is modified.
    std::lock_guard<std::mutex> lock(g_trace_mutex);
    uintptr_t base = 0;
    if (!FindPairIpBase(&base)) return JNI_FALSE;
    if (!InstallAppendHookLocked(base)) return JNI_FALSE;
    g_reader_guard.store(true);
    LOGI("reader-guard: enabled");
    return JNI_TRUE;
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
