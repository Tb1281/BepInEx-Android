/*
 * Debug-only entry shim for the PairIP VM string-append helper.
 *
 * The helper at libpairipcore.so+0x8d460 takes its destination buffer in x8,
 * which is outside the AAPCS argument registers, so a C replacement would
 * clobber it before forwarding the call. This shim saves the full register
 * file, calls the C logger, restores every register, and tail-branches to
 * Dobby's trampoline.
 *
 * Not used by production builds: the shield only installs it when the
 * debug-only `pairip_trace` extra is present.
 */

    .text
    .global pairip_append_trace_entry
    .type pairip_append_trace_entry, %function
    .p2align 4
pairip_append_trace_entry:
    sub     sp, sp, #0x110
    stp     x0, x1, [sp, #0x00]
    stp     x2, x3, [sp, #0x10]
    stp     x4, x5, [sp, #0x20]
    stp     x6, x7, [sp, #0x30]
    stp     x8, x9, [sp, #0x40]
    stp     x10, x11, [sp, #0x50]
    stp     x12, x13, [sp, #0x60]
    stp     x14, x15, [sp, #0x70]
    stp     x16, x17, [sp, #0x80]
    stp     x18, x19, [sp, #0x90]
    stp     x20, x21, [sp, #0xa0]
    stp     x22, x23, [sp, #0xb0]
    stp     x24, x25, [sp, #0xc0]
    stp     x26, x27, [sp, #0xd0]
    stp     x28, x29, [sp, #0xe0]
    mrs     x9, nzcv
    str     x9, [sp, #0xf0]
    str     x30, [sp, #0xf8]

    mov     x0, sp
    bl      pairip_append_trace_c

    ldr     x9, [sp, #0xf0]
    msr     nzcv, x9
    ldp     x0, x1, [sp, #0x00]
    ldp     x2, x3, [sp, #0x10]
    ldp     x4, x5, [sp, #0x20]
    ldp     x6, x7, [sp, #0x30]
    ldp     x8, x9, [sp, #0x40]
    ldp     x10, x11, [sp, #0x50]
    ldp     x12, x13, [sp, #0x60]
    ldp     x14, x15, [sp, #0x70]
    ldp     x16, x17, [sp, #0x80]
    ldp     x18, x19, [sp, #0x90]
    ldp     x20, x21, [sp, #0xa0]
    ldp     x22, x23, [sp, #0xb0]
    ldp     x24, x25, [sp, #0xc0]
    ldp     x26, x27, [sp, #0xd0]
    ldp     x28, x29, [sp, #0xe0]
    ldr     x30, [sp, #0xf8]
    add     sp, sp, #0x110

    adrp    x16, g_pairip_append_original
    ldr     x16, [x16, :lo12:g_pairip_append_original]
    br      x16

    .size pairip_append_trace_entry, . - pairip_append_trace_entry

/*
 * Debug-only shim for the instruction just after executeVM copies the VM
 * bytecode into its writable image (libpairipcore.so+0x6aa20).
 *
 * klB's first ZIP check reads a string view from two image literals that a
 * hosted launch leaves unresolved, so the memmove source is still the asset's
 * ciphertext. Patching the image in place here is invisible to the VM's code
 * because this is the loader, not the program. Same full-register contract as
 * the append shim: save everything, call C, restore, tail-branch home.
 */

    .text
    .global pairip_copy_patch_entry
    .type pairip_copy_patch_entry, %function
    .p2align 4
pairip_copy_patch_entry:
    sub     sp, sp, #0x110
    stp     x0, x1, [sp, #0x00]
    stp     x2, x3, [sp, #0x10]
    stp     x4, x5, [sp, #0x20]
    stp     x6, x7, [sp, #0x30]
    stp     x8, x9, [sp, #0x40]
    stp     x10, x11, [sp, #0x50]
    stp     x12, x13, [sp, #0x60]
    stp     x14, x15, [sp, #0x70]
    stp     x16, x17, [sp, #0x80]
    stp     x18, x19, [sp, #0x90]
    stp     x20, x21, [sp, #0xa0]
    stp     x22, x23, [sp, #0xb0]
    stp     x24, x25, [sp, #0xc0]
    stp     x26, x27, [sp, #0xd0]
    stp     x28, x29, [sp, #0xe0]
    mrs     x9, nzcv
    str     x9, [sp, #0xf0]
    str     x30, [sp, #0xf8]

    mov     x0, sp
    bl      pairip_copy_patch_c

    ldr     x9, [sp, #0xf0]
    msr     nzcv, x9
    ldp     x0, x1, [sp, #0x00]
    ldp     x2, x3, [sp, #0x10]
    ldp     x4, x5, [sp, #0x20]
    ldp     x6, x7, [sp, #0x30]
    ldp     x8, x9, [sp, #0x40]
    ldp     x10, x11, [sp, #0x50]
    ldp     x12, x13, [sp, #0x60]
    ldp     x14, x15, [sp, #0x70]
    ldp     x16, x17, [sp, #0x80]
    ldp     x18, x19, [sp, #0x90]
    ldp     x20, x21, [sp, #0xa0]
    ldp     x22, x23, [sp, #0xb0]
    ldp     x24, x25, [sp, #0xc0]
    ldp     x26, x27, [sp, #0xd0]
    ldp     x28, x29, [sp, #0xe0]
    ldr     x30, [sp, #0xf8]
    add     sp, sp, #0x110

    adrp    x16, g_pairip_copy_original
    ldr     x16, [x16, :lo12:g_pairip_copy_original]
    br      x16

    .size pairip_copy_patch_entry, . - pairip_copy_patch_entry

/*
 * Debug-only shim for the VM's class resolver (libpairipcore.so+0x8c570).
 *
 * The resolver takes a descriptor in x0: [x0] points at a string-table
 * descriptor, [x0+0xc] is the table index and [x0+0x10] caches the resolved
 * jclass. Logging the descriptor, the index and the caller's return address
 * tells us whether klB is decoding a stale table entry (hosted-mode
 * unresolved literal) or resolving a class the VM expects to exist.
 */

    .text
    .global pairip_class_trace_entry
    .type pairip_class_trace_entry, %function
    .p2align 4
pairip_class_trace_entry:
    sub     sp, sp, #0x110
    stp     x0, x1, [sp, #0x00]
    stp     x2, x3, [sp, #0x10]
    stp     x4, x5, [sp, #0x20]
    stp     x6, x7, [sp, #0x30]
    stp     x8, x9, [sp, #0x40]
    stp     x10, x11, [sp, #0x50]
    stp     x12, x13, [sp, #0x60]
    stp     x14, x15, [sp, #0x70]
    stp     x16, x17, [sp, #0x80]
    stp     x18, x19, [sp, #0x90]
    stp     x20, x21, [sp, #0xa0]
    stp     x22, x23, [sp, #0xb0]
    stp     x24, x25, [sp, #0xc0]
    stp     x26, x27, [sp, #0xd0]
    stp     x28, x29, [sp, #0xe0]
    mrs     x9, nzcv
    str     x9, [sp, #0xf0]
    str     x30, [sp, #0xf8]

    mov     x0, sp
    bl      pairip_class_trace_c

    ldr     x9, [sp, #0xf0]
    msr     nzcv, x9
    ldp     x0, x1, [sp, #0x00]
    ldp     x2, x3, [sp, #0x10]
    ldp     x4, x5, [sp, #0x20]
    ldp     x6, x7, [sp, #0x30]
    ldp     x8, x9, [sp, #0x40]
    ldp     x10, x11, [sp, #0x50]
    ldp     x12, x13, [sp, #0x60]
    ldp     x14, x15, [sp, #0x70]
    ldp     x16, x17, [sp, #0x80]
    ldp     x18, x19, [sp, #0x90]
    ldp     x20, x21, [sp, #0xa0]
    ldp     x22, x23, [sp, #0xb0]
    ldp     x24, x25, [sp, #0xc0]
    ldp     x26, x27, [sp, #0xd0]
    ldp     x28, x29, [sp, #0xe0]
    ldr     x30, [sp, #0xf8]
    add     sp, sp, #0x110

    adrp    x16, g_pairip_class_original
    ldr     x16, [x16, :lo12:g_pairip_class_original]
    br      x16

    .size pairip_class_trace_entry, . - pairip_class_trace_entry

/*
 * Debug-only shim for the two ZIP-entry name comparisons in the PairIP ZIP
 * reader (+0x3e4d0 and +0x3e544). The reader hashes a central-directory
 * entry's name, looks the hash up in a table of expected names, and then
 * memcmps the full name. Replacing those two `bl memcmp@plt` instructions
 * with this shim logs what the VM expects and what our synthetic central
 * directory provided, then performs the comparison.
 */

    .text
    .global pairip_name_trace_entry
    .type pairip_name_trace_entry, %function
    .p2align 4
pairip_name_trace_entry:
    bti     c
    stp     x29, x30, [sp, #-0x30]!
    mov     x29, sp
    stp     x0, x1, [sp, #0x10]
    str     x2, [sp, #0x20]
    bl      pairip_name_trace_c
    ldp     x0, x1, [sp, #0x10]
    ldr     x2, [sp, #0x20]
    ldp     x29, x30, [sp], #0x30
    b       memcmp@plt

    .size pairip_name_trace_entry, . - pairip_name_trace_entry
