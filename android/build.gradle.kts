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
}
