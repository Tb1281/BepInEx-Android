# XZ Embedded (vendored)

Minimal XZ decompressor used by hookengine to read the `.gnu_debugdata`
section of the on-device `libart.so` (retail ART builds strip `.symtab`;
the full symbol table survives only as an xz-compressed mini-ELF in
`.gnu_debugdata`, and LSPlant needs those symbols).

- Source: https://github.com/tukaani-project/xz-embedded
- Commit: `ae63ae3a36ed01724674e8f3d750dc47bf125410` (2024-12-30)
- Files: `xz.h` (from `linux/include/linux/xz.h`), `xz_config.h` (from
  `userspace/xz_config.h`), `xz_private.h`, `xz_stream.h`, `xz_lzma2.h`,
  `xz_dec_stream.c`, `xz_dec_lzma2.c`, `xz_dec_bcj.c`, `xz_crc32.c`
  (from `linux/lib/xz/`), copied unmodified.
- License: XZ Embedded is public domain (the files carry
  `SPDX-License-Identifier: 0BSD` headers in this revision).
