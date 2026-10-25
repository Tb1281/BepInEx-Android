# BepInEx Android Launcher

Android launcher app for running BepInEx mods in Unity IL2CPP games.

## Features

- One-click BepInEx injection into Unity IL2CPP games
- Modpack manager: create, import/export, enable/disable mod sets
- Config file editor with syntax highlighting
- In-game floating log viewer & post-launch logcat viewer
- Dynamic game Activity launch via StubActivity & Instrumentation hooks
- Automatic crash detection and diagnostic archive export

## How it works

The launcher uses LSPlant (ART hook framework) to intercept the game's ClassLoader, native library loading, and UnityPlayer initialization. A custom `libmain.so` and `libfusion.so` control the dlopen order to install an `il2cpp_init` hook before Unity starts. The hook launches CoreCLR and the BepInEx preloader.

## Requirements

- Android 9+ (API 28+)
- arm64-v8a device
- Unity IL2CPP game installed

## Building

```
MSYS_NO_PATHCONV=1 ./gradlew assembleDebug
```

Requirements: Android SDK 35, NDK 30.0.16248370, CMake 3.22.1, JDK 17.

## Project structure

```
app/src/main/
  cpp/          Native code (libmain.so, libfusion.so)
  java/         Kotlin launcher UI and injection logic
  assets/       BepInEx framework and .NET runtime archives
  res/          UI strings (EN, KO, JA, ZH-CN)
TestPlugin/     Sample BepInEx IL2CPP test plugin
build_assets/   BepInEx core files for packaging
```

## Related projects

The managed framework is upstream [BepInEx](https://github.com/BepInEx/BepInEx) plus
the patch queue in `../patches`; the runtime is
[dotnet/runtime](https://github.com/dotnet/runtime). See `../provenance/SOURCES.md`
for the full provenance table and `THIRDPARTY_NATIVE.md` for the native binaries.
The injection chain follows the approach described by Starlight's FusionCore.

## License

- Launcher and injection code: GPL-3.0
- BepInEx: LGPL-2.1
- dotnet/runtime and .NET BCL: MIT; Dobby: Apache-2.0; LSPlant: LGPL-3.0
