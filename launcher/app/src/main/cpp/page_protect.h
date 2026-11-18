// page_protect.h — shared read-only-page patch window for Dobby.
//
// Inline hook engines patch code that lives in read-only (or read-execute)
// mappings. The helpers here snapshot a page's live permission bits from
// /proc/self/maps, temporarily make the covering pages writable, and restore the
// snapshot afterwards, so a hook never leaves executable memory writable (W^X).
//
// FindLibart() in hookengine/hookengine.cpp parses /proc/self/maps with the
// same scanf idiom but needs the mapping's file offset; it is intentionally
// kept separate from the permission reader here.

#ifndef BEPINEX_PAGE_PROTECT_H
#define BEPINEX_PAGE_PROTECT_H

#include <sys/mman.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <vector>

namespace page_protect {

// Only the page(s) covering Dobby's inline patch are touched. Dobby overwrites
// the first few instructions at the target and places the relocated copy in a
// trampoline it allocates elsewhere (LSPlant may additionally supply an
// executable_memory_allocator), so only the inline patch lives in this window.
// 32 bytes covers arm64's current worst case: 4 instructions (16 B) plus a
// literal pool. If Dobby's patch span ever grows, kDobbyPatchWindow must grow
// with it or the patch will fault on the unwritable tail.
constexpr size_t kDobbyPatchWindow = 32;

struct PageProtection {
    void *address;
    int prot;
};

inline size_t PageSize() {
    static const size_t kSize = [] {
        const long size = sysconf(_SC_PAGESIZE);
        return size > 0 ? static_cast<size_t>(size) : size_t{4096};
    }();
    return kSize;
}

// Decodes the "rwxp" permission column of a /proc/self/maps line.
inline int DecodePageProtection(const char *perms) {
    int prot = 0;
    if (perms[0] == 'r') prot |= PROT_READ;
    if (perms[1] == 'w') prot |= PROT_WRITE;
    if (perms[2] == 'x') prot |= PROT_EXEC;
    return prot;
}

// Fills one PageProtection per entry in pages from a single /proc/self/maps
// read. A page missing from maps falls back to PROT_READ | PROT_EXEC: restore
// must still drop write access, and hook targets are code.
inline void ReadPageProtections(const std::vector<uintptr_t> &pages,
                                std::vector<PageProtection> &out) {
    // prot == -1 marks "not seen in /proc/self/maps yet".
    out.clear();
    out.reserve(pages.size());
    for (uintptr_t page : pages) {
        out.push_back({reinterpret_cast<void *>(page), -1});
    }

    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) {
        for (PageProtection &page : out) page.prot = PROT_READ | PROT_EXEC;
        return;
    }

    char line[1024];
    for (size_t remaining = pages.size();
         remaining > 0 && fgets(line, sizeof(line), maps) != nullptr;) {
        unsigned long start = 0;
        unsigned long end = 0;
        char perms[5] = {};
        if (sscanf(line, "%lx-%lx %4s", &start, &end, perms) != 3) continue;
        for (size_t i = 0; i < pages.size(); ++i) {
            if (out[i].prot >= 0) continue;
            if (pages[i] < start || pages[i] >= end) continue;
            out[i].prot = DecodePageProtection(perms);
            --remaining;
        }
    }
    fclose(maps);

    for (PageProtection &page : out) {
        if (page.prot < 0) page.prot = PROT_READ | PROT_EXEC;
    }
}

// Snapshots the original protection of every page covering [address, address +
// window) with one /proc/self/maps read.
inline std::vector<PageProtection> SnapshotPageProtections(
        uintptr_t address, size_t window = kDobbyPatchWindow) {
    if (window == 0) window = 1;
    const uintptr_t page_size = PageSize();
    const uintptr_t first = address & ~(page_size - 1);
    const uintptr_t last = (address + window - 1) & ~(page_size - 1);

    std::vector<uintptr_t> pages;
    for (uintptr_t page = first; page <= last; page += page_size) {
        pages.push_back(page);
    }

    std::vector<PageProtection> snapshots;
    ReadPageProtections(pages, snapshots);
    return snapshots;
}

// Applies one protection to each snapshotted page. Returns false when any
// mprotect fails; the caller still owns the snapshot and must restore it.
inline bool SetPagesProtection(const std::vector<PageProtection> &pages, int prot) {
    bool ok = true;
    for (const PageProtection &page : pages) {
        if (mprotect(page.address, PageSize(), prot) != 0) ok = false;
    }
    return ok;
}

// Restores every page to the protection captured by SnapshotPageProtections.
inline void RestorePageProtections(const std::vector<PageProtection> &pages) {
    for (const PageProtection &page : pages) {
        mprotect(page.address, PageSize(), page.prot);
    }
}

// Runs one Dobby operation with the covering pages temporarily writable, then
// always restores their previous protection (including when the operation
// throws). A failed make-writable is treated as a failed hook: the pages that
// were already changed are restored and the operation is not run.
template <typename Operation>
bool WithWritablePages(uintptr_t address, Operation operation) {
    const std::vector<PageProtection> pages =
        SnapshotPageProtections(address, kDobbyPatchWindow);

    struct RestoreOnExit {
        const std::vector<PageProtection> &pages;
        ~RestoreOnExit() { RestorePageProtections(pages); }
    } restore{pages};

    if (!SetPagesProtection(pages, PROT_READ | PROT_WRITE | PROT_EXEC)) {
        return false;
    }
    return static_cast<bool>(operation());
}

}  // namespace page_protect

#endif  // BEPINEX_PAGE_PROTECT_H
