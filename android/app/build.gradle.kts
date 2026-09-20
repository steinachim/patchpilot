import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.PathSensitivity
import java.util.Properties

// Release signing credentials live in local.properties (gitignored, never committed) rather than
// in this file, so the keystore path/passwords aren't checked into git. A debug build or a
// contributor without a keystore still gets a working `assembleRelease` output - it's just
// unsigned - so this stays an empty Properties() rather than failing the build eagerly.
val releaseSigningProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

plugins {
    // No `kotlin.android` plugin: AGP 9 provides Kotlin support itself, and applying the
    // standalone plugin alongside it is an error rather than a redundancy.
    alias(libs.plugins.android.application)
    // The Compose compiler is a Kotlin plugin from Kotlin 2.x on, rather than a separate
    // artifact pinned to a Kotlin version through `composeOptions`, so a Kotlin upgrade is one
    // version bump rather than two that have to match.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// AGP 9 owns the `kotlin` extension, so the JVM target moves here from the `kotlinOptions`
// block inside `android { }`. Same value, and it must stay in step with `compileOptions` below -
// a mismatch between the two is a link error at runtime rather than a build failure.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

android {
    namespace = "de.thewolfwalkexperience.software.patchpilot"
    // Compile-time only: `minSdk` still decides which devices can install this, and `targetSdk`
    // still decides which runtime behaviours are opted into - neither moves with this.
    //
    // **37 is Compose 1.12's floor, and headroom for this build rather than its requirement.**
    // The BOM pinned in libs.versions.toml (2026.01.01) resolves to Compose 1.10.2, whose AAR
    // metadata declares `minCompileSdk=35`, and this app's own code reaches no further than API
    // 33 (UsbConnectionManager's TIRAMISU branches) - so 35 compiles today, verified rather than
    // assumed. It stays at 37 because that is what Compose 1.12's AARs declare, so moving the BOM
    // forward needs no change here. The price is that anyone building this needs the SDK 37
    // platform installed, which is the one reason to reconsider it.
    compileSdk = 37

    defaultConfig {
        applicationId = "de.thewolfwalkexperience.software.patchpilot"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"
    }

    signingConfigs {
        // Only registered when local.properties actually has the four RELEASE_* keys, so
        // `assembleRelease` still works (producing an unsigned APK) for a contributor without a
        // keystore, rather than failing the whole build eagerly.
        if (releaseSigningProps.getProperty("RELEASE_STORE_FILE") != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = releaseSigningProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        // Generates BuildConfig.DEBUG, used to gate protocol/preset logging (see
        // Pro800Editor.kt, SysExExchange.kt) so it never reaches a release logcat.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Protocol code logs via android.util.Log on best-effort error paths (see
            // NordDevice.unlockCategorySelection); the plain android.jar unit tests run
            // against throws "not mocked" for any Android framework call by default. Returning
            // defaults instead lets those paths run under plain JUnit without pulling in
            // Robolectric just for logging.
            isReturnDefaultValues = true
        }
    }
}

// Falls back to "unknown" rather than failing the build for a checkout with no .git (e.g. a
// source tarball) - only the debug APK's file name depends on this, nothing else does.
val gitCommitHash: String = try {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    "unknown"
}

// APK output names identify the app and build on their own, since that's the file attached to
// GitHub Releases or shared ad hoc - AGP's default app-release.apk/app-debug.apk don't.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "patchpilot-v$it.apk" })
        }
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("patchpilot-debug-$gitCommitHash.apk")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

// devices/*.json (repo root) are the source of truth for per-instrument constants. Android can
// only load bundled assets at runtime, so this always overwrites the assets copies before every
// build. Never hand-edit anything in android/app/src/main/assets/; the whole directory is
// generated and git-ignored.
//
// A catalog file is named for what it holds: nord_devices.json covers several Nord models,
// behringer_pro800.json covers one instrument. The name is cosmetic - each file declares its own
// `family`, and that is what the registry dispatches on. Schemas (*.schema.json) are excluded:
// they describe the catalogs, they are not catalogs.
val deviceCatalogDir = rootDir.parentFile.resolve("devices")

val syncDeviceCatalog = tasks.register<Copy>("syncDeviceCatalog") {
    from(deviceCatalogDir) {
        include("*.json")
        exclude("*.schema.json")
    }
    // devices/blanks/*.bin - payloads a family writes over a slot to erase it. Captured off an
    // instrument rather than constructed, and therefore sources like the catalogs beside them,
    // even though they are binary. See devices/blanks/README.md.
    from(deviceCatalogDir.resolve("blanks")) {
        include("*.bin")
    }
    into("src/main/assets")
}

// The repo-root LICENSE (GPLv3, covering this app) plus the third-party license texts named in
// NOTICE.md - Apache-2.0 for AndroidX/Compose/Kotlin/kotlinx, OFL-1.1 for the two bundled fonts -
// copied into assets so the in-app "Open Source Licenses" screen (OpenSourceLicensesScreen.kt)
// can show the real text offline, with no INTERNET permission needed. Source of truth for the
// GPL text is the repo root LICENSE; source of truth for the others is android/licenses/.
val syncLicenses = tasks.register<Copy>("syncLicenses") {
    from(rootDir.parentFile.resolve("LICENSE")) {
        rename { "LICENSE-GPL-3.0.txt" }
    }
    from(rootDir.resolve("licenses")) {
        include("*.txt")
    }
    into("src/main/assets/licenses")
}

/**
 * Generates res/xml/device_filter.xml from the catalogs' USB matches.
 *
 * Generated rather than maintained by hand, because a hand-kept copy fails silently when a device
 * is added to the catalog and not to it (the app simply never launches on attach). It cannot be
 * loaded from an asset, because Android's PackageManager reads it to match USB_DEVICE_ATTACHED
 * before any app code runs, so generating it is the only way to have one source of truth.
 *
 * A USB match produces an entry by itself. A MIDI-matched instrument is found through MidiManager
 * and needs no USB permission, so it contributes one only where its entry sets
 * `launchOnUsbAttach` - which is what makes Android offer the app when it is plugged in.
 */
val generateUsbDeviceFilter = tasks.register("generateUsbDeviceFilter") {
    val outputFile = file("src/main/res/xml/device_filter.xml")
    inputs.dir(deviceCatalogDir)
    outputs.file(outputFile)
    doLast {
        val entries = linkedSetOf<Pair<Int, Int>>()

        // One lazy pattern covers every catalog shape: the Nord file carries vendorId/productId
        // directly on each device, the generic one inside a "usb" match, a usbHint or a
        // launchOnUsbAttach. A file with none of them contributes nothing, which is correct.
        val usbIds = Regex("\"vendorId\"\\s*:\\s*(\\d+)[\\s\\S]*?\"productId\"\\s*:\\s*(\\d+)")
        deviceCatalogDir.listFiles()
            ?.filter { it.name.endsWith(".json") && !it.name.endsWith(".schema.json") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                usbIds.findAll(file.readText())
                    .forEach { entries += it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            buildString {
                appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
                appendLine("<!--")
                appendLine("    GENERATED by the generateUsbDeviceFilter Gradle task from devices/*.json.")
                appendLine("    Do not edit by hand - add the device to its family's catalog instead.")
                appendLine("-->")
                appendLine("<resources>")
                entries.forEach { (vendorId, productId) ->
                    appendLine("""    <usb-device vendor-id="$vendorId" product-id="$productId" />""")
                }
                appendLine("</resources>")
            },
        )
    }
}

tasks.named("preBuild") {
    dependsOn(syncDeviceCatalog, generateUsbDeviceFilter, syncLicenses)
}

// Unit tests cannot read src/main/assets, so hand them the catalog directory itself. This is what
// lets CatalogParsesTest decode the real files rather than a copy that agrees with the code - a
// malformed catalog is otherwise a defect that only appears on a phone, with the instrument simply
// missing from the connect screen.
tasks.withType<Test>().configureEach {
    systemProperty("deviceCatalogDir", deviceCatalogDir.absolutePath)
    // **Declared as an input, or the tests that read it never re-run when it changes.**
    // A `systemProperty` is not an input Gradle tracks, so without this editing a catalog leaves
    // every test task UP-TO-DATE and the tests that assert what the catalogs contain never run.
    // A catalog is data the tests assert against, so it belongs here beside the property that
    // points at it.
    //
    // RELATIVE rather than ABSOLUTE so the cache still hits when the checkout moves; NAME_ONLY
    // would ignore the contents, which is exactly what is being tracked.
    inputs.dir(deviceCatalogDir)
        .withPropertyName("deviceCatalog")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
