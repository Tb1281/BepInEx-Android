plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Shipping inputs. The tag release workflow passes bepinex.versionName and
// bepinex.versionCode from the release preflight; debug and CI artifact builds
// fall back to the development defaults so they need no release credentials.
val defaultVersionName = "0.0.0-dev"
val defaultVersionCode = 1
val releaseVersionName: String? = providers.gradleProperty("bepinex.versionName")
    .orNull?.takeIf { it.isNotBlank() }
val releaseVersionCode: Int? = providers.gradleProperty("bepinex.versionCode")
    .orNull?.takeIf { it.isNotBlank() }?.toIntOrNull()
val releaseKeystorePath: String? = providers.environmentVariable("RELEASE_KEYSTORE_PATH")
    .orNull?.takeIf { it.isNotBlank() }

// Mirrors scripts/release_preflight.sh. The workflow passes both values, but the
// build independently derives the code from the name so a mismatched handoff is
// rejected here instead of shipping an APK that claims the wrong version.
// versionCode = major*100,000,000 + minor*100,000 + patch*100 + rank; rank 99
// is stable and pre-releases use 1..98 from their trailing ordinal, so
// rc1 < rc2 < stable.
fun derivedVersionCode(versionName: String): Int? {
    val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(-([0-9A-Za-z][0-9A-Za-z.-]*))?$")
        .matchEntire(versionName) ?: return null
    val (majorText, minorText, patchText) = match.destructured
    val components = listOf(majorText, minorText, patchText)
    if (components.any { it.length > 3 || (it.length > 1 && it.startsWith("0")) }) {
        return null
    }
    val major = majorText.toInt()
    val minor = minorText.toInt()
    val patch = patchText.toInt()
    if (major > 20 || minor > 999 || patch > 999) return null
    val rank = match.groups[5]?.value?.let { suffix ->
        val trailing = Regex("(\\d+)$").find(suffix)?.groupValues?.get(1)
        val ordinal = if (trailing == null) 1 else trailing.toIntOrNull() ?: return null
        if (ordinal !in 1..98) return null
        ordinal
    } ?: 99
    return major * 100_000_000 + minor * 100_000 + patch * 100 + rank
}

