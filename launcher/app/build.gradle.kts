plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bepinex.android"
    compileSdk = 35

    defaultConfig {
        // Debug and release install side by side: release carries an
        // applicationIdSuffix (see buildTypes below), so a release APK never
        // overwrites the debug install. The test-variant applicationId
        // override (-Pbepinex.applicationId) was removed 2026-10-12; the
        // suffix below replaces it.
        applicationId = "com.bepinex.android"
        manifestPlaceholders["appLabel"] = "BepInEx Debug"
        minSdk = 28
        targetSdk = 35
        versionCode = 184
        versionName = "0.184"

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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "30.0.16248370"

    buildTypes {
        release {
            // Side-by-side with debug: release installs as
            // com.bepinex.android.release, so it never overwrites the debug
            // install. Still the debug keystore (see signing note below).
            applicationIdSuffix = ".release"
            versionNameSuffix = "-release"
            manifestPlaceholders["appLabel"] = "BepInEx Release"
            signingConfig = signingConfigs.getByName("debug")
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
        debug {
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

    // AppCompat (DayNight theme support)
    implementation(libs.androidx.appcompat)

    // Kotlin Coroutines & DataStore
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    // Pine — ART Java hook framework (FusionCore approach)
    implementation(libs.pine.core)

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
        "libdobby.so", "libpine.so", "libc++_shared.so",
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

tasks.named("preBuild") {
    dependsOn(preparePrebuilts)
}

