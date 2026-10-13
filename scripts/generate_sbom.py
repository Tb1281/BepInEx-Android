#!/usr/bin/env python3
"""
Generate an SPDX 2.3 source SBOM from this repository's pinned inputs.

Why a source SBOM, and why this script instead of syft:

  The shipped APK is assembled from six pins in provenance/*.env (BepInEx,
  dobby, Pine + nested xz-embedded, MonoMod, Il2CppInterop, .NET runtime),
  the Gradle version catalog, and a pinned toolchain (AGP/Kotlin/NDK/CMake/
  .NET SDK/JDK). Those pins are the entire dependency list: nothing else is
  fetched at build time. A scanner such as syft inventories the *built*
  artifact (dex, native libs), which is complementary but cannot name the
  upstream commit a .so was compiled from. This script records exactly that,
  deterministically and offline, so `verify-provenance` can assert it without
  network access or an external binary.

  A consumer that wants an artifact inventory can still run
  `syft app-debug-aligned.apk -o spdx-json` on the shipped APK; the two
  documents answer different questions (what upstream went in vs. what bytes
  came out) and neither replaces the other.

Why it fails closed:

  A missing pin file or a pin file missing a required key exits 1 rather than
  emitting an SBOM with the entry omitted. An SBOM that silently drops the one
  dependency that changed is worse than no SBOM: it attests to a completeness
  it does not have. This is operational principle 3 (fail-closed verification).

Determinism:

  Packages and relationships are sorted by SPDXID. The only non-deterministic
  field is creationInfo.created, which honours SOURCE_DATE_EPOCH when set
  (reproducible-builds.org) and otherwise uses the current UTC time. Two runs
  with the same SOURCE_DATE_EPOCH over the same tree are byte-identical.

Usage:
  scripts/generate_sbom.py [--output out/sbom.spdx.json]
      [--provenance-dir provenance] [--launcher-dir launcher] [--repo-root .]
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys

try:
    import tomllib
except ImportError:  # Python < 3.11 has no tomllib; the runners all have 3.12+.
    tomllib = None

SPDX_VERSION = "SPDX-2.3"
DATA_LICENSE = "CC0-1.0"
TOOL = "Tool: BepInEx-generate_sbom"

# The six pins that determine the build. A file renamed away or added without
# being listed here must fail (missing) or be ignored loudly in review (extra),
# never silently change the SBOM. This is the same explicit-list rule as
# check_upstream_pins.sh: a glob over provenance/*.env would start treating an
# unrelated file as a pin.
REQUIRED_ENV_FILES = (
    "bepinex.env",
    "dobby.env",
    "pine.env",
    "monomod.env",
    "il2cppinterop.env",
    "dotnet-runtime.env",
)


def parse_env_file(path):
    """Parse a shell-style KEY=VALUE file, honouring single-quoted multiline
    values (PINE_PATCH_FILES, MONOMOD_COMPONENTS). Returns a dict."""
    values = {}
    with open(path, "r", encoding="utf-8") as fp:
        lines = fp.read().splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        i += 1
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, _, raw = line.partition("=")
        key = key.strip()
        raw = raw.strip()
        # Single-quoted value possibly spanning lines.
        if raw.startswith("'") and not (len(raw) > 1 and raw.endswith("'")):
            buf = [raw[1:]]
            closed = False
            while i < len(lines):
                nxt = lines[i]
                i += 1
                if nxt.endswith("'"):
                    buf.append(nxt[:-1])
                    closed = True
                    break
                buf.append(nxt)
            if not closed:
                raise ValueError(f"{path}: unterminated single-quoted value for {key}")
            values[key] = "\n".join(buf)
        elif len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in ("'", '"'):
            values[key] = raw[1:-1]
        else:
            # Strip trailing inline comment only when outside quotes; values in
            # these files never contain '#' except in comments.
            values[key] = raw.split("#", 1)[0].strip()
    return values


def require(mapping, key, path):
    if key not in mapping or not mapping[key]:
        raise ValueError(f"{path}: missing required key {key}")
    return mapping[key]


def git_head(repo_root):
    """Best-effort commit SHA: GITHUB_SHA first (CI), then git, else working-tree."""
    sha = os.environ.get("GITHUB_SHA", "").strip()
    if sha:
        return sha
    try:
        out = subprocess.run(
            ["git", "-C", repo_root, "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=10,
        )
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    except Exception:
        pass
    return "working-tree"


def created_timestamp():
    epoch = os.environ.get("SOURCE_DATE_EPOCH", "").strip()
    if epoch:
        try:
            dt = datetime.datetime.fromtimestamp(int(epoch), tz=datetime.timezone.utc)
            return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
        except ValueError:
            pass
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_gradle_app_version(launcher_dir):
    version_name = "unknown"
    version_code = "unknown"
    namespace = "com.bepinex.android"
    path = os.path.join(launcher_dir, "app", "build.gradle.kts")
    try:
        with open(path, "r", encoding="utf-8") as fp:
            text = fp.read()
        m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
        if m:
            version_name = m.group(1)
        m = re.search(r'versionCode\s*=\s*(\d+)', text)
        if m:
            version_code = m.group(1)
        m = re.search(r'namespace\s*=\s*"([^"]+)"', text)
        if m:
            namespace = m.group(1)
    except FileNotFoundError:
        raise ValueError(f"missing Gradle build file: {path}")
    return namespace, version_name, version_code


def parse_catalog(launcher_dir):
    """Return (versions dict, libraries list of (alias, group, name, version, bom_managed))."""
    path = os.path.join(launcher_dir, "gradle", "libs.versions.toml")
    if tomllib is None:
        raise ValueError("tomllib unavailable (need Python 3.11+)")
    try:
        with open(path, "rb") as fp:
            data = tomllib.load(fp)
    except FileNotFoundError:
        raise ValueError(f"missing version catalog: {path}")
    versions = dict(data.get("versions", {}))
    bom_version = versions.get("composeBom", "")
    libraries = []
    for alias, spec in sorted(data.get("libraries", {}).items()):
        group = spec.get("group", "")
        name = spec.get("name", "")
        version = spec.get("version", "")
        bom_managed = False
        if not version and spec.get("version.ref"):
            version = versions.get(spec["version.ref"], "")
        if not version:
            # No explicit version: resolved by the Compose BOM at build time.
            # The BOM version is not the library version, so record the
            # constraint honestly rather than copying the BOM number over.
            bom_managed = True
            version = f"managed-by-compose-bom-{bom_version}" if bom_version else "managed-by-bom"
        if not group or not name:
            continue
        libraries.append((alias, group, name, version, bom_managed))
    return versions, libraries


def pkg(spdx_id, name, version, supplier, download, homepage="", checksum=None,
        license_declared="NOASSERTION", copyright_text="NOASSERTION",
        external_refs=None, comment=""):
    d = {
        "SPDXID": spdx_id,
        "name": name,
        "versionInfo": version,
        "supplier": supplier,
        "downloadLocation": download,
        "filesAnalyzed": False,
        "licenseConcluded": "NOASSERTION",
        "licenseDeclared": license_declared,
        "copyrightText": copyright_text,
    }
    if homepage:
        d["homepage"] = homepage
    if checksum:
        d["checksums"] = [{"algorithm": "SHA256", "checksumValue": checksum}]
    if external_refs:
        d["externalRefs"] = external_refs
    if comment:
        d["comment"] = comment
    return d


def git_ref(url, commit):
    return [{
        "referenceCategory": "OTHER",
        "referenceType": "git-commit",
        "referenceLocator": commit,
    }]


def main():
    ap = argparse.ArgumentParser(description="Generate SPDX source SBOM from pins")
    ap.add_argument("--output", default="out/sbom.spdx.json")
    ap.add_argument("--provenance-dir", default="provenance")
    ap.add_argument("--launcher-dir", default="launcher")
    ap.add_argument("--repo-root", default=".")
    args = ap.parse_args()

    prov = args.provenance_dir
    for fname in REQUIRED_ENV_FILES:
        p = os.path.join(prov, fname)
        if not os.path.isfile(p):
            sys.stderr.write(f"generate_sbom: missing pin file {p}\n")
            sys.exit(1)

    try:
        bepinex = parse_env_file(os.path.join(prov, "bepinex.env"))
        dobby = parse_env_file(os.path.join(prov, "dobby.env"))
        pine = parse_env_file(os.path.join(prov, "pine.env"))
        monomod = parse_env_file(os.path.join(prov, "monomod.env"))
        interop = parse_env_file(os.path.join(prov, "il2cppinterop.env"))
        runtime = parse_env_file(os.path.join(prov, "dotnet-runtime.env"))

        bepinex_url = require(bepinex, "UPSTREAM_URL", "bepinex.env")
        bepinex_commit = require(bepinex, "UPSTREAM_COMMIT", "bepinex.env")
        dobby_url = require(dobby, "UPSTREAM_URL", "dobby.env")
        dobby_commit = require(dobby, "UPSTREAM_COMMIT", "dobby.env")
        pine_url = require(pine, "UPSTREAM_URL", "pine.env")
        pine_commit = require(pine, "UPSTREAM_COMMIT", "pine.env")
        xz_url = require(pine, "PINE_SUBMODULE_XZ_URL", "pine.env")
        xz_commit = require(pine, "PINE_SUBMODULE_XZ_COMMIT", "pine.env")
        monomod_url = require(monomod, "UPSTREAM_URL", "monomod.env")
        monomod_commit = require(monomod, "UPSTREAM_COMMIT", "monomod.env")
        monomod_ver = require(monomod, "MONOMOD_PACKAGE_VERSION", "monomod.env")
        interop_url = require(interop, "UPSTREAM_URL", "il2cppinterop.env")
        interop_commit = require(interop, "UPSTREAM_COMMIT", "il2cppinterop.env")
        interop_ver = require(interop, "PACKAGE_VERSION", "il2cppinterop.env")
        rt_pack = require(runtime, "DOTNET_RUNTIME_PACK", "dotnet-runtime.env")
        rt_ver = require(runtime, "DOTNET_RUNTIME_VERSION", "dotnet-runtime.env")
        rt_sha = require(runtime, "DOTNET_RUNTIME_PACK_SHA256", "dotnet-runtime.env")

        versions, libraries = parse_catalog(args.launcher_dir)
        namespace, app_version, app_code = parse_gradle_app_version(args.launcher_dir)

        # .NET SDK pin: root global.json. Missing file is an error, not a skip:
        # the SDK determines the managed output.
        sdk_version = "unknown"
        gpath = os.path.join(args.repo_root, "global.json")
        with open(gpath, "r", encoding="utf-8") as fp:
            gdata = json.load(fp)
        sdk_version = gdata.get("sdk", {}).get("version", "unknown")
        if sdk_version == "unknown":
            raise ValueError("global.json: missing sdk.version")
    except (ValueError, FileNotFoundError, json.JSONDecodeError) as e:
        sys.stderr.write(f"generate_sbom: {e}\n")
        sys.exit(1)

    head = git_head(args.repo_root)
    doc_ns = f"https://github.com/BepInEx-Android/BepInEx-Android/sbom/{head}-{app_version}"

    packages = []
    packages.append(pkg(
        "SPDXRef-Root-APK", namespace, app_version,
        "Organization: BepInEx-Android", "NOASSERTION",
        homepage="https://github.com/BepInEx-Android/BepInEx-Android",
        comment=f"versionCode {app_code}; built from commit {head}"))

    def upstream_pkg(spdx_id, name, url, commit, comment=""):
        short = commit[:7]
        return pkg(spdx_id, name, commit, "NOASSERTION", url,
                   homepage=url, external_refs=git_ref(url, commit),
                   comment=comment or f"pinned commit {short}")

    packages.append(upstream_pkg("SPDXRef-Upstream-BepInEx", "BepInEx/BepInEx",
                                 bepinex_url, bepinex_commit))
    packages.append(upstream_pkg("SPDXRef-Upstream-Dobby", "canyie/dobby",
                                 dobby_url, dobby_commit))
    packages.append(upstream_pkg("SPDXRef-Upstream-Pine", "canyie/Pine",
                                 pine_url, pine_commit))
    packages.append(upstream_pkg("SPDXRef-Upstream-Pine-XZ", "tukaani-project/xz-embedded",
                                 xz_url, xz_commit,
                                 comment="nested submodule compiled into libpine.so"))
    packages.append(upstream_pkg("SPDXRef-Upstream-MonoMod", "MonoMod/MonoMod",
                                 monomod_url, monomod_commit))
    packages.append(upstream_pkg("SPDXRef-Upstream-Il2CppInterop", "BepInEx/Il2CppInterop",
                                 interop_url, interop_commit))

    for comp in ("Core", "Utils", "RuntimeDetour"):
        packages.append(pkg(
            f"SPDXRef-Package-MonoMod-{comp}", f"MonoMod.{comp}", monomod_ver,
            "NOASSERTION", "NOASSERTION",
            comment=f"rebuilt here from MonoMod@{monomod_commit[:7]}; local-nuget/",
            external_refs=[{"referenceCategory": "PACKAGE-MANAGER",
                             "referenceType": "purl",
                             "referenceLocator": f"pkg:nuget/MonoMod.{comp}@{monomod_ver}"}]))

    for comp in ("Runtime", "Common", "HarmonySupport", "Generator"):
        packages.append(pkg(
            f"SPDXRef-Package-Il2CppInterop-{comp}", f"Il2CppInterop.{comp}", interop_ver,
            "NOASSERTION", "NOASSERTION",
            comment=f"rebuilt here from Il2CppInterop@{interop_commit[:7]}; local-nuget/",
            external_refs=[{"referenceCategory": "PACKAGE-MANAGER",
                             "referenceType": "purl",
                             "referenceLocator": f"pkg:nuget/Il2CppInterop.{comp}@{interop_ver}"}]))

    packages.append(pkg(
        "SPDXRef-Package-DotnetRuntime", rt_pack, rt_ver,
        "Organization: Microsoft", "https://api.nuget.org/v3-flatcontainer",
        homepage="https://dotnet.microsoft.com",
        checksum=rt_sha.lower(),
        comment="official Microsoft runtime pack; supplies libcoreclr.so and dotnet.zip BCL",
        external_refs=[{"referenceCategory": "PACKAGE-MANAGER",
                        "referenceType": "purl",
                        "referenceLocator": f"pkg:nuget/{rt_pack}@{rt_ver}"}]))

    for alias, group, name, version, bom_managed in libraries:
        safe = re.sub(r"[^A-Za-z0-9.-]+", "-", f"{group}-{name}")[:60]
        if bom_managed:
            purl = f"pkg:maven/{group}/{name}"
            comment = f"Gradle catalog alias {alias}; version resolved by Compose BOM"
        else:
            purl = f"pkg:maven/{group}/{name}@{version}"
            comment = f"Gradle catalog alias {alias}"
        packages.append(pkg(
            f"SPDXRef-Gradle-{safe}", f"{group}:{name}", version,
            "NOASSERTION", "NOASSERTION",
            comment=comment,
            external_refs=[{"referenceCategory": "PACKAGE-MANAGER",
                            "referenceType": "purl",
                            "referenceLocator": purl}]))

    toolchain = [
        ("SPDXRef-Tool-AGP", "com.android.application (AGP)", versions.get("agp", "unknown")),
        ("SPDXRef-Tool-Kotlin", "org.jetbrains.kotlin", versions.get("kotlin", "unknown")),
        ("SPDXRef-Tool-NDK", "Android NDK", "30.0.16248370"),
        ("SPDXRef-Tool-CMake", "CMake", "3.22.1"),
        ("SPDXRef-Tool-DotnetSDK", ".NET SDK", sdk_version),
        ("SPDXRef-Tool-JDK", "Eclipse Temurin JDK", "17"),
    ]
    for spdx_id, name, version in toolchain:
        if version == "unknown":
            sys.stderr.write(f"generate_sbom: unknown version for toolchain {name}\n")
            sys.exit(1)
        packages.append(pkg(spdx_id, name, version, "NOASSERTION", "NOASSERTION",
                            comment="pinned build toolchain"))

    packages.sort(key=lambda p: p["SPDXID"])

    relationships = [{"spdxElementId": "SPDXRef-DOCUMENT",
                      "relationshipType": "DESCRIBES",
                      "relatedSpdxElement": "SPDXRef-Root-APK"}]
    for p in packages:
        if p["SPDXID"] == "SPDXRef-Root-APK":
            continue
        relationships.append({"spdxElementId": "SPDXRef-Root-APK",
                              "relationshipType": "DEPENDS_ON",
                              "relatedSpdxElement": p["SPDXID"]})
    relationships.sort(key=lambda r: (r["spdxElementId"], r["relationshipType"],
                                       r["relatedSpdxElement"]))

    doc = {
        "spdxVersion": SPDX_VERSION,
        "dataLicense": DATA_LICENSE,
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"{namespace}-{app_version}",
        "documentNamespace": doc_ns,
        "creationInfo": {
            "created": created_timestamp(),
            "creators": [TOOL, "Organization: BepInEx-Android"],
        },
        "packages": packages,
        "relationships": relationships,
    }

    out_dir = os.path.dirname(os.path.abspath(args.output))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as fp:
        json.dump(doc, fp, indent=2)
        fp.write("\n")
    print(f"sbom -> {args.output} ({len(packages)} packages)")


if __name__ == "__main__":
    main()
