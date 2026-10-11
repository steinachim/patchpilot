plugins {
    alias(libs.plugins.android.application) apply false
    // Declared but never applied: AGP 9 compiles Kotlin itself, and applying this alongside it is
    // an error. It stays here because `apply false` still puts the Kotlin Gradle plugin at this
    // project's Kotlin version on the root build classpath, and the Compose compiler plugin's
    // release mapping task (`produceReleaseComposeMapping`) resolves
    // `org.jetbrains.kotlin:compose-group-mapping` at whatever Kotlin Gradle plugin version it
    // can see. Without this line it sees the copy embedded in AGP, and asks for a version of that
    // artifact that does not exist, so `assembleRelease` and `bundleRelease` fail.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.spotless)
}

// Formatting, checked with `./gradlew spotlessCheck` and applied with `spotlessApply`: ktlint with
// the settings below. They are passed here because this Spotless step does not apply them from
// .editorconfig; android/.editorconfig repeats them for editors, and the two are kept the same.
// Only these tasks resolve ktlint, so a release build downloads nothing for it.
val ktlintSettings = mapOf(
    "ktlint_code_style" to "android_studio",
    // The column ktlint wraps code at. Long comments and strings are wrapped by hand.
    "max_line_length" to 120,
    "ij_kotlin_allow_trailing_comma" to true,
    "ij_kotlin_allow_trailing_comma_on_call_site" to true,
    // Off: they join a wrapped signature, expression body or supertype onto one line, and the
    // code wraps there where that reads better.
    "ktlint_standard_function-signature" to "disabled",
    "ktlint_standard_class-signature" to "disabled",
    // Composables are named like types.
    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
    // On: this code style leaves it off, and nothing else in the build catches an unused import.
    "ktlint_standard_no-unused-imports" to "enabled",
)
spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintSettings)
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintSettings)
    }
}
