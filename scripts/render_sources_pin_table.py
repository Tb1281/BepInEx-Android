#!/usr/bin/env python3
"""Render the human-readable Pin Table from the Source pins.

`provenance/*.env` is the only authored pin source.  The Pin Table in
`provenance/SOURCES.md` is a projection: this script fills
`{{dependency.field}}` placeholders from the env files and replaces the block
between the two generated-block markers.

Usage:
  scripts/render_sources_pin_table.py             # rewrite SOURCES.md
  scripts/render_sources_pin_table.py --check     # fail if it is stale

The `--check` mode is the CI gate.  It never writes, so a hand-edited or stale
table fails closed instead of being silently repaired.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

BEGIN = "<!-- BEGIN GENERATED PIN TABLE -->"
END = "<!-- END GENERATED PIN TABLE -->"
PLACEHOLDER_RE = re.compile(r"\{\{([a-z0-9_]+)\.([a-z0-9_]+)\}\}")

# Every env-backed row in the Pin Table.  Keeping the file/key pairs explicit
# makes a renamed or missing pin fail here instead of rendering a blank cell.
PIN_KEYS: dict[str, tuple[str, str]] = {
    "bepinex.url": ("bepinex.env", "UPSTREAM_URL"),
    "bepinex.ref": ("bepinex.env", "UPSTREAM_REF"),
    "bepinex.commit": ("bepinex.env", "UPSTREAM_COMMIT"),
    "bepinex.patch_queue": ("bepinex.env", "BEPINEX_PATCH_QUEUE"),
    "dobby.url": ("dobby.env", "UPSTREAM_URL"),
    "dobby.ref": ("dobby.env", "UPSTREAM_REF"),
    "dobby.commit": ("dobby.env", "UPSTREAM_COMMIT"),
    "pine.url": ("pine.env", "UPSTREAM_URL"),
    "pine.ref": ("pine.env", "UPSTREAM_REF"),
    "pine.commit": ("pine.env", "UPSTREAM_COMMIT"),
    "pine.xz_commit": ("pine.env", "PINE_SUBMODULE_XZ_COMMIT"),
    "monomod.url": ("monomod.env", "UPSTREAM_URL"),
    "monomod.ref": ("monomod.env", "UPSTREAM_REF"),
    "monomod.commit": ("monomod.env", "UPSTREAM_COMMIT"),
    "monomod.patch_file": ("monomod.env", "PATCH_FILE"),
    "monomod.package_version": ("monomod.env", "MONOMOD_PACKAGE_VERSION"),
    "il2cppinterop.url": ("il2cppinterop.env", "UPSTREAM_URL"),
    "il2cppinterop.ref": ("il2cppinterop.env", "UPSTREAM_REF"),
    "il2cppinterop.commit": ("il2cppinterop.env", "UPSTREAM_COMMIT"),
    "il2cppinterop.patch_file": ("il2cppinterop.env", "PATCH_FILE"),
    "il2cppinterop.package_version": ("il2cppinterop.env", "PACKAGE_VERSION"),
    "dotnet.pack": ("dotnet-runtime.env", "DOTNET_RUNTIME_PACK"),
    "dotnet.version": ("dotnet-runtime.env", "DOTNET_RUNTIME_VERSION"),
    "dotnet.pack_sha256": ("dotnet-runtime.env", "DOTNET_RUNTIME_PACK_SHA256"),
}


class RenderError(Exception):
    """A source pin or template defect that must not produce a table."""


def read_env(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise RenderError(f"missing env file: {path}")
    values: dict[str, str] = {}
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, raw_value = line.split("=", 1)
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            continue
        if key in values:
            raise RenderError(f"{path}:{lineno}: duplicate {key}")
        value = raw_value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def load_pin_values(root: Path) -> dict[str, str]:
    cache: dict[Path, dict[str, str]] = {}
    values: dict[str, str] = {}
    for placeholder, (env_name, key) in PIN_KEYS.items():
        path = root / "provenance" / env_name
        if path not in cache:
            cache[path] = read_env(path)
        if key not in cache[path]:
            raise RenderError(f"missing {key} in {path}")
        value = cache[path][key]
        if not value:
            raise RenderError(f"empty {key} in {path}")
        if any(char in value for char in ("\n", "\r", "|")):
            raise RenderError(
                f"{placeholder} from {path} cannot be rendered in a Markdown table"
            )
        values[placeholder] = value
    return values


def render_template(template: str, values: dict[str, str]) -> str:
    used: set[str] = set()

    def replace(match: re.Match[str]) -> str:
        key = f"{match.group(1)}.{match.group(2)}"
        used.add(key)
        if key not in values:
            raise RenderError(f"unknown placeholder: {match.group(0)}")
        return values[key]

    rendered = PLACEHOLDER_RE.sub(replace, template)
    if PLACEHOLDER_RE.search(rendered):
        raise RenderError("unresolved placeholder remains in rendered table")
    missing = sorted(set(values) - used)
    if missing:
        raise RenderError("template does not use pin(s): " + ", ".join(missing))
    return rendered


def generated_block(rendered: str) -> str:
    return f"{BEGIN}\n{rendered.strip(chr(10))}\n{END}"


def replace_block(text: str, block: str) -> str:
    if text.count(BEGIN) != 1 or text.count(END) != 1:
        raise RenderError(f"expected exactly one {BEGIN} and one {END} marker")
    start = text.index(BEGIN)
    end = text.index(END, start) + len(END)
    return text[:start] + block + text[end:]


def current_block(text: str) -> str:
    if text.count(BEGIN) != 1 or text.count(END) != 1:
        raise RenderError(f"expected exactly one {BEGIN} and one {END} marker")
    start = text.index(BEGIN)
    end = text.index(END, start) + len(END)
    return text[start:end]


def resolve(root: Path, raw: str) -> Path:
    path = Path(raw)
    return path if path.is_absolute() else root / path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if SOURCES.md is stale")
    parser.add_argument("--root", default=Path(__file__).resolve().parents[1], type=Path)
    parser.add_argument("--sources", default="provenance/SOURCES.md")
    parser.add_argument("--template", default="provenance/pin-table.template.md")
    args = parser.parse_args()

    root = args.root.resolve()
    sources = resolve(root, args.sources)
    template = resolve(root, args.template)

    try:
        template_text = template.read_text(encoding="utf-8")
        values = load_pin_values(root)
        rendered = render_template(template_text, values)
        block = generated_block(rendered)
        text = sources.read_text(encoding="utf-8")
        old_block = current_block(text)
        if args.check:
            if old_block != block:
                print(
                    f"error: {sources} Pin Table is stale; run "
                    "scripts/render_sources_pin_table.py",
                    file=sys.stderr,
                )
                return 1
            print(f"{sources} Pin Table is current")
            return 0
        if old_block == block:
            print(f"{sources} Pin Table is current")
            return 0
        sources.write_text(replace_block(text, block), encoding="utf-8")
        print(f"updated {sources}")
        return 0
    except OSError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except RenderError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
