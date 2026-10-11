# Hardware test plan

Manual checks on a real phone against real instruments, for what the JVM tests cannot reach: permission dialogs, cable pulls, rotation, backgrounding, process death and the instruments' own timing. Run the debug menu's regression test first; it already covers loading, reading, every edit and the device report against whatever is connected. Categories and favorites it only lists, so their cases below are the only check they get.

**Result:** ✓ passed, ✗ failed, ◐ passed with an open item (named in the cell), – not run. **Last run:** the date and the unit of the most recent run.

## 1. Setup

Drive the phone over adb, pinned to one device: `adb -s <device> <command>`. Package id: `de.thewolfwalkexperience.software.patchpilot`.

| Action | Command | Notes |
|---|---|---|
| Rotate | `adb shell settings put system accelerometer_rotation 0`, then `adb shell settings put system user_rotation 1` | `0` portrait, `1` landscape |
| Background (Home) | `adb shell input keyevent KEYCODE_HOME` | `onPause`, not process death |
| Return to the app | `adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1` | |
| Lock the screen | `adb shell input keyevent KEYCODE_POWER` | Press again to wake |
| Hard kill | `adb shell am force-stop <package>` | Also discards saved state; not for cases that expect a restore |
| Soft kill (as a low-memory kill) | Background first, then `adb shell am kill <package>`; `adb shell pidof <package>` then prints nothing | Keeps saved state, so a relaunch from Recents restores |
| Memory pressure | `adb shell am send-trim-memory <package> RUNNING_CRITICAL`, or several large apps in the background | Asks; does not force a GC pause |
| Logs | `adb logcat --pid=$(adb shell pidof <package>)` | Tags are the class names: `ConnectionCoordinator` (every connection state), `PresetListings` (each listing's start and end), `UsbConnectionManager` (each permission request and its answer), `NordDevice`, `AndroidUsbBulkTransport`, `UsbMidiBulkTransport`, `Pro800Instrument`, `AxeFx3Device`, `Subsequent37Instrument` and others |

Needs a hand at the hardware: plugging and unplugging, the instrument's own panel, listening for a preset change, and a second host on a shared MIDI port.

## 2. Cross-cutting (USB host: Nord and Motif XS, unless a case says otherwise)

| # | Case | Steps | Pass when | Last run | Result |
|---|---|---|---|---|---|
| X1 | First permission grant | Uninstall and reinstall (`pm clear` keeps a USB grant), plug in, launch, accept | Connects; no hang | 2026-09-20, Nord Grand | ✓ |
| X2 | First permission denial | As X1, launched from the launcher with the cable in; tap Deny | One "did not get USB access" screen with Retry; no second dialog by itself; Retry asks again and connects | 2026-09-20, Nord Grand | ✓ |
| X3 | Launch with a permitted device attached | Grant once, force-stop, relaunch with the cable in | Connects at once, no dialog | 2026-09-20, Nord Grand | ✓ |
| X4 | Plugged in while backgrounded | Home, plug in, return; also from "No supported instrument was found" in the foreground | Connects with no Retry tap | 2026-09-20, Nord Grand | ✓ |
| X5 | Rotation mid regression run | Start the regression test, rotate | The run continues; its progress shows in the new orientation; the result is kept | 2026-09-20, Nord Grand | ✓ |
| X6 | Rotation mid device report | Start a device report, rotate | As X5 | 2026-09-20, Nord Grand | ✓ |
| X7 | Rotation mid listing | Open the presets, rotate mid-listing | The listing continues where it was | 2026-09-20, unit not recorded | ✓ |
| X8 | Home mid transfer | Start a listing or the regression run, Home for several seconds, return | A USB instrument's listing stops on Home and restarts on return; a MIDI instrument's (Pro-800, Axe-Fx III, Subsequent 37) keeps running. A report or regression run continues, and the return waits for it. No screen is left waiting | 2026-10-10, Nord Grand and Pro-800 | ✓ (listing: Nord Grand, Pro-800, and Home and lock on the Axe-Fx III and Subsequent 37 as A13 and S7; the report and regression run across Home ran on the Nord Grand and Pro-800 only) |
| X9 | Force-stop mid report, relaunch | Start a report, force-stop mid-read, relaunch | The running report is gone; the relaunch connects (logcat shows the queued bytes drained). A dead endpoint ends in "unplug the USB cable, plug it back in and try again" | 2026-09-20, Nord Grand | ✓ |
| X10 | Soft kill with a finished report held | Finish a report, soft kill before sharing it, relaunch from Recents | The report is restored; Share and Save work on it | 2026-09-20, Nord Grand | ✓ |
| X11 | Cable pulled mid listing | Start a listing, unplug | The detach is reported; a replug reconnects with nothing left of the aborted listing | 2026-10-10, Motif XS6 | ✓ |
| X12 | Cable pulled mid regression write | Unplug during a copy, rename, move or swap step | "Instrument disconnected" at once, and the run is gone; after reconnecting, a second regression run finds every slot consistent | 2026-10-09, Nord Grand | ✓ |
| X13 | Unplug and replug at once | Unplug and replug within a second, idle session | Recovers without a restart or a second permission request | 2026-10-09, Nord Grand | ✓ |
| X14 | Contents changed while unplugged | Browse, unplug; change the contents on the panel (or plug in another unit of the same model); replug without restarting the app | The listing shows the current contents, not the one from before the unplug | 2026-09-20, Nord Grand | ✓ |
| X15 | Unrelated USB device alongside | Plug a USB drive or keyboard in through a hub next to the instrument | The instrument is found and preferred; the other device is at most a picker row | 2026-09-20, unit not recorded | ✓ |
| X16 | Only an unrecognised device | Plug in something not in the catalog | "No supported instrument was found" or the picker; no crash | 2026-09-20, unit not recorded | ✓ |
| X17 | Bus power through a passive OTG adapter | Use the adapter an end user would have, not a powered hub | No dropouts | 2026-10-10, Nord Grand | ✓ (USB-C to USB-B cable, no hub; no unplanned dropouts in a day of sessions) |
| X18 | Screen lock mid transfer | Start a listing or regression run, lock, wait, unlock | As X8; a Motif XS leaves "dump in progress" within a second of the lock | 2026-09-20, Nord Grand | ✓ |
| X19 | Unplug on the debug screen | Idle on the debug screen, unplug; separately, Home and return with the cable in | Leaves for "Instrument disconnected"; after the return the buttons are enabled again | 2026-09-20, Nord Grand | ✓ |
| X20 | Unplug mid report, replug later | Unplug during a report, wait tens of seconds, replug: (a) in the foreground, (b) backgrounded, then return, (c) replugged while Android's attach dialog is up | "Instrument disconnected" as soon as the cable is out, then reconnected after the replug; never stuck on "Connecting…" | 2026-10-09, Nord Grand | ✓ |
| X21 | Report read cut short | Save a baseline report; start another, unplug mid-read, save what arrives; diff the two. Repeat from the preset screen's "Share device details" | Where the reads fail before the unplug is seen: "Device report ready — incomplete", naming the failed reads, and the JSON's `failures` map is not empty. Where the unplug is seen first: the report is cancelled and "Instrument disconnected" shows | 2026-10-10, Nord Grand | ✓ |
| X22 | Bank rail after deleting a bank's last preset | "Show empty slots" off: copy into an empty bank, then delete the copy | The bank leaves the rail with its header once the delete lands | 2026-09-20, Nord Grand | ✓ |
| X23 | Process death on the preset screen | On the presets: Home, soft kill, relaunch from Recents | Reconnects and lists the presets; never stays on "Connecting…" | 2026-10-10, Nord Grand | ✓ |
| X24 | Lock with auto-connect off | Auto-connect off, pick the instrument, lock, unlock | Back on its presets, not the picker | 2026-10-10, Nord Grand | ✓ |
| X25 | Two USB instruments | A Nord and a Motif XS on a hub: pick the one listed second, lock, unlock | Still on the one picked | 2026-10-11, Nord Grand and Stage 2 EX on a powered hub | ✓ (picker: the second row picked, the phone locked and unlocked: back on the same instrument; auto-connect on: the first row, the Grand, opened on its own, and again after lock and unlock) |
| X26 | Back during a device report | Start a report from the debug menu, go back to the presets, press Back | The picker appears at once; the report is cancelled | 2026-10-10, Nord Grand | ✓ |
| X27 | Rotation mid edit (any instrument) | Start a copy, rotate while it runs | The progress line stays, Back stays off until it lands, and the result shows | 2026-10-10, Nord Grand and Motif XS6 | ✓ (the progress line in landscape and the result seen on the Motif; the Back arrow's state is covered by `PresetScreenLifecycleTest`) |
| X29 | System back on the presets | Use the back gesture or key on the presets; then again, and the arrow, while picking a "Copy to…" destination | Back lands on the picker, as the arrow does; while picking, both end the pick and stay | 2026-10-10, Nord Grand | ✓ |
| X30 | Plugged in during a search | Nothing attached, on "No supported instrument was found": tap Retry, and plug the instrument in while the search runs | It is found once that search ends, with no second Retry | 2026-10-10, Nord Grand | ✓ |
| X31 | Report from an advisory | Behind an advisory (as N2 or P9), start the device report; while it reads, try Continue anyway; then tap Disconnect | Continue anyway and the share button stay off until the report is ready; Disconnect stops the read and lands on the picker | 2026-10-10, Nord Grand | ✓ |
| X32 | Second instrument after a denial | Two supported instruments attached, auto-connect on: Deny the permission for the first, tap "Show all devices", pick the second | The picker lists both; the second connects | 2026-10-11, Nord Grand and Stage 2 EX on a powered hub, fresh install | ✓ (auto-connect on by default: the Grand asked first and was denied; Show all devices listed both; the Stage 2 EX asked again and connected) |
| X33 | Permission request left unanswered | As X1, but leave the permission dialog up for over a minute, then Allow | After a minute the app shows "did not get USB access", visible once the dialog is closed; after Allow, Retry connects with no second dialog | 2026-10-10, Nord Grand | ✓ |
| X34 | Release build | Install the release APK (an uninstall first where a debug build is installed); look at the launcher icon, the connect screen, the preset list, Settings and the debug menu, in both themes | Nothing missing: icon, text, images and fonts as in a debug build | 2026-10-10, Pixel 6 | ✓ |
| X35 | Search speed | With virtual MIDI ports on the phone (another app's): Back from the presets to the device list; then nothing attached, Retry; then a Pro-800, Axe-Fx III or Subsequent 37 plugged in directly | The device list and "No supported instrument was found" appear within a second; the log's MIDI scan line counts the virtual ports as skipped; the MIDI instrument is still found | 2026-10-10, Nord Grand and Pro-800 | ✓ (114 ms with either attached; the Pro-800 found by probing 1 of 5 ports) |
| X36 | Retry reopens the failed instrument | Auto-connect off. On a Motif XS set MIDI In/Out away from USB, pick it, wait for the advice, set it back to USB, tap Retry. Separately: deny the USB permission, then tap Retry | The same instrument opens, with no stop at the device list | 2026-10-10, Motif XS6 | ✓ |
| X37 | One instrument through two paths | A MIDI instrument on the hub, with a MIDI interface or hub cabled to its DIN ports on the same hub (an Axe-Fx III and an iConnectivity mioXL) | Two rows, told apart by "via <interface>" on the one that is not its own USB connection; either connects | 2026-10-10, Axe-Fx III with an iConnectivity mioXL | ✓ (both rows list presets; the one via the interface is much slower, limited by the DIN MIDI rate) |
| X38 | Regression run behind a MIDI interface | The X37 setup, on the row marked "via": run the debug menu's regression test | It takes minutes, not seconds, and passes: no step times out because of the cable's speed | 2026-10-10, Axe-Fx III with an iConnectivity mioXL | ◐ open: a copy of preset 000, a read, a write and a delete passed (about 4 min for the copy); the full regression run not run |

## 3. Nord

| # | Case | Steps | Pass when | Last run | Result |
|---|---|---|---|---|---|
| N1 | Permission with a real Nord | X1 and X2 with a Nord | Ids match; the permission flow completes | 2026-09-20, Nord Grand | ✓ |
| N2 | Firmware advisory | Connect a Nord on firmware not in the catalog (simulate by editing `supportedFirmwareVersions`); then repeat and tap Disconnect | The advisory shows and the report is reachable from it; the session is not refused. Disconnect lands on the picker with the Nord listed; picking it raises the advisory again; Continue anyway connects | 2026-10-10, Nord Grand | ✓ |
| N3 | Panel refresh after an edit, per model | Rename, move, swap and set a category; watch the panel | The panel shows each change, on a Stage 2 EX and a Grand | 2026-10-10, Nord Stage 2 EX and Nord Grand | ✓ |
| N4 | Category lock after a kill mid operation | Force-stop during a rename, move, swap or category change; for a swap, between the regression test's two swap halves (the log line `Swapped … swapping back` marks the moment). Variants: no free user slot; exactly one | The panel is not left locked; a real preset is never displaced, only scratch copies remain. In the variants the run asks before testing on a stored preset: declined, no stored preset is touched; accepted, a kill between the swap halves leaves one swapped, not lost | 2026-10-10, Nord Grand | ✓ |
| N5 | Unplug during a locked operation | As N4, unplugging instead | As N4, and the app reports the disconnect | 2026-10-09, Nord Grand | ✓ |
| N6 | Two different Nords | Two supported Nords on a hub | One row each, in alphabetical order (the Grand before the Stage 2 EX), whatever order Android lists them in | 2026-10-11, Nord Grand and Stage 2 EX | ✓ |
| N7 | Instrument powered off mid session | Switch the Nord off, idle session | The app notices it has gone silent | 2026-09-20, Nord Grand | ✓ (switching off detaches the Nord; the 64-empty-reads path is unit-tested only) |
| N8 | Accepted advisory across a lock | As N2: Continue anyway, then lock and unlock | No second advisory | 2026-10-10, Nord Grand | ✓ |
| N9 | Category the model cannot name | On a program whose display shows `No Cat`, open Set category | The dialog shows "Not set" and Save stays off until a category is picked | 2026-10-10, Nord Grand | ✓ |
| N10 | Delete after a panel change | Store another program over a listed one on the panel, then delete that row in the app without refreshing; then move the program away on the panel and delete again | Refused both times, naming what the slot holds now, then that it is empty; nothing is erased | 2026-10-10, Nord Grand | ✓ |
| N11 | Edits load programs | Connect; then, with an unsaved change on the panel, rename the loaded program, and rename another one | The "Edits load programs" notice shows; each edit loads the program it changes, which reverts the unsaved change, as the notice says | 2026-10-10, Nord Grand | ✓ |
| N12 | Home as a listing starts | Tap Re-read and press Home within a fraction of a second, a few times; check the panel each time | The panel is never left locked; no unlock warning in the log | 2026-10-09, Nord Grand | ✓ |

## 4. Yamaha Motif XS

| # | Case | Steps | Pass when | Last run | Result |
|---|---|---|---|---|---|
| M1 | MIDI routing not USB | Utility → [F5] Control → [SF2] MIDI → MIDI In/Out away from USB, then connect | The steps to fix it are shown, not a timeout | 2026-09-20, Motif XS | ✓ |
| M4 | Listing interrupted early, mid, late | At under 10 %, about 50 % and over 90 %: rotate, Home and return, lock, unplug | A listing that did not finish is never cached; Home and lock end "dump in progress" at once | 2026-09-20, Motif XS | ✓ |
| M5 | Killed mid write | Force-stop while the panel shows "receiving midi bulk data" (a drum kit rename gives the widest window) | Record whether the instrument recovers or needs a power cycle | 2026-09-20, Motif XS | ✓ recovered on its own, no power cycle |
| M6 | Session closed mid read, reconnect at once | Leave or reconnect during a read, reconnect at once | The reconnect claims the interface | 2026-09-20, Motif XS | ✓ |
| M7 | Favorites | Set and clear a favorite several times | Each shows within 3 s; no hang. Never send a malformed favorites write: it can hang the instrument's MIDI until a power cycle | 2026-09-20, Motif XS | ✓ |
| M8 | XS7 and XS8 product ids | Connect an XS7 or XS8 | Recognised (the ids are assumed) | – | – deferred: no XS7 or XS8 unit |
| M9 | Move onto a slot changed on the panel | Store a voice from the panel into a slot the list shows as empty, then "Move to…" that slot in the app | Refused; the voice stored there is unchanged | 2026-10-10, Motif XS6 | ✓ |
| M10 | Pull to refresh mid listing | Over-pull at the top of the list while the user banks are still arriving | Nothing restarts; the listing goes on to the end | 2026-10-10, Motif XS6 | ✓ |
| M11 | Loading a voice in Performance mode | Put the instrument in Performance mode, tap a voice in the app, accept "Switch to Voice mode"; try Back and another edit while it switches | "Changing the instrument's state…" shows, Back and the edits stay off until the switch lands, then the voice loads | 2026-10-10, Motif XS6 | ✓ (the switch is too fast to tap anything; Back and the row menus were seen greyed out for an instant) |
| M12 | Destinations of the other kind | "Copy to…" and "Move to…" on a USER 1 voice; drag it over the USER DR rows | No USER DR slot is offered or highlighted; the reverse for a drum kit | 2026-10-10, Motif XS6 | ✓ |
| M13 | Firmware and advisory | Connect; then set `supportedFirmwareVersions` in `devices/yamaha_motif_xs.json` to `["1.50"]`, rebuild and connect | "Firmware: 1.60" and no advisory; with the edited catalog the advisory names 1.60 and Continue anyway connects | 2026-10-10, Motif XS6 | ✓ |
| M14 | Renaming the loaded voice | Load a voice and change something on the panel without storing; rename that voice in the app; then rename a different voice | The "Renaming reloads the voice" notice shows when connecting; renaming the loaded voice reloads it and drops the unsaved change, renaming another leaves the panel alone | 2026-10-10, Motif XS6 | ✓ |
| M15 | Voice with its name cleared | On the panel, clear the name of a stored voice (spaces or delete the characters; the instrument stores no name); refresh the list in the app; then try to copy another voice onto that slot | The voice is listed as "(unnamed)" where it carries a category, also with "Show empty slots" off; the copy is refused as the slot being occupied | 2026-10-10, Motif XS6 | ✓ (the panel stores an empty name and keeps the sound; listed as "(unnamed)", refused as a destination, delete empties it) |

## 5. Behringer Pro-800

| # | Case | Steps | Pass when | Last run | Result |
|---|---|---|---|---|---|
| P1 | No permission dialog | Connect fresh (cleared app data) | Connects with no USB permission prompt | 2026-09-20, Pro-800 1.4.6 | ✓ |
| P2 | The right port among several | Connect next to other MIDI ports, virtual ones included; then unplug and replug with the app backgrounded | Found by its probe reply; the replug offers the app and connects without Retry | 2026-09-20, Pro-800 | ✓ |
| P3 | Selection, by ear | Select about twenty presets, listening | "Selected" only once confirmed; the sound changes every time | 2026-09-20, Pro-800 | ✓ |
| P4 | MIDI RX channel off | Set the RX channel to OFF, select | Selection still works and is heard | 2026-09-20, Pro-800 | ✓ |
| P5 | Channel from the DIP switches | As P4, channel from the rear switches | As P4 | 2026-09-20, Pro-800 | ✓ |
| P6 | Shared MIDI port | A second host on the port (a MIDI monitor or the vendor's editor); full listing | Spliced replies are retried, never shown as failures | 2026-09-20, Pro-800 | ✓ |
| P7 | Unplug during a selection | Unplug within the 2.5 s confirmation | Clean failure, no hang; the instrument is sane after reconnecting | 2026-09-20, Pro-800 | ✓ |
| P8 | Unplug mid listing | As X11 | The drop is noticed; no partial listing is cached | 2026-09-20, Pro-800 | ✓ |
| P9 | Firmware advisory | Firmware other than 1.4.6 (simulate in the catalog) | The advisory shows; the session connects and works | 2026-09-20, Pro-800 | ✓ |

## 6. Fractal Audio Axe-Fx III

Not yet run in the app. Use scratch slots, and back the unit up with Axe-Edit III first.

| # | Case | Steps | Pass when | Last run | Result |
|---|---|---|---|---|---|
| A1 | Discovery and attach | Connect fresh next to another MIDI port; unplug and replug with the app backgrounded | No permission prompt; found by its firmware query; the replug offers the app and connects without Retry | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (found by its firmware query, no app permission; unplug, Home, replug: Android's attach dialog opened the app, which listed the three candidates on the hub instead of guessing, with no Retry needed) |
| A2 | Model and firmware | Connect; generate the device report | "Fractal Audio Axe-Fx III Mark I", firmware 32.06, no advisory; `capabilities` `336, 2048, 2048, 16, 512`; record `loadedPresetQueryWorks` and `loadedPresetNumber` against the display | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ ("Fractal Audio Axe-Fx III Mark I", firmware 32.06, 512 presets, no advisory, loaded-preset query works; `capabilities` and the loaded preset confirmed against the unit) |
| A3 | Connect notice | Dismiss the notice; replug; then "Don't show this again", force-stop, relaunch; then Settings → Show Hidden Messages Again, reconnect | Once per run; hidden for good after the tick; back after the Settings item | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (shown once per run, driven over adb: after a plain OK it is gone for the run and back in a new one; hidden for good after the tick; back after Settings, Show Hidden Messages Again; the replug itself was a reconnect in the same run) |
| A4 | Full listing | Time a listing; compare with Axe-Edit III | All 512 names match; `<EMPTY>` reads as empty; ids `000`–`511`; the rail jumps to `000`/`128`/`256`/`384` | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (512 slots in about 2 s over USB, about 20 s through a MIDI interface; names and empty slots confirmed against Axe-Edit III) |
| A5 | Selection | Load about ten presets, listening | Display and sound change every time | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ |
| A6 | Dump integrity | The device report three times | Every dump read is 17 messages (3,082 bytes ×16, then 11) and the reads agree; a damaged read is retried | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (three device reports, each complete; a dump read that stalled at 5 of 17 messages was retried after the port was reopened, and the report still finished complete; the reads of the three reports were not compared byte for byte) |
| A7 | Regression run | At least two empty slots; run it; check the slots in Axe-Edit III | Every step passes; only the scratch slots were touched, and they are empty again | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (13 passed, 1 skipped (categories and favorites), also with a MIDI interface attached; the slots confirmed empty in Axe-Edit III) |
| A8 | The loaded preset is restored | Load X; rename, copy, move X, swap X, delete X | After each edit the unit is back on X (at its new number after a move or swap) | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (a rename of another preset: the unit returned to the loaded one; copy of the loaded preset: back on it; move and swap: followed it to its new slot; delete of the loaded preset: the display stays on the now empty slot) |
| A9 | Names | Rename to 31 characters with `-_.()/!`; give a slot a 32-character name in Axe-Edit III and refresh | The renames read back exactly; a 32-character name listing whole means the limit can be 32 | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (a 31-character name with `-_.()/!` stored and read back exactly; Axe-Edit III itself accepts at most 31 characters, so the app's limit is right) |
| A10 | Unsaved panel changes | Change a parameter on X without saving, copy X in the app | Record whether the copy carries the change; X's change is lost, as the notice says | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (400 with an unsaved change, second FX switched to WAH: the copy holds the saved preset without the change; 400 is reloaded to its stored state, as the notice says) |
| A11 | Unplug mid write | Unplug during a copy's transfer | Clean failure; the slot holds its old content or the copy | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (the cable pulled during the store step of a copy: "Instrument disconnected" at once; the target held a complete copy, which copied on without complaint; the unit's panel was fine) |
| A12 | Advisories | Set `supportedFirmwareVersions` to `["31.00"]`, then `testedPresetCounts` to `[1024]`; rebuild and connect | Each raises its advisory; Continue anyway connects | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ |
| A13 | Home and lock during a listing | Start a listing; Home and return; then lock and unlock | The listing continues and is complete on return | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (Home and lock during a re-read: the listing finished in the background with all 512 slots; the list was complete on return) |
| A14 | Phone audio | Play audio while connected | Record whether it goes to the unit's USB audio | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ (phone audio is routed to the unit's USB audio; Android does this, the app has no part in it) |
| A15 | Mark II | On a Mark II or Mark II Turbo: A2, A4, then copy and delete above 511 | "Mark II" with 1,024 presets behind the untested-model advisory; edits above 511 work | – | – deferred: no Mark II unit |
| A16 | Copy a factory preset | Copy preset 000 to a free slot, then copy that copy to another | Both copies are accepted; the first reads back converted by the instrument (thousands of bytes differ), the second identical | 2026-10-10, Axe-Fx III Mark I 32.06 | ✓ |

## 7. Moog Subsequent 37

Not yet run in the app. Use scratch slots.

| # | Case | Steps | Pass when | Last run | Result |
|---|---|---|---|---|---|
| S1 | Discovery and attach | Connect fresh next to another MIDI port; unplug and replug with the app backgrounded | No permission prompt; found by the ping; the replug offers the app and connects without Retry | – | – |
| S2 | Name and firmware | Connect | "Moog Subsequent 37", firmware 1.2.0, no advisory | 2026-10-10, Subsequent 37 1.2.0 | ✓ ("Moog Subsequent 37", firmware 1.2.0, IN channel 4, no advisory) |
| S3 | Connect notices | As A3, for both notices | Each once per run; hidden for good; back after the Settings item | 2026-10-10, Subsequent 37 1.2.0 | ✓ (both notices show, can be hidden for good, and come back through Settings, Show Hidden Messages Again; confirmed by the user) |
| S4 | Full listing, three times | Pull to refresh three times; time each; compare with the panel | About 142 s each; record whether rows appear bank by bank or all at the end; 256 names; `Init Preset` hidden until "Show empty slots"; badges match the panel's CAT line | 2026-10-10, Subsequent 37 1.2.0 | ✓ (three listings of 150 s each, 256 names; rows stream in during the dump; names, empty slots and category badges spot-checked against the panel) |
| S5 | Dump integrity | During S4, read the `Subsequent37Instrument` log | No "did not arrive whole"; if there is one, record where the dump broke and how long the single reads took | 2026-10-10, Subsequent 37 1.2.0 | ✓ (no "did not arrive whole", dropped message or port recovery in three dumps) |
| S6 | Panel touched mid dump | Turn a knob about 60 s into a listing | Record whether the dump survives; if not, the listing finishes by single reads once the MIDI button stops blinking | – | – |
| S7 | Home and lock during a listing | As A13 | As A13 | 2026-10-10, Subsequent 37 1.2.0 | ✓ (Home for 30 s, and a locked screen, in the middle of a listing: both listings finished with all 256 slots; the list was complete on return) |
| S8 | Reconnect mid dump | About 30 s into a listing, pull and replug the cable | Record what the connect screen shows while the dump runs on; connecting works once the MIDI button stops blinking | – | – |
| S9 | Selection | Load about ten presets with IN CHANNEL at 1, ALL and 4, reconnecting between; then RCV PGM CHG off; then change IN CHANNEL without reconnecting | The panel follows every time; with RCV PGM CHG off the app shows the steps to fix it; after the channel change it still works | – | – |
| S10 | Busy during a listing | Tap a preset, open the report and rename while a listing runs | Each refused at once with the time left | 2026-10-10, Subsequent 37 1.2.0 | ✓ (a load and the device report were refused at once with "about 2 minutes left"; the row menu stays closed during a listing) |
| S11 | Regression run | At least two `Init Preset` slots; take a bulk dump with any SysEx tool before and after, and diff | Every step passes; only the scratch slots differ, holding the panel's Initialize | 2026-10-10, Subsequent 37 1.2.0 | ◐ open: 14 passed, 0 failed, 0 skipped on two `Init Preset` slots (B09/P16 and B10/P02, "the instrument is as it was"); the bulk-dump diff before and after not run |
| S12 | Names | Rename to 24 characters with `-_.()/!@`; try 25 characters, `é` and `Init Preset` | The 24 characters read back and show on two panel lines; the other three are refused before anything is written | 2026-10-10, Subsequent 37 1.2.0 | ✓ (24 characters with `-_.()/!@` read back exactly and shown on the panel as two lines of 12; the 25th character cannot be typed ("24 / 24"); `Init Preset` and `é` are refused in the dialog with their reasons, Rename greyed out) |
| S13 | Editing the loaded preset | Load X on the panel, rename X in the app, then SAVE X on the panel | Record whether the panel shows the new name, and whether its save writes the old name back | – | – |
| S14 | Categories | Set a scratch preset to User, then Percussion, reading the panel's CAT line | The panel shows User, then Percussion | 2026-10-10, Subsequent 37 1.2.0 | ✓ (a scratch copy set from Lead to User, then Percussion: the panel's CAT line followed each time) |
| S15 | Unplug mid swap | Swap two scratch presets, unplug between the writes | Clean failure; each slot holds one of the two presets (check on the panel); the log says whether the rollback ran | – | – |
| S16 | Device report | Generate it twice | Firmware 1.2.0; 268 settings words; IN and OUT channels match the panel; the two reads of the selected program agree | 2026-10-10, Subsequent 37 1.2.0 | ✓ (two reports identical apart from timing: firmware 1.2.0, 268 settings words, IN and OUT channel 4 as on the panel, the two reads of program 0 identical at 756 bytes, no failures) |
| S17 | Advisory | Set `supportedFirmwareVersions` to `["1.1.0"]`, rebuild, connect | The advisory names 1.2.0; Continue anyway connects | 2026-10-10, Subsequent 37 1.2.0 | ✓ (catalog firmware set to 1.1.0: the advisory shows, Disconnect and Continue anyway both work, the one-line banner and the "Share device details" button follow; the catalog was restored) |
| S18 | Unassigned category | Initialize a scratch preset on the panel (its CAT reads Misc), open Set category in the app | The dialog shows "Not set" and Save stays off until a category is picked; the panel's value is left alone on Cancel | – | – |