android {
    namespace = "com.bepinex.android"
    compileSdk = 36

    defaultConfig {
        // The release is com.bepinex.android. Debug and CI artifact builds carry
        // their own suffixes so all three can install side by side (see
        // buildTypes below). The test-variant applicationId override
        // (-Pbepinex.applicationId) was removed 2026-10-12.
        applicationId = "com.bepinex.android"
        manifestPlaceholders["appLabel"] = "BepInEx"
        minSdk = 28
        targetSdk = 36
        versionCode = releaseVersionCode ?: defaultVersionCode
        versionName = releaseVersionName ?: defaultVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-28"
                )
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // Fixed debug keystore checked into the repo (launcher/debug.keystore).
        // The default ~/.android/debug.keystore is per-machine - and on the
        // Muse VM it regenerates whenever the VM is replaced - which made
        // every rebuild signature-incompatible with the installed app
        // (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and forced full reinstalls.
        // Debug keys are public by design; this one is the standard
        // androiddebugkey/android pair.
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "30.0.16248370"

    signingConfigs {
        // Only present when the preflight has materialized the release keystore.
        // The release build type fails closed in preReleaseBuild if it is absent;
        // debug and artifact builds never look at this config.
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The shipping identity: com.bepinex.android, signed by the release
            // key. preReleaseBuild refuses to run without the key and the
            // tag-derived version, so a release can never fall back to debug
            // signing.
            manifestPlaceholders["appLabel"] = "BepInEx"
            signingConfig = signingConfigs.findByName("release")
            // The bulk of this APK was androidx.compose.material.icons.extended:
            // 21,320 icon classes (4,264 x 5 variants) of which this app uses 33.
            // A debug build ships all of them - classes.dex alone was 39.6 MB of
            // the 104 MB APK. R8 strips the unreferenced ones, which is the
            // difference between a sideloadable APK and a distributable one.
            //
            // This is safe only with the -keep rule in proguard-rules.pro for
            // net.dot.android.crypto.**, and only because the same rules are
            // applied to debug below. Those classes are looked up by the crypto
            // PAL by JNI name; obfuscation or a missing constructor aborts the
            // game process, and verify_crypto_java.sh reads sources plus one
            // built APK, so it cannot see a rule that was never applied.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("artifact") {
            // CI-only installable APK for the upstream-sync and manual launcher
            // builds. It keeps the release shrinking and alignment but is
            // debug-signed under a distinct ID, and is never published as a
            // release. This is what lets those paths build without the release
            // key while keeping the release build fail-closed.
            initWith(getByName("release"))
            applicationIdSuffix = ".artifact"
            versionNameSuffix = "-artifact"
            manifestPlaceholders["appLabel"] = "BepInEx CI"
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "BepInEx Debug"
            isDebuggable = true
            isJniDebuggable = true
            // proguardFiles is declared on release only, so a debug build with
            // isMinifyEnabled = true runs R8 with none of proguard-rules.pro.
            // That is how DotnetX509KeyManager's constructor was stripped: the
            // keep rule for net.dot.android.crypto.** existed the whole time,
            // it just was not in the rule set. Declaring it here makes turning
            // on minification for debug safe instead of a silent trap.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            pickFirsts.add("lib/**/*.so")
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM - manages all Compose library versions
    implementation(platform(libs.androidx.compose.bom))

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Activity, Lifecycle & Core
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Serialization (type-safe Navigation routes)
    implementation(libs.kotlinx.serialization.json)

    // AppCompat (DayNight theme support)
    implementation(libs.androidx.appcompat)

    // Kotlin Coroutines & DataStore
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    // Java hooks run through the in-repo HookEngine facade over LSPlant
    // (native, staged as liblsplant.so) — see ADR-0021. No Java dependency.

    // Pure cfg parser (metadata model + round-trip editing) behind the
    // Framework Config form editor — see ADR-0001.
    implementation(project(":cfgparser"))

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ==============================================================================
// Rust-style build.rs automated prebuilt verification & setup
// ==============================================================================
//
// The native runtimes and the managed framework archives are CI outputs of this
// repository (see ../.github/workflows). They are never vendored from a third
// party, so the build refuses to proceed unless every one of them matches the
// checksum manifest in provenance/sha256sums.txt.
val preparePrebuilts = tasks.register("preparePrebuilts") {
    description = "Checks, verifies, and prepares required native runtime libraries and assets before compilation."
    group = "build setup"

    val jniDir = file("src/main/jniLibs/arm64-v8a")
    val assetsDir = file("src/main/assets")

    // libSystem.IO.Ports.Native.so was listed here and is not in the pinned
    // runtime pack: SOURCES.md removed it because nothing in the BCL references
    // it. Leaving it listed meant this task reported it missing on every build
    // and the build continued anyway (the miss is only logged), so the list
    // quietly described a file that does not and should not exist.
    val requiredJniLibs = listOf(
        "libcoreclr.so", "libclrjit.so",
        "libSystem.Native.so",
        "libSystem.Globalization.Native.so", "libSystem.IO.Compression.Native.so",
        "libSystem.Security.Cryptography.Native.Android.so",
        "libdobby.so", "liblsplant.so", "libc++_shared.so",
        "libmscordaccore.so", "libmscordbi.so"
    )
    val requiredAssets = listOf("BepInEx.Android.zip", "dotnet.zip")

    inputs.files(requiredJniLibs.map { File(jniDir, it) })
    inputs.files(requiredAssets.map { File(assetsDir, it) })
    outputs.upToDateWhen {
        requiredJniLibs.all { File(jniDir, it).exists() } &&
        requiredAssets.all { File(assetsDir, it).exists() }
    }

    doLast {
        val missingLibs = requiredJniLibs.filterNot { File(jniDir, it).exists() }
        val missingAssets = requiredAssets.filterNot { File(assetsDir, it).exists() }

        if (missingLibs.isNotEmpty() || missingAssets.isNotEmpty()) {
            logger.lifecycle(
                "[preparePrebuilts] Missing prebuilts (libs: $missingLibs, assets: $missingAssets). " +
                    "Stage them with scripts/stage_launcher_inputs.sh (local builds) or install CI producer artifacts (see README, Building from Source)."
            )
        }

        // Always verify: presence is not provenance.
        //
        // verify_crypto_java.sh belongs here, not only in a manual checklist. The
        // crypto PAL resolves its Java classes by JNI descriptor and aborts the game
        // process when a descriptor is missing, so this check is the difference
        // between a failed build and a failed launch. It used to be a script that no
        // automated job ran, and it asserted a hardcoded .NET 11 signature, so a
        // .NET 10 build that aborted on the device passed it. The expected
        // descriptors now come from provenance/pal-java-contract.txt, which
        // scripts/fetch_dotnet_runtime.sh regenerates from the pinned runtime pack.
        val verifyScript = rootProject.file("scripts/verify_prebuilts.sh")
        val palScript = rootProject.file("scripts/verify_crypto_pal.sh")
        val javaContractScript = rootProject.file("scripts/verify_crypto_java.sh")
        for (script in listOf(verifyScript, palScript, javaContractScript)) {
            require(script.exists()) { "[preparePrebuilts] ${script.name} not found" }
            val process = ProcessBuilder("bash", script.absolutePath).inheritIO().start()
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "[preparePrebuilts] ${script.name} failed (exit $exitCode). " +
                    "Shipped artifacts do not match provenance/sha256sums.txt."
            }
        }
    }
}

// Fail before any release byte is compiled when the shipping inputs are absent.
// Attached to preReleaseBuild rather than the configuration phase so debug and
// artifact builds - and `gradlew tasks` - still work without release
// credentials. There is deliberately no debug-key fallback here.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val missing = listOf(
            "RELEASE_KEYSTORE_PATH" to System.getenv("RELEASE_KEYSTORE_PATH"),
            "RELEASE_KEYSTORE_PASSWORD" to System.getenv("RELEASE_KEYSTORE_PASSWORD"),
            "RELEASE_KEY_ALIAS" to System.getenv("RELEASE_KEY_ALIAS"),
            "RELEASE_KEY_PASSWORD" to System.getenv("RELEASE_KEY_PASSWORD"),
        ).filter { it.second.isNullOrBlank() }.map { it.first }
        check(missing.isEmpty()) {
            "Release signing is not configured. Missing: ${missing.joinToString(", ")}. " +
                "Use assembleArtifact for a CI-only build; release builds require the release key."
        }
        check(releaseVersionName != null && releaseVersionCode != null) {
            "Release version is not configured. Pass -Pbepinex.versionName and " +
                "-Pbepinex.versionCode from the release preflight."
        }
        val versionName = releaseVersionName!!
        val versionCode = releaseVersionCode!!
        check(versionCode > 0) {
            "Release versionCode must be positive."
        }
        check(versionCode <= 2_100_000_000) {
            "Release versionCode must stay below Android's 2,100,000,000 limit."
        }
        val expectedVersionCode = derivedVersionCode(versionName)
        check(expectedVersionCode != null) {
            "Release versionName '$versionName' is not a tag version."
        }
        check(versionCode == expectedVersionCode) {
            "Release versionCode $versionCode does not match versionName '$versionName' " +
                "(expected $expectedVersionCode). Derive both from the tag."
        }
        val store = file(System.getenv("RELEASE_KEYSTORE_PATH"))
        check(store.isFile) {
            "Release keystore not found at RELEASE_KEYSTORE_PATH."
        }
    }
}

tasks.named("preBuild") {
    dependsOn(preparePrebuilts)
}

