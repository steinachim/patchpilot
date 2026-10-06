# Changelog

## Next release

### Added
- The Fractal Audio Axe-Fx III is supported: its presets can be browsed, loaded, renamed, copied, moved, swapped and deleted. Every operation was verified on a Mark I, but the app itself has not yet been run on one. A Mark II is recognized and lists all 1,024 presets, but has not been tested yet.
- The Moog Subsequent 37 is supported: its presets can be browsed, loaded, renamed, copied, moved, swapped and deleted, and their categories shown and set. Reading its preset list takes about 2½ minutes, the instrument's own transfer time. Every operation was verified on a unit, but the app itself has not yet been run on one.
- An instrument can show a notice when it connects, with a “Don't show this again” option; Settings → “Show Hidden Messages Again” brings hidden notices back.

### Changed
- The rename dialog says why a name can't be stored (characters outside plain ASCII, or a name the instrument reserves) and only enables Rename for a name that can, before anything is sent.
- On a Nord, the preset list updates faster after an edit: only the changed slots are read back, not the whole program list.

### Fixed
- Unplugging an instrument that had failed to open no longer interrupts a session started afterwards, such as demo mode.
- Yamaha Motif XS edits only accept voice data from the slot they read, so a late reply to an earlier request can no longer be written to the wrong slot.
- The preset list no longer uses CPU while idle; the refresh icon only animates while a refresh runs.
- On Android 8 to 12L, other apps can no longer send Patch Pilot fake USB permission answers or unplug notices.
- Spaces before or after a new preset name are now removed before it is sent, so renaming a Behringer Pro-800 preset with them no longer fails.
- TalkBack reads Settings → “Auto-Connect to First Found Instrument” as a single switch.
- Plugging an instrument in while the phone is locked now opens its preset list once unlocked, instead of stopping at “Connected to …”.
- After Android closes Patch Pilot in the background, reopening it reconnects the instrument instead of staying on “Connecting…”.
- Leaving the preset list while a device report or another operation is running now goes straight to the instrument list, instead of bouncing back or stopping at “Connecting…”.
- Returning to the app reconnects the instrument that was in use: with two attached it no longer switches to the other, and with auto-connect off it no longer asks which one every time.
- Choosing demo mode on the untested-firmware warning releases the instrument, so the next search finds it.
- An accepted firmware warning is no longer shown again each time the app returns from the background.
- On a Nord, an edit the instrument applies to a different slot than the one chosen is reported as such, with a prompt to refresh the list, instead of as a refusal; and a write is no longer reported as failed when only the display refresh after it goes unconfirmed.
- Moving a Yamaha Motif XS voice onto a slot that holds one, which an out-of-date list can show as empty, is now refused instead of overwriting that voice.
- After an edit, the preset list updates only the changed rows, instead of re-reading the whole instrument whenever its list had a slot that could not be read (about 1½ minutes on a Yamaha Motif XS).
- A preset slot that could not be read is shown as such and is no longer offered as an empty destination for a copy or move.
- Rotating the phone, opening Settings or switching the theme while an edit runs no longer loses its progress line or result, or re-enables Back and other edits before it finishes; an open dialog or destination pick stays open too.
- A preset edited while the user list was still loading no longer appears unedited after returning to the app.
- Pulling to refresh while the preset list is still loading no longer starts the list over.
- The system Back gesture on the preset list now does what the Back arrow does: it returns to the instrument list, after ending a “Copy to…” or “Move to…” pick, instead of closing the app on Android 8 to 11.
- A preset list stops with a message once three presets in a row go unanswered, instead of waiting out every remaining one - minutes on a Pro-800 or Motif XS whose MIDI connection has stopped answering.
- On a Nord Electro 7 and Stage 4, the category list shows <None> last, as on the other Nords.
- A Fractal Audio Axe-Fx III preset name with control characters in it is shown without them, as on the other instruments.
- On a Nord, a reply that does not come in time is reported as such, rather than as a lost connection.
- Loading a Yamaha Motif XS voice counts as done only when the instrument echoes that voice, and a preset list being read at the same time can no longer slip between the selection's messages.
- A Behringer Pro-800 that refuses to load a preset now says so, instead of the app reporting the preset as selected.

## 1.1

### Added
- Pushing a version tag now creates its GitHub Release automatically, with the signed APK attached and release notes taken from the matching changelog section.

### Fixed
- Building the app now uses Android Gradle Plugin 9.4.0, fixing an R8 bug that made two builds of the same source produce different bytes, needed for F-Droid's reproducible-build verification.
- The release APK no longer carries Play's dependency-metadata block, which F-Droid's scanner flags as suspicious since it's unreadable outside Play; it had no effect on the app itself.

## 1.0

### Added
- Six more Nord keyboards are recognized, each confirmed against real hardware: Electro 7, Grand 2, Lead A1, Piano 6, Stage 4 and Wave 2.
- The Yamaha Motif XS7 and XS8 are recognized in addition to the XS6, sharing its protocol and catalog configuration. Their USB product ids are inferred from Yamaha's driver files and not yet confirmed on real hardware.
- The Yamaha Motif XS browser shows the instrument's 1,217 factory voices and the favorites marked on its own front panel, chosen with a selector above the list. The factory names ship with the app instead of being read over MIDI, which would take several minutes; a factory voice can be copied into a user slot.
- Yamaha Motif XS voices show their categories in the browser, and a row's menu offers Set favorite (any voice) and Set categories (user voices). A voice can be a favorite under a category it does not have, which matches what the instrument's own front panel allows.
- A Nord program's category shows on every row in the browser, and a row's menu offers Set category.
- A preset can be moved from a row's menu ("Move to…") as well as by dragging it; an empty destination moves, an occupied one swaps.
- A Yamaha Motif XS that is connected but not answering is identified as such on the connect screen, with the steps to set its MIDI In/Out setting to USB. Previously every operation timed out separately without saying why.
- Store metadata with phone and tablet screenshots, a privacy policy and a release guide, in preparation for F-Droid and Google Play.
- Settings has a new "Auto-Connect to First Found Instrument" toggle, on by default; turning it off always scans both buses and shows the device list, instead of connecting straight to the first instrument found over USB.

### Changed
- The Steampunk theme's drag handle, top bar icons, plate fasteners and bank headers are rendered brass rather than glyphs and flat colour.
- The app targets Android 16 and is versioned 1.0.
- The app opts out of Android backups and of Android 12's device-to-device transfer, so its preferences and a staged report never leave the phone that way.
- The Behringer Pro-800 loads presets by writing its settings block over SysEx instead of sending bank select and program change, so it no longer needs to know the instrument's MIDI channel; this also works when the channel comes from the rear DIP switches or MIDI receive is off.
- The browser shows a preset's categories beside its id only where the row is wide enough (a phone in landscape, or a tablet), separating two of them with a comma. On a portrait phone they remain available from the row's menu. Where they are shown, a preset filed under no category says so.
- The Steampunk theme's progress indicator is a rendered brass compass whose needle hunts about north, in the style of the app icon, centred on the preset screen while the first rows load.

### Fixed
- On a large screen with edge-swipe-back navigation, the connect screen's settings gear could sit partly inside the swipe strip; on the same screen it could also be silently unclickable in its own left half, since a sibling further down caught the touch first.
- The app no longer shows the default theme for a moment at launch before the chosen one appears.
- Connecting to a recognised instrument over USB no longer waits for the slower MIDI bus scan to finish first, which is what made launch take longer since the Pro-800 and Motif XS were added.
- The device report is offered on the connect screen's firmware warning, as documented; previously the button never appeared there.
- On a Nord, a preset copied or moved into an empty slot no longer shows at the bottom of the list under a second header for its bank until the listing is re-read.
- Connecting to an instrument that has not been granted USB permission before — an unrecognised Nord, or the app opened before plugging in — no longer waits forever for a permission result the app had made itself unable to receive.
- Reading a device report no longer starts over when the phone is rotated; the read continues in the background and the result is kept until it is shared or dismissed.
- Closing a Motif XS session no longer releases its USB connection while a read or a write is still in flight.
- Tapping a second destination while a copy or move is still running no longer queues a second operation.
- The cached preset listing is tied to the physical device, so plugging in another unit of the same model without restarting the app no longer shows the first unit's presets.
- Denying the USB permission dialog no longer brings the same dialog straight back; the connect screen says the permission was denied and asks again only on Retry.
- Plugging an instrument in while the app shows "No supported instrument was found" now connects to it, instead of leaving that screen standing until Retry is tapped.
- The preset screen no longer stays on "Connecting…" after the instrument is unplugged while a system dialog is up or the app is in the background; it returns to the connect screen, and plugging the same instrument back in reconnects on its own.
- A device report whose read was interrupted is marked incomplete, with the reads that failed listed on the debug screen and in the report's own failure map, instead of reaching "ready" like a complete one.
- A Nord session started after the app was killed mid-transfer discards what the instrument still had queued from the earlier session, instead of reading it as the answer to its first request; where the connection cannot be recovered, the error says to unplug and replug the cable.
- Unplugging the instrument now drops the cached preset list, so changes made from its own panel while disconnected (or a different unit of the same model on the same port) show on reconnect without a manual refresh.
- Deleting the last preset in a bank removes that bank from the index rail when empty slots are hidden.
- Choosing Disconnect on the firmware advisory now returns to the device list instead of a "Not connected" screen with nothing to tap.
- Plugging in a Behringer Pro-800 now offers to open the app, as it does for the other instruments.
- Backgrounding or locking the phone during a preset scan over USB now stops the scan at once, so the instrument no longer stays busy for nobody; the scan restarts on return.
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
