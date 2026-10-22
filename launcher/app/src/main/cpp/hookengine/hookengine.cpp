// libhookengine.so — the launcher's Java-method hook engine.
//
// A thin JNI shim over LSPlant (LGPL-3.0, pinned in provenance/lsplant.env and
// built from source by scripts/build_native_deps.sh into liblsplant.so). It
// replaces Pine, whose Anti-996 license is incompatible with this project's
// GPL-3.0 (see docs/adr/0019 and the ADR that supersedes it) and whose Java
// bridge caused the CheckReferenceResult crashes analysed in
// docs/release-crash-investigation.md: Pine captured intercepted frames as raw
// register addresses and converted them to jobject lazily, so a GC between
// capture and conversion left ART validating a stale reference. LSPlant's
// callbacks are real Java methods holding real references, so that failure
// mode does not exist here.
//
// The Kotlin facade (com.bepinex.android.hooks) presents a Pine-shaped
// MethodHook/CallFrame API over this shim so the hook sites keep their
// before/after structure.

#include <jni.h>

#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "dobby.h"
#include "lsplant.hpp"
#include "xz.h"

#define LOG_TAG "HookEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// libart symbol resolution.
//
// LSPlant needs ART symbols that are not exported in .dynsym, so the resolver
// parses the on-disk libart.so once at load time and answers lookups as
// load_bias + st_value. Retail libart.so is stripped: it keeps only .dynsym
// in the main image, and the full .symtab survives xz-compressed inside the
// .gnu_debugdata section (a mini-ELF), so both images are parsed and merged.
// The table is built before lsplant::Init runs, because the resolver
// callbacks must not do I/O or call JNI while ART threads are suspended
// during Init.
// ---------------------------------------------------------------------------

std::unordered_map<std::string, uintptr_t> g_art_symbols;
std::vector<std::pair<std::string, uintptr_t>> g_art_symbol_list;
uintptr_t g_art_load_bias = 0;
bool g_art_ready = false;

bool FindLibart(std::string &path, uintptr_t &bias) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) return false;
    char line[1024];
    bool found = false;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (strstr(line, "libart.so") == nullptr) continue;
        char *slash = strchr(line, '/');
        if (slash == nullptr) continue;
        unsigned long start = 0, offset = 0;
        if (sscanf(line, "%lx-%*lx %*4s %lx", &start, &offset) != 2) continue;
        std::string candidate(slash);
        candidate.erase(candidate.find_last_not_of(" \n\r\t") + 1);
        if (candidate.size() < 9 ||
            candidate.compare(candidate.size() - 9, 9, "libart.so") != 0) {
            continue;
        }
        // The first mapping of the file is its first PT_LOAD segment, whose
        // p_vaddr == p_offset, so load_bias = start - offset for that line.
        path = candidate;
        bias = static_cast<uintptr_t>(start) - static_cast<uintptr_t>(offset);
        found = true;
        break;
    }
    fclose(maps);
    return found;
}

// Merges every SHT_SYMTAB/SHT_DYNSYM entry of one in-memory ELF64 image into
// the resolver tables. Safe on malformed input: anything out of bounds is
// skipped.
void CollectElfSymbols(const uint8_t *base, size_t size) {
    if (size < sizeof(Elf64_Ehdr)) return;
    const auto *ehdr = reinterpret_cast<const Elf64_Ehdr *>(base);
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr->e_ident[EI_CLASS] != ELFCLASS64 ||
        ehdr->e_shoff == 0 || ehdr->e_shentsize != sizeof(Elf64_Shdr) ||
        ehdr->e_shoff + static_cast<uint64_t>(ehdr->e_shnum) * sizeof(Elf64_Shdr) > size) {
        return;
    }
    const auto *shdr = reinterpret_cast<const Elf64_Shdr *>(base + ehdr->e_shoff);
    for (int i = 0; i < ehdr->e_shnum; ++i) {
        const Elf64_Shdr &sec = shdr[i];
        if (sec.sh_type != SHT_SYMTAB && sec.sh_type != SHT_DYNSYM) continue;
        if (sec.sh_link >= static_cast<uint32_t>(ehdr->e_shnum)) continue;
        const Elf64_Shdr &str_sec = shdr[sec.sh_link];
        if (sec.sh_offset + sec.sh_size > size ||
            str_sec.sh_offset + str_sec.sh_size > size) {
            continue;
        }
        const auto *syms = reinterpret_cast<const Elf64_Sym *>(base + sec.sh_offset);
        const char *strs = reinterpret_cast<const char *>(base) + str_sec.sh_offset;
        size_t count = sec.sh_size / sizeof(Elf64_Sym);
        for (size_t s = 0; s < count; ++s) {
            const Elf64_Sym &sym = syms[s];
            if (sym.st_name == 0 || sym.st_value == 0) continue;
            if (sym.st_name >= str_sec.sh_size) continue;
            std::string name(strs + sym.st_name);
            g_art_symbol_list.emplace_back(name, static_cast<uintptr_t>(sym.st_value));
            g_art_symbols.try_emplace(name, static_cast<uintptr_t>(sym.st_value));
        }
    }
}

