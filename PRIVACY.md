# Privacy policy

*Applies to Patch Pilot (package `de.thewolfwalkexperience.software.patchpilot`), all versions. Last updated 2026-09-19.*

## In short

Patch Pilot collects no personal data, has no network access, and never sends anything anywhere on its own. The only device it communicates with is the instrument you plug into your phone or tablet.

## What the app can access

- **The connected instrument.** The app talks to a synthesizer over USB to read and change the presets stored on it. For an instrument the app opens directly over USB (the Nord and Yamaha models), Android asks for your permission the first time that instrument is connected. For a class-compliant USB-MIDI instrument (the Behringer Pro-800), Android's MIDI service handles the connection and no permission prompt is shown. Nothing read from the instrument leaves the device except through the exports described below, which you start yourself.
- **Nothing else.** The app requests no Android permissions: no internet, no storage, no location, no contacts, no camera, no microphone. (The one permission in its manifest is a private one the AndroidX library generates so that only the app itself can deliver its USB broadcasts; it grants access to nothing.) It contains no advertising, analytics or crash-reporting libraries.

## What the app stores on your device

- Your theme choice, a single preference.
- While a report you asked to share is being handed to another app, a temporary copy of it in the app's private cache directory. It is replaced by the next share and removed when the app's cache is cleared.

Preset names and other instrument data are held in memory only while the app is running. A finished regression report is kept, in the app's saved state, until you dismiss it or close the app. The app opts out of Android backups, so none of this is copied to a cloud backup or to another device.

## Exports you start yourself

The app can produce two kinds of report, from the debug menu (five taps on the instrument name on the preset screen). The device report is also offered on the preset screen when the instrument or its firmware is one the app does not recognize, and on the connect screen's firmware warning. A report is written outside the app only when you tap **Share** or **Save to device**:

- **Device report**: the instrument's model, firmware version, USB vendor and product ids, the raw replies to the app's read-only configuration queries, storage figures, and counts of presets (how many slots are occupied, how many presets of each format version). It contains no preset names and no preset data.
- **Regression report**: which operations the app tested against the instrument and whether they worked. It names the presets the test used.

**Share** hands the report to an app you pick through the Android share sheet; **Save to device** writes it to a location you choose. Where it goes from there is up to you and that app. Nothing about you or your phone is included, and the instrument's serial number is not read.

If you send a report to the developer to help support an instrument, it is used for that purpose only.

## Children

The app is not directed at children and collects no data from anyone.

## Changes

Changes to this policy are recorded in this file's history in the public repository at <https://github.com/steinachim/patchpilot>.

## Contact

Questions about this policy: open an issue at <https://github.com/steinachim/patchpilot/issues>.
