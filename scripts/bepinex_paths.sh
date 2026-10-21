#!/usr/bin/env bash
# Shared paths for the BepInEx upstream source tree.
#
# Source this; do not execute. It defines no behaviour, only where things live.
#
# WHY THIS EXISTS
#
# The patched upstream tree used to be the third_party/BepInEx submodule, and
# its path was written out in six places across three workflows and four scripts.
# A pin migration that moves it therefore had six chances to move only some of
# them - and the failure is silent in the worst way: apply_patches.sh clones to
# the new location while a workflow still restores and builds the old one, so the
# job passes against a stale tree, or fails on a directory nothing created.
#
# One definition, sourced everywhere, makes that class of half-migration
# impossible to express.
#
# WHY build/bepinex AND NOT third_party/BepInEx
#
# build/ is gitignored (.gitignore:2), so the clone can never leave the working
# tree dirty. Keeping the name third_party/BepInEx would also mean that during the
# migration window the same path means two different things - a submodule on one
# command and a scratch clone on the next - which is the ambiguity this whole
# phase exists to remove.

# Repository root, resolved from this file's location.
BEPINEX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Where the patched upstream tree is cloned and built.
# Override for an out-of-tree build; every consumer reads this same value.
BEPINEX_SRC="${BEPINEX_SRC:-$BEPINEX_ROOT/build/bepinex}"

# The pin file this tree is cloned from.
BEPINEX_PIN_FILE="${BEPINEX_PIN_FILE:-$BEPINEX_ROOT/provenance/bepinex.env}"

# The patch queue replayed onto that clone, as a glob (see provenance/bepinex.env
# for why this is a pattern and not a list of twelve filenames).
BEPINEX_PATCH_GLOB="${BEPINEX_PATCH_GLOB:-$BEPINEX_ROOT/patches/*.patch}"

# The recorded tree-object hash of a complete replay. apply_patches.sh checks
# it, record_patched_tree.sh writes it, and a fixture can point both at a
# throwaway file instead of the repository's record.
BEPINEX_PATCHED_TREE_FILE="${BEPINEX_PATCHED_TREE_FILE:-$BEPINEX_ROOT/references/patched-tree.sha256}"