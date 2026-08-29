import org.gradle.api.tasks.Copy

plugins {
    // No `kotlin.android` plugin: AGP 9 provides Kotlin support itself, and applying the
    // standalone plugin alongside it is an error rather than a redundancy.
    alias(libs.plugins.android.application)
    // The Compose compiler is a Kotlin plugin from Kotlin 2.x on, rather than a separate
    // artifact pinned to a Kotlin version - which is what `composeOptions` used to do, and what
    // made a Kotlin upgrade a two-part version-matching exercise.
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
    // 37 because Compose 1.12 (BOM 2026.08.00) declares minCompileSdk=37. Compile-time only:
    // `minSdk` still decides which devices can install this, and `targetSdk` still decides which
    // runtime behaviours are opted into - neither moves. Compose 1.12 itself declares
    // minSdkVersion 23, below this app's 26, so nothing is cut off at the low end either.
    compileSdk = 37

    defaultConfig {
        applicationId = "de.thewolfwalkexperience.software.patchpilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
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

// devices/*.json (repo root) are the source of truth for per-instrument constants - Python reads
// devices/nord_devices.json directly, but Android can only load bundled assets at runtime, so this
// always overwrites the assets copies before every build. Never hand-edit anything in
// android/app/src/main/assets/; the whole directory is generated and git-ignored.
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
 * That file used to be maintained by hand, with a comment asking whoever added a device to
 * remember to add a line here too - a standing invitation to forget, and one that fails silently
 * (the app simply never launches on attach). It cannot be loaded from an asset, because Android's
 * PackageManager reads it to match USB_DEVICE_ATTACHED before any app code runs, so generating it
 * is the only way to have one source of truth.
 *
 * Only USB matches produce an entry. A MIDI-attached instrument is found through MidiManager and
 * has no USB_DEVICE_ATTACHED filter to appear in.
 */
val generateUsbDeviceFilter = tasks.register("generateUsbDeviceFilter") {
    val outputFile = file("src/main/res/xml/device_filter.xml")
    inputs.dir(deviceCatalogDir)
    outputs.file(outputFile)
    doLast {
        val entries = linkedSetOf<Pair<Int, Int>>()

        // One lazy pattern covers both catalog shapes: the Nord file carries vendorId/productId
        // directly on each device, the generic one inside a "usb" match or a usbHint. A file with
        // neither - a MIDI-only instrument - contributes nothing, which is correct.
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
}
