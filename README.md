# Patch Pilot

Patch Pilot is a native Android app for browsing, organizing and editing the presets stored on a hardware synthesizer, over USB, from a phone or tablet. It renames, copies, moves and deletes presets on the instrument itself; no computer and no manufacturer editor is needed.

## Supported instruments

| Instrument | Connection | Status |
|---|---|---|
| Nord Electro 7 | USB (vendor protocol) | Verified on hardware |
| Nord Grand | USB (vendor protocol) | Verified on hardware |
| Nord Grand 2 | USB (vendor protocol) | Verified on hardware |
| Nord Lead A1 | USB (vendor protocol) | Verified on hardware |
| Nord Piano 6 | USB (vendor protocol) | Verified on hardware |
| Nord Stage 2 EX | USB (vendor protocol) | Verified on hardware |
| Nord Stage 4 | USB (vendor protocol) | Verified on hardware |
| Nord Wave 2 | USB (vendor protocol) | Verified on hardware |
| Behringer Pro-800 | USB-MIDI (class compliant) | Verified on hardware |
| Yamaha Motif XS6 | USB (USB-MIDI packets on a vendor interface) | Verified on hardware |
| Yamaha Motif XS7, XS8 | as XS6 | **Assumed.** Their USB product ids are inferred from Yamaha's driver files and have not been confirmed on a unit. |

"Verified on hardware" means the app has been run against that model; the firmware versions it was run with are listed in the device catalog under [devices/](devices/). Connecting to a supported model with a firmware version that is not in the catalog shows a warning but is allowed.

### Other Nord instruments

Every Nord model above is handled by one protocol implementation; the differences between models are data in [devices/nord_devices.json](devices/nord_devices.json) (USB ids, bank layout, tested firmware). Another Nord that speaks the same USB protocol is expected to need only a catalog entry. This is an expectation, not a guarantee: a new model may differ in ways the catalog cannot express.

An unrecognized Nord (Clavia USB vendor id) can be opened anyway from the connect screen, behind a warning. A hidden debug menu (five taps on the instrument name on the preset screen) runs a regression test against the connected instrument and shares the resulting report. To get such an instrument added, open an issue at <https://github.com/steinachim/patchpilot/issues> and attach that report.

### Other manufacturers

The Behringer Pro-800 and the Yamaha Motif XS each have their own protocol implementation, and neither says anything about other instruments from the same manufacturer. A further Behringer or Yamaha instrument needs its own implementation, verified against real hardware. Requests are welcome as issues; support depends on access to the instrument.

## Screenshots

The app has two themes, System Default and Steampunk.

<p>
  <img src="docs/screenshots/connect.png" alt="Connecting to an instrument" width="30%">
  <img src="docs/screenshots/presets-steampunk.png" alt="Preset browser (Steampunk theme)" width="30%">
  <img src="docs/screenshots/presets-system.png" alt="Preset browser (System theme)" width="30%">
</p>

Without an instrument, the connect screen offers a demo mode with a simulated instrument.

## Building

Requirements: JDK 17 or newer, and the Android SDK with platform 37 installed (`compileSdk` is 37). The build runs on whatever JDK launches Gradle (`JAVA_HOME`, or the Gradle JDK configured in Android Studio); nothing is downloaded beyond the Maven dependencies.

```
cd android
./gradlew assembleDebug
```

Unit tests:

```
cd android
./gradlew testDebugUnitTest
```

The build reads the device catalog from `../devices` relative to `android/` (the JSON descriptors and the binary blanks under `devices/blanks/`) and copies it into the app's assets; it also generates the USB device filter from the catalog's USB ids. Keep `devices/` and `android/` as siblings.

### Continuous integration

[GitHub Actions](.github/workflows/android.yml) runs the unit tests and `assembleRelease bundleRelease` on every pull request and on every push to `main`. Pushes to other branches without a pull request do not trigger a build. On `main` the release APK and bundle are signed with the release key held in repository secrets (see [RELEASING.md](docs/RELEASING.md#signing)) and attached to the run as an artifact for 30 days. Pull-request builds are unsigned.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): a tour of the codebase.
- [docs/PROTOCOLS.md](docs/PROTOCOLS.md): the wire protocol each device implementation uses.
- [docs/RELEASING.md](docs/RELEASING.md): how a version is built and published.
- [devices/README.md](devices/README.md): the device catalog and how to add an instrument.
- [CHANGELOG.md](CHANGELOG.md).

## Legal

Patch Pilot collects no data and has no network access; see [PRIVACY.md](PRIVACY.md).

Patch Pilot is an independent, unofficial project. It is not affiliated with, endorsed by, or supported by Clavia DMI AB (Nord), Music Tribe / Behringer, or Yamaha Corporation. Product and brand names are used solely to identify the hardware this app is compatible with.

This software changes your instrument's internal storage, including operations that overwrite or delete presets. It is provided "AS IS", without warranty of any kind, as permitted by the license below. Use with real hardware is at your own risk.

## License

Patch Pilot is licensed under the GNU General Public License v3.0 (SPDX: `GPL-3.0-only`); see [LICENSE](LICENSE). Third-party license notices for bundled dependencies and fonts are listed in [android/NOTICE.md](android/NOTICE.md).

## Thank you

Thanks to both my bands, [The Wolfwalk Experience](https://www.youtube.com/@thewolfwalkexperience) and [The Jukes](https://www.youtube.com/@thejukesmusicgermany), without whom this project would not have started. Go and give them a listen.
