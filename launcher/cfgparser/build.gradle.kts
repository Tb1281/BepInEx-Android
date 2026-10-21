plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module: the BepInEx cfg parser (ADR-0001).
// No Android dependencies are allowed here — this module is the test seam
// for config parsing, validation, and round-trip-safe serialisation.

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
