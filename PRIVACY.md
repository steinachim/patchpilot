# Privacy policy

*Applies to Patch Pilot (package `de.thewolfwalkexperience.software.patchpilot`), all versions. Last updated 2026-09-13.*

## In short

Patch Pilot collects no personal data, has no network access, and never sends anything anywhere on its own. The only device it communicates with is the instrument you plug into your phone or tablet.

## What the app can access

- **The connected instrument.** The app talks to a synthesizer over USB or USB-MIDI to read and change the presets stored on it. Android asks for your permission the first time each instrument is connected. Nothing read from the instrument leaves the device except through the exports described below, which you start yourself.
- **Nothing else.** The app declares no Android permissions: no internet, no storage, no location, no contacts, no camera, no microphone. It contains no advertising, analytics, or crash-reporting libraries.

## What the app stores on your device

- Your theme choice (a single preference).
- While preparing a report you have asked to share (see below), a temporary copy of it in the app's private cache directory. It is replaced on the next share and removed when the app's cache is cleared.

Preset names and other instrument data are held in memory only while the app is open. The app is excluded from Android backups, so none of this is copied to a cloud backup.

## Exports you start yourself

Two features write data outside the app, and both happen only when you tap them:

- **Share device info / Save to device** (debug menu and regression test): produces a report about the connected instrument - model, firmware version, USB vendor and product ids, the results of the app's probes, and the names of the presets on it. "Share" hands the report to an app you pick through the Android share sheet; "Save to device" writes it to a location you choose. Where it goes from there is up to you and that app. Nothing about you or your phone is included, and the instrument's serial number is not read.

If you send a report to the developer to help support an instrument, it is used for that purpose only.

## Children

The app is not directed at children and collects no data from anyone.

## Changes

Changes to this policy are recorded in this file's history in the public repository at <https://github.com/steinachim/patchpilot>.

## Contact

Questions about this policy: open an issue at <https://github.com/steinachim/patchpilot/issues>.