// Finds a section by name in an in-memory ELF64 image, or nullptr.
const Elf64_Shdr *FindSection(const uint8_t *base, size_t size, const char *wanted) {
    if (size < sizeof(Elf64_Ehdr)) return nullptr;
    const auto *ehdr = reinterpret_cast<const Elf64_Ehdr *>(base);
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr->e_ident[EI_CLASS] != ELFCLASS64 ||
        ehdr->e_shoff == 0 || ehdr->e_shentsize != sizeof(Elf64_Shdr) ||
        ehdr->e_shstrndx >= ehdr->e_shnum ||
        ehdr->e_shoff + static_cast<uint64_t>(ehdr->e_shnum) * sizeof(Elf64_Shdr) > size) {
        return nullptr;
    }
    const auto *shdr = reinterpret_cast<const Elf64_Shdr *>(base + ehdr->e_shoff);
    const Elf64_Shdr &shstr = shdr[ehdr->e_shstrndx];
    if (shstr.sh_offset + shstr.sh_size > size) return nullptr;
    const char *names = reinterpret_cast<const char *>(base) + shstr.sh_offset;
    for (int i = 0; i < ehdr->e_shnum; ++i) {
        const Elf64_Shdr &sec = shdr[i];
        if (sec.sh_name >= shstr.sh_size) continue;
        if (std::strcmp(names + sec.sh_name, wanted) == 0) return &sec;
    }
    return nullptr;
}

// Decompresses one complete xz stream (the .gnu_debugdata payload).
bool DecompressXz(const uint8_t *in, size_t in_size, std::vector<uint8_t> &out) {
    xz_crc32_init();
    // dict_max is a hard cap in multi-call mode (0 rejects every stream with
    // XZ_MEMLIMIT_ERROR); 64 MiB is far above the few-tens-of-KiB
    // dictionaries Android's .gnu_debugdata streams actually request, and
    // the allocation follows the stream's real dictionary size.
    struct xz_dec *dec = xz_dec_init(XZ_DYNALLOC, 64U << 20);
    if (dec == nullptr) return false;
    out.assign(in_size * 3 + 65536, 0);
    struct xz_buf buf {};
    buf.in = in;
    buf.in_size = in_size;
    enum xz_ret ret = XZ_OK;
    while (ret == XZ_OK) {
        buf.out = out.data();
        buf.out_size = out.size();
        ret = xz_dec_run(dec, &buf);
        if (ret == XZ_OK && buf.out_pos == buf.out_size) {
            out.resize(out.size() * 2);
        }
    }
    xz_dec_end(dec);
    if (ret != XZ_STREAM_END) return false;
    out.resize(buf.out_pos);
    return true;
}

