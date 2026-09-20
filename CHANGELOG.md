# Changelog

## Next release

### Added
- Six more Nord keyboards are recognized, each confirmed against real hardware: Electro 7, Grand 2, Lead A1, Piano 6, Stage 4 and Wave 2.
- The Yamaha Motif XS7 and XS8 are recognized in addition to the XS6, sharing its protocol and catalog configuration. Their USB product ids are inferred from Yamaha's driver files and not yet confirmed on real hardware.
- The Yamaha Motif XS browser shows the instrument's 1,217 factory voices and the favorites marked on its own front panel, chosen with a selector above the list. The factory names ship with the app instead of being read over MIDI, which would take about seven and a half minutes; a factory voice can be copied into a user slot.
- Yamaha Motif XS voices show their categories in the browser, and a row's menu offers Set favorite (any voice) and Set categories (user voices). A voice can be a favorite under a category it does not have, which matches what the instrument's own front panel allows.
- A Nord program's category shows on every row in the browser, and a row's menu offers Set category.
- A preset can be moved from a row's menu ("Move to…") as well as by dragging it; an empty destination moves, an occupied one swaps.
- A Yamaha Motif XS that is connected but not answering is identified as such on the connect screen, with the steps to set its MIDI In/Out setting to USB. Previously every operation timed out separately without saying why.
- Store metadata with phone and tablet screenshots, a privacy policy and a release guide, in preparation for F-Droid and Google Play.
- Settings has a new "Auto-Connect to First Found Instrument" toggle, on by default; turning it off always scans both buses and shows the device list, instead of connecting straight to the first instrument found over USB.

### Changed
- The app targets Android 16 and is versioned 1.0.
- The app also opts out of Android 12's device-to-device transfer, so the theme preference and a staged report never leave the phone that way either.
- The Behringer Pro-800 loads presets by writing its settings block over SysEx instead of sending bank select and program change, so it no longer needs to know the instrument's MIDI channel; this also works when the channel comes from the rear DIP switches or MIDI receive is off.
- The browser shows a preset's categories beside its id only where the row is wide enough (a phone in landscape, or a tablet), separating two of them with a comma. On a portrait phone they remain available from the row's menu. Where they are shown, a preset filed under no category says so.
- The Steampunk theme's progress indicator is a rendered brass compass whose needle hunts about north, in the style of the app icon, centred on the preset screen while the first rows load.

### Fixed
- Connecting to a recognised instrument over USB no longer waits for the slower MIDI bus scan to finish first, which is what made launch take longer since the Pro-800 and Motif XS were added.
- The device report is offered on the connect screen's firmware warning, as documented; previously the button never appeared there.
- On a Nord, a preset copied or moved into an empty slot no longer shows at the bottom of the list under a second header for its bank until the listing is re-read.
- Connecting to an instrument that has not been granted USB permission before — an unrecognised Nord, or the app opened before plugging in — no longer waits forever for a permission result the app had made itself unable to receive.
- Reading a device report no longer starts over when the phone is rotated; the read continues in the background and the result is kept until it is shared or dismissed.
- Closing a Motif XS session no longer releases its USB connection while a read is still in flight.
- The cached preset listing is tied to the physical device, so plugging in another unit of the same model without restarting the app no longer shows the first unit's presets.
- Denying the USB permission dialog no longer brings the same dialog straight back; the connect screen says the permission was denied and asks again only on Retry.
- Plugging an instrument in while the app shows "No supported instrument was found" now connects to it, instead of leaving that screen standing until Retry is tapped.
- The preset screen no longer stays on "Connecting…" after the instrument is unplugged while a system dialog is up or the app is in the background; it returns to the connect screen, and plugging the same instrument back in reconnects on its own.
- A device report whose read was interrupted is marked incomplete, with the reads that failed listed on the debug screen and in the report's own failure map, instead of reaching "ready" like a complete one.
- A Nord session started after the app was killed mid-transfer discards what the instrument still had queued from the earlier session, instead of reading it as the answer to its first request; where the connection cannot be recovered, the error says to unplug and replug the cable.
- Unplugging the instrument now drops the cached preset list, so changes made from its own panel while disconnected (or a different unit of the same model on the same port) show on reconnect without a manual refresh.
- Deleting the last preset in a bank removes that bank from the index rail when empty slots are hidden.
- Choosing Disconnect on the firmware advisory now returns to the device list instead of a "Not connected" screen with nothing to tap.
- A refused Nord category change now reports the instrument's reason like every other refused operation.
- A Yamaha Motif XS edit can no longer be interrupted part way through. Leaving the screen mid-write left the instrument waiting on "receiving midi bulk data" until it was power-cycled, or left a change accepted but not applied, which a later, unrelated edit would then apply on its own. Edits now always run to completion, and the back arrow is unavailable while one is in progress.
- Motif XS writes no longer block the UI thread; a failed connect releases the port; the regression test survives rotation; Nord listing and edits can no longer interleave.
- Sharing or saving a finished regression test or device report a second time no longer crashes the app.
- A refused operation now explains itself rather than reporting only a status number. In practice that means a slot the app shows as empty which the instrument says is occupied (a preset stored from its front panel since the last read): the app now says so and asks for a re-read.
- Backing out of a Nord connection while it is still being established no longer reports it as a failure to read the instrument.
- The connect screen no longer lists an instrument after it is unplugged, and tapping it can no longer hang waiting for USB permission.
- The connect screen no longer claims to be waiting for USB permission when it is not; it says what it is doing, which is opening the instrument.
- Selecting a preset no longer briefly inserts a progress line above the list, which pushed every row down on each tap.
- In the Steampunk theme, the outlines around text fields, buttons and the listing selector were too faint to make out, and the status bar's clock and icons could disappear when the phone was set to light mode.

## 0.9
Initial release.