bool ParseArtSymbols(const std::string &path) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        return false;
    }
    auto size = static_cast<size_t>(st.st_size);
    void *base = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (base == MAP_FAILED) return false;
    const auto *bytes = static_cast<const uint8_t *>(base);

    CollectElfSymbols(bytes, size);

    // The stripped main image has no .symtab; the full table is inside
    // .gnu_debugdata. Failure here is not fatal to the parse itself — the
    // caller sees a smaller table — so log and continue.
    if (const Elf64_Shdr *debug = FindSection(bytes, size, ".gnu_debugdata")) {
        if (debug->sh_offset + debug->sh_size <= size && debug->sh_size > 0) {
            std::vector<uint8_t> mini;
            if (DecompressXz(bytes + debug->sh_offset,
                             static_cast<size_t>(debug->sh_size), mini)) {
                CollectElfSymbols(mini.data(), mini.size());
                LOGI(".gnu_debugdata merged: %zu symbols total", g_art_symbols.size());
            } else {
                LOGE(".gnu_debugdata decompression failed");
            }
        }
    }
    munmap(base, size);
    return !g_art_symbols.empty();
}

void *ResolveArtSymbol(std::string_view name, bool prefix) {
    if (!g_art_ready) return nullptr;
    if (!prefix) {
        auto it = g_art_symbols.find(std::string(name));
        if (it == g_art_symbols.end()) return nullptr;
        return reinterpret_cast<void *>(g_art_load_bias + it->second);
    }
    for (const auto &[candidate, value] : g_art_symbol_list) {
        if (candidate.size() >= name.size() &&
            std::string_view(candidate).substr(0, name.size()) == name) {
            return reinterpret_cast<void *>(g_art_load_bias + value);
        }
    }
    return nullptr;
}

// ---------------------------------------------------------------------------
// Inline hooker backed by Dobby, following LSPlant's own test app: make the
// target page writable before Dobby patches it.
// ---------------------------------------------------------------------------

void *InlineHooker(void *target, void *hooker) {
    static const long kPageSize = sysconf(_SC_PAGESIZE);
    auto start = reinterpret_cast<uintptr_t>(target) & ~static_cast<uintptr_t>(kPageSize - 1);
    mprotect(reinterpret_cast<void *>(start), static_cast<size_t>(kPageSize) * 2,
             PROT_READ | PROT_WRITE | PROT_EXEC);
    void *origin = nullptr;
    if (DobbyHook(target, hooker, &origin) == 0) return origin;
    return nullptr;
}

bool InlineUnhooker(void *func) { return DobbyDestroy(func) == 0; }

bool g_lsplant_ok = false;

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    std::string art_path;
    if (FindLibart(art_path, g_art_load_bias) && ParseArtSymbols(art_path)) {
        g_art_ready = true;
        LOGI("libart symbols: %zu entries from %s", g_art_symbols.size(), art_path.c_str());
    } else {
        LOGE("libart symbol table unavailable (path=%s)", art_path.c_str());
    }

    lsplant::InitInfo info{
        .inline_hooker = InlineHooker,
        .inline_unhooker = InlineUnhooker,
        .art_symbol_resolver =
            [](std::string_view name) { return ResolveArtSymbol(name, false); },
        .art_symbol_prefix_resolver =
            [](std::string_view prefix) { return ResolveArtSymbol(prefix, true); },
    };
    g_lsplant_ok = lsplant::Init(env, info);
    LOGI("LSPlant init: %s", g_lsplant_ok ? "ok" : "FAILED");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_bepinex_android_hooks_HookEngine_nativeIsInitialized(JNIEnv *, jobject) {
    return g_lsplant_ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL Java_com_bepinex_android_hooks_HookEngine_nativeHook(
    JNIEnv *env, jobject, jobject target, jobject hooker, jobject callback_method) {
    if (!g_lsplant_ok) return nullptr;
    return lsplant::Hook(env, target, hooker, callback_method);
}

JNIEXPORT jboolean JNICALL Java_com_bepinex_android_hooks_HookEngine_nativeUnhook(
    JNIEnv *env, jobject, jobject target) {
    return lsplant::UnHook(env, target) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_bepinex_android_hooks_HookEngine_nativeIsHooked(
    JNIEnv *env, jobject, jobject target) {
    return lsplant::IsHooked(env, target) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
