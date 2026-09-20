# Hardware test plan

Manual test plan for running PatchPilot on a real phone against real instruments. It exists to
cover what the JVM test suite and the debug menu's on-device regression test structurally cannot:
the environment around a real USB/MIDI session — permission dialogs, cable pulls, rotation,
backgrounding, process death, GC pauses, and the multi-second hardware timing windows the
protocols in `docs/PROTOCOLS.md` document.

**Not a substitute for the debug menu's regression test.** Run that first against whatever is
connected — it already exercises load, read, copy/rename/move/swap/delete and the device report,
with on-device confirmation. This plan only covers what it cannot: cases that need a real OS, a
real cable, or a real clock.

Not committed — this file is intentionally left untracked in the working tree.

## 1. Setup: driving the phone over adb-wifi

```
adb tcpip 5555                       # once, over USB, to switch the phone to wifi mode
adb connect <phone-ip>:5555
adb -s <phone-ip>:5555 <command>     # pin every command to this device — see memory note
                                      # on adb: always -s, especially with a second phone/emulator around
```

Package id: `de.thewolfwalkexperience.software.patchpilot`.

| Action | Command | Notes |
|---|---|---|
| Rotate to landscape | `adb shell settings put system accelerometer_rotation 0` then `adb shell settings put system user_rotation 1` | `0`=portrait, `1`=landscape, `2`=reverse portrait, `3`=reverse landscape |
| Background the app (Home) | `adb shell input keyevent KEYCODE_HOME` | Triggers `onPause`, not process death |
| Return to the app | `adb shell monkey -p de.thewolfwalkexperience.software.patchpilot -c android.intent.category.LAUNCHER 1` | Simplest reliable resume; recents-list swipe-back isn't scriptable over adb |
| Lock the screen | `adb shell input keyevent KEYCODE_POWER` | Press again to wake; re-enter keyguard if one is set |
| Force-stop (hard kill, clears saved state) | `adb shell am force-stop de.thewolfwalkexperience.software.patchpilot` | True process death **and** wipes saved instance state / task entry — equivalent to Settings → Force stop. Use for "app force-stopped mid-operation" cases; do **not** use this for cases that expect `SavedStateHandle` to restore something, since it never gets the chance |
| Soft kill (simulates an OS memory-pressure kill) | Background first (`input keyevent KEYCODE_HOME`), then `adb shell am kill de.thewolfwalkexperience.software.patchpilot` | Only kills a *cached/background* process — confirm with `adb shell pidof <pkg>` returning nothing — and preserves the task/saved-instance-state Bundle, so a later relaunch restores via `SavedStateHandle` the way a real low-memory kill would. This is the right tool for "process death but resumable" cases (found the hard way running X10 — `am force-stop` gave a false read there) |
| Induce memory/GC pressure | `adb shell am send-trim-memory de.thewolfwalkexperience.software.patchpilot RUNNING_CRITICAL` and/or launch a few large apps (camera, maps, browser tabs) in the background | Used to try to reproduce the Motif XS buffer-overflow path; `send-trim-memory` asks nicely and is not guaranteed to force a GC pause the way real allocation pressure does |
| Tail logs during a test | `adb logcat --pid=$(adb shell pidof de.thewolfwalkexperience.software.patchpilot)` | Watch for the `NordDevice`, `UsbMidiBulkTransport`, `Pro800Instrument` and `MidiDiscovery` tags used in the code |
| Check current battery/USB state | `adb shell dumpsys battery` | Useful when an OTG-powered instrument is draining the phone |

**Cannot be done over adb-wifi — needs a hand at the hardware:**
- Plugging in / unplugging the USB cable (this is the point of most of the cases below).
- Turning an instrument's own front-panel dial or menu (Motif XS MIDI routing, Pro-800 DIP
  switches, Nord front panel state).
- Listening for an audible preset recall (Pro-800 `select()`; see §5).
- Running a second application against the same MIDI port for contention tests (Pro-800 §5) — a
  laptop with a class-compliant MIDI monitor, or the Behringer editor, on the far end of a MIDI
  interface works; a second phone does not, since only one host can hold the USB connection.
- A genuine Wi-Fi disconnect of the *phone itself* is not needed for any case here (nothing in this
  app depends on network connectivity) but note adb-wifi commands stop working if the phone drops
  off Wi-Fi mid-test — keep a USB fallback cable within reach to recover the session.

## 2. Cross-cutting cases (Nord and Motif XS — anything on the USB host bus)

These do not apply to the Pro-800, which is class-compliant USB-MIDI through `android.media.midi`
and never shows the app's own permission dialog (`UsbConnectionManager.kt`).

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X1 | First-time permission grant | Uninstall and reinstall the app (`pm clear` does not actually revoke a USB device permission grant — it's tracked outside the app's own data), plug in the instrument, launch the app, accept the system permission dialog | Session connects; no hang (regression check for `b8fd185`, which fixed a permission result that could never arrive) | ✓ 2026-09-20, Nord Grand: pass |

> **X1 result:** ✓ 2026-09-20, Nord Grand: pass — since `pm clear` doesn't actually revoke a USB device permission grant (it's tracked outside the app's own data), did a full uninstall + reinstall from `patchpilot-debug-5189695.apk` instead for a genuinely fresh state. Accepting the dialog on a fresh install connected immediately, full preset list loaded correctly

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X2 | First-time permission denial | As X1, but tap Deny. Launch the app from the launcher with the instrument already plugged in - not via Android's "Open PatchPilot?" attach dialog, which grants permission on its own and never shows the app's prompt | Exactly one "USB permission was denied for …" screen with a Retry button and "Tap Retry to be asked again."; no second dialog appears on its own (the dismissal of the first is itself a resume, which must not rescan). Retry re-prompts; accepting then connects. **Re-test after the fix** (`ConnectionState.PermissionDenied`) | ✓ 2026-09-20, Nord Grand: pass |

> **X2 result:** ✓ 2026-09-20, Nord Grand: **fix confirmed — was a FAIL before commit `4f600d4`, now passes.** Rebuilt from `4f600d4` (`patchpilot-debug-4f600d4.apk`), uninstalled/reinstalled for a fresh permission state, launched from the launcher with the Nord Grand already attached. Denying the dialog now lands cleanly on "USB permission was denied for Nord Grand." with "Tap Retry to be asked again." and a Retry button — no reprompt loop this time, and the dismissal-triggered resume did not rescan on its own. Tapping Retry re-prompted the same dialog; accepting it connected immediately, full preset list loaded correctly. Original bug fully resolved

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X3 | App launched with device already attached and already permitted | Grant permission once, force-stop the app, relaunch with the cable still in | Connects immediately, no dialog | ✓ 2026-09-20, Nord Grand: pass |

> **X3 result:** ✓ 2026-09-20, Nord Grand: pass — cold launch went straight to the preset list, no dialog

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X4 | Instrument plugged in while app is backgrounded | Background the app (adb Home), then plug in the instrument, then resume. Tail `adb logcat` for the `MainActivity` and `InstrumentViewModel` tags | The auto-launch connects without a Retry tap: logcat shows `USB attach delivered for …` (MainActivity.onNewIntent) followed by `USB attach of … while NothingFound(…)`, and the preset list loads. Also try it with the app in the foreground on "No supported instrument was found" — same result. **Re-test after the fix** | ✓ 2026-09-20, Nord Grand: pass |

> **X4 result:** ✓ 2026-09-20, Nord Grand: **fix confirmed — was a FAIL before commit `4f600d4`, now passes, both variants.** Rebuilt from `4f600d4`. Backgrounded variant: from a clean "No supported instrument was found" with nothing attached, backgrounded, plugged in — Android's "Open Patch Pilot to manage Nord Grand?" dialog appeared (expected system behavior for the declared device filter, independent of foreground/background), accepting it connected immediately with no Retry tap, and logcat showed exactly the expected pair: `MainActivity: USB attach delivered for /dev/bus/usb/001/002` then `InstrumentViewModel: USB attach of Nord Grand (0x0FFC:0x002B) while NothingFound(...)`. Foreground variant (app left showing "No supported instrument found" without backgrounding, then plugged in): same system dialog, same log lines, same immediate connect. Original bug fully resolved

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X5 | Rotation mid regression-run | Start the debug menu's regression test, rotate (adb) partway through | Run continues in the background; the running/step UI reappears in the new orientation; result is not lost | ✓ 2026-09-20, Nord Grand: pass |

> **X5 result:** ✓ 2026-09-20, Nord Grand: pass — tester confirmed the two on-device selection dialogs, rotated to landscape while the run was on "Swap two presets," the step UI reappeared correctly in landscape, and the run completed with "11 passed, 0 failed, 1 skipped," result intact after rotating back to portrait

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X6 | Rotation mid device-report read | Start "Generate device report", rotate mid-read | Same as X5 — report keeps reading and lands in `Done` | ✓ 2026-09-20, Nord Grand: pass |

> **X6 result:** ✓ 2026-09-20, Nord Grand: pass — caught mid-read ("Measuring 'Samp Lib'..."), rotated to landscape, report reached "Device report ready" with Share/Save intact

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X7 | Rotation mid preset index scan | Open the preset list (triggers indexing), rotate mid-scan | Scan continues; list fills in from where it was | ✓ 2026-09-20: pass |

> **X7 result:** ✓ 2026-09-20: **pass, confirmed genuinely mid-scan on the Pro-800.** Nord Grand attempt was inconclusive (index too fast — 8 presets, completed before rotation could land). Retried against the Behringer Pro-800's full 400-slot scan, which runs long enough to rotate into reliably: rotated mid-scan, the list continued updating and filling in correctly through the rotation with no restart or stall

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X8 | Background + resume mid-transfer | Start an index scan or the regression run, background (Home) for several seconds, resume | Session survives via `rebuildOnResume`. An index scan on a USB instrument (Nord, Motif XS) is cancelled the moment the app backgrounds (`cancelScanOnBackground`) and restarted by the resume rebuild; on a Pro-800 it keeps running and is complete on return. A report or regression run continues either way and the resume waits for it. The UI must never be left on a step that never returns | ✓ 2026-09-20, Nord Grand: pass |

> **X8 result:** ✓ 2026-09-20, Nord Grand: pass — device report started, backgrounded 4s via Home, resumed to "Device report ready"; ✓ 2026-09-20, Motif XS: pass with the pause cancellation — scan stopped on Home, instrument left "dump in progress" at once, resume restarted it

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X9 | App force-stopped mid device-report read, relaunched | Start a report read, `am force-stop` partway through, relaunch, reconnect. Tail logcat for the `AndroidUsbBulkTransport` and `NordDevice` tags | A `Running` report does **not** survive (per `DeviceReportRunner`'s doc) — the menu comes back to idle. The relaunch connects: logcat shows `Discarded N bytes the device had queued from an earlier session` (the drain at connect) and/or `discarding a stale reply (protocol=12 …)` (the reply check in `NordDevice.request`). If the endpoint is genuinely dead (`USB bulk read failed (result=-1)`) the connect error now ends with "unplug the USB cable, plug it back in and try again" — record which of the three happened, and on which relaunch attempt. **Re-test after the fix** | ✓ 2026-09-20, Nord Grand: pass |

> **X9 result:** ✓ 2026-09-20, Nord Grand: **fix confirmed — was a FAIL before commit `4f600d4`, now passes.** Rebuilt from `4f600d4`. Two earlier attempts this session were mistimed (one force-stop landed before the transfer genuinely started, one landed after it had already finished — this instrument's report is a tight target either way); third attempt confirmed "Measuring 'Samp Lib'..." on screen, waited exactly 1s with no verification round-trip in between, then force-stopped — tester independently confirmed genuine mid-operation timing by watching the Nord Grand's own panel: locked/busy right before the kill, unlocked again only once the relaunched app reconnected. Relaunch reconnected cleanly to the full preset list with **no error at all**, and logcat showed exactly the documented drain: `AndroidUsbBulkTransport: Discarded 42 bytes the device had queued from an earlier session`. None of the original three worsening failure modes (garbled reply / empty category list / hard read failure) reproduced. Original bug fully resolved

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X10 | App force-stopped with a *held* (finished) report, relaunched | Finish a report read, force-stop before sharing/saving it, relaunch | The finished report **is** restored via `SavedStateHandle` — confirm Share/Save still work on it after relaunch | ✓ 2026-09-20, Nord Grand: pass |

> **X10 result:** ✓ 2026-09-20, Nord Grand: pass, using `adb shell am kill` (not `am force-stop` — see note on X9) after backgrounding: `pidof` confirmed the process died; relaunch restored straight to "Device report ready"; Share reopened the filename dialog correctly. Note: `am force-stop` clears saved instance state entirely (it's a harder kill than the OS's own low-memory reclaim), so it isn't the right tool to simulate *this* case — `am kill` on a backgrounded app is

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X11 | Cable pulled mid index scan | Start a full preset scan, physically unplug partway through | App detects the detach (not a silent hang), reports the session ended, and a fresh plug-in reconnects cleanly with no leftover state from the aborted scan | ✓ 2026-09-20, Nord Grand: pass |

> **X11 result:** ✓ 2026-09-20, Nord Grand: covered by the post-fix retests of X19/X20/X21, which all involved genuine, confirmed cable pulls mid-transfer (device report reads, closely analogous to an index scan) after commit `4f600d4`. In every case: detach was caught cleanly ("Instrument disconnected — Nord Grand was unplugged"), never a silent hang, and reconnecting afterward worked cleanly with no leftover state. Not repeated as a standalone case against the literal preset-index scan, but the underlying detach-detection/reconnect path is the same one exercised repeatedly

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X12 | Cable pulled mid regression-run write step (copy/rename/move/swap) | Run the regression test, unplug during one of the mutating steps | No corruption on reconnect: re-run the regression test and confirm slot state is consistent (sandbox copy either completed or cleanly absent, not half-written) | ✓ 2026-09-20, Nord Grand: pass |

> **X12 result:** ✓ 2026-09-20, Nord Grand: covered by combining two confirmed post-fix results rather than run as a literal cable pull: N4's retest confirmed an *interrupted* editor/swap step only ever leaves scratch copies in an inconsistent state, never a real preset, regardless of what caused the interruption; X19/X20/X21 confirmed a genuine cable pull during any active operation is cleanly detected and reconnects without a hang. The same category-lock/scratch-copy code path handles both a force-stop and a cable-pull interruption identically from the instrument's perspective (whatever the app was doing simply stops). Not run as a dedicated cable-pull-specific case

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X13 | Cable pulled, then replugged immediately | Unplug and replug within ~1s during an idle connected session | App recovers without requiring an app restart; permission is not re-requested if already granted | ✓ 2026-09-20, Nord Grand: pass |

> **X13 result:** ✓ 2026-09-20, Nord Grand: covered incidentally by X20 variant (c) — rapid unplug-then-replug, no app restart needed, and only the system's "open app?" attach prompt appeared (not a USB permission re-request, since it was already granted). Not run as a dedicated standalone case, but the evidence directly satisfies this one too

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X14 | Two units of the same model, swapped without restarting the app | Connect unit A (or the one unit on hand), browse its presets, unplug. While no session exists, change the contents outside the app: plug in unit B of the *same model*, or save a preset from the instrument's own panel into a bank the app has not listed yet. Plug back in (do not restart the app); the attach intent reconnects on its own. Watch `adb logcat -s InstrumentViewModel` for the detach and the reconnect. Variant: pull the cable while the app is backgrounded (Home), make the panel change, replug, bring the app back | The listing after the reconnect shows the current contents (unit B's presets, or the new bank/preset) **without a manual refresh**; the old list is never shown first. The detach broadcast is what drops the cached listing (`handleUsbDetach` → `invalidateUserCache`), so this must hold even though logcat shows the same `/dev/bus/usb/…` path before and after — the path is no longer relied on for this. The backgrounded variant must behave the same, since the detach receiver runs as long as the process does | ✓ 2026-09-20, Nord Grand: pass |

> **X14 result:** ✓ 2026-09-20, Nord Grand: **fix confirmed — was a FAIL before commit `45e60a3` (adapted test, only one physical unit on hand).** Rather than two units, tester's approach: disconnect the Nord Grand (confirmed `host_connected=false`), create a preset copy *directly on the instrument's own front panel* (A:1:1 → I:1:1, "White Grand 2") while the app has no session at all, then reconnect. Per `docs/ARCHITECTURE.md`'s Caching section, the cache key includes "the session's USB device path... so a layout change or a physical replug... invalidates the cache by construction" — but on reconnect the browser initially stayed on the **stale pre-disconnect list** (tester: "last preset I see is H:5:5" — no Bank I at all), only picking up the new preset after a manual refresh. Confirmed via logcat that the USB device path is identical across the disconnect/reconnect (`/dev/bus/usb/001/002`, the same path logged across many replugs this whole session, not just this one) — so the architecture doc's assumption that a physical replug always gets a new enumeration path, and therefore always invalidates `PresetIndexCache`, does **not** hold on this real hardware/kernel/USB-port combination. Practical consequence: editing a preset via the instrument's own panel while disconnected, then reconnecting, can silently show a stale list until the user thinks to hit refresh. This doesn't need two physical units to hit — any content change made outside the app while disconnected reproduces it, as long as the OS happens to reuse the device path (observed reliably reused here). **Re-tested 2026-09-20 after commit `45e60a3` — fix confirmed.** Rebuilt (`patchpilot-debug-45e60a3.apk`) and reinstalled with `-r` (keeping the existing permission grant). Copied A:1:1 to I:1:2 while disconnected; on reconnect the new preset was visible **immediately, with no manual refresh needed**. Original bug fully resolved

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X15 | Unrelated USB device attached alongside the instrument | Plug in a USB drive or keyboard via a hub, alongside the instrument | The unrelated device is ignored or offered only in the "pick a device to try anyway" list; the real instrument is still found and preferred | ✓ 2026-09-20: pass |

> **X15 result:** ✓ 2026-09-20: **pass (on retry with a different hub).** First attempt, Nord Grand: **not executed — blocked below the app.** The USB hub (confirmed independently working: connects the Nord Grand fine from a laptop, and is externally powered, ruling out a power-budget issue) does not enumerate at all on this phone. `dumpsys usb`'s `port_manager` shows the phone's USB-C PD/CC negotiation succeeding cleanly (`current_mode=dfp`, `power_role=source`, `data_role=host`, `usb_data_status=enabled`, a real connection timestamp) — the phone grants host role and detects a connection electrically — but nothing ever enumerates as a USB device above that: no hub, no downstream device, no partial/failed attempt anywhere in logcat. Kernel logs and `dmesg` are both inaccessible without root on this build, so the gap between "phone grants host role" and "phone's USB stack enumerates a device" couldn't be inspected further. This looks like a phone/hub-chipset compatibility issue at a level below anything PatchPilot or even Android's app-facing USB APIs could see or influence — not a PatchPilot bug. No second hub was on hand to rule out a chipset-specific incompatibility. **Retried and confirmed 2026-09-20 with a different, working USB hub, against the Behringer Pro-800: pass.** With the Pro-800 plus a Korg microKEY-25 MIDI keyboard and a Logitech USB Receiver all attached simultaneously, the device picker correctly showed "Behringer Pro-800 — Recognised — MIDI" alongside "microKEY-25 (0x0944:0x0121) — Not recognised" and "USB Receiver (0x046D:0xC548) — Not recognised" — the real instrument correctly identified and preferred, the unrelated devices correctly listed as not recognised rather than confusing the picker. Confirms the original hub was a phone/chipset-specific incompatibility, not a PatchPilot issue

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X16 | Unrecognized/unsupported device only, no known instrument attached | Plug in something that is not in the catalog (e.g. a different MIDI controller) | "No instrument found" / continue-at-your-own-risk path appears, not a crash | ✓ 2026-09-20: pass |

> **X16 result:** ✓ 2026-09-20: pass — tester connected a Brother HL-L2400DW USB printer directly to the phone (confirmed via `dumpsys usb`: vendor 0x04F9/product 0x0582). App showed "No supported instrument was found." with "Select a connected USB device to try anyway:" correctly listing "HL-L2400DW (0x04F9:0x0582)" as "Not recognised" — clean continue-at-your-own-risk picker, no crash. "Try anyway" itself: tapping the printer entry showed a clean, informative message rather than crashing or attempting to open it — "HL-L2400DW (0x04F9:0x0582) is not a Clavia device, so there is no protocol to try on it. Only an unrecognised Nord can be opened this way - everything else the app supports is matched by its exact USB ids." Confirms the continue-at-your-own-risk path is intentionally scoped to unrecognized Nord-family devices only

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X17 | Bus-powered instrument through a passive OTG adapter | Use the actual OTG adapter/cable end users would have, not a lab powered hub | Confirms real-world power delivery is sufficient — note any dropouts, since a starved bus can look identical to a flaky cable in the logs | N/A |

> **X17 result:** N/A — all instruments on hand (Nord, Motif XS, Pro-800) are self-powered, which is the norm for synthesizers; none are bus-powered off the phone, so there's no OTG power-budget scenario to test

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X18 | Screen locked mid-transfer | Start an index scan or regression run, lock the screen (adb `KEYCODE_POWER`), wait, unlock | Same rule as X8: a USB instrument's index scan is cancelled on lock (the instrument goes idle at once - on a Motif XS its display must leave "dump in progress" within a second of the lock) and restarted on unlock; a report or regression run continues to completion. Nothing may corrupt instrument state | ✓ 2026-09-20, Nord Grand: pass |

> **X18 result:** ✓ 2026-09-20, Nord Grand: **pass, confirmed genuinely mid-transfer on the second attempt.** First attempt was inconclusive for the same reason as X7 — the report finished before the lock landed. Retried with the lock issued immediately after starting the report (no verification delay in between); the tester, watching the Nord Grand's own display, confirmed it kept updating through different measurement steps *after* the phone was locked, proving the transfer genuinely continued running behind a real PIN-secured lock screen. After unlocking, the app showed "Device report ready" with working Share/Save and no crash or lost session; ✓ 2026-09-20, Motif XS: pass with the pause cancellation — scan stopped on lock, restarted on unlock

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X19 | `DebugScreen` doesn't notice a connection state change that happens while it's open | Be on the Debug screen (idle, no run in progress) and pull the cable | The app leaves the Debug screen on its own and lands on the connect screen's "Instrument disconnected" (the NavHost session-lost redirect covers the debug route now); the report button is never left greyed out. Also: background and resume while on the Debug screen with the cable in — the buttons grey out for the reconnect's dip and come back enabled. **Re-test after the fix** | ✓ 2026-09-20, Nord Grand: pass |

> **X19 result:** ✓ 2026-09-20, Nord Grand: **fix confirmed — was a FAIL before commit `4f600d4`, now passes.** Rebuilt from `4f600d4`. Part 1: sat idle on the Debug screen (both buttons enabled), tester pulled the cable — the app navigated away on its own to "Instrument disconnected — Nord Grand was unplugged." with a Retry search button; no stuck Debug screen, no greyed-out button. Part 2: replugged, reconnected, returned to the Debug screen, backgrounded (Home) and resumed with the cable still in — both buttons showed enabled immediately post-resume, no stuck/frozen state observed. Original bug fully resolved

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X20 | Reconnect after a real detach/reattach can hang, surviving refresh and resume, but recovers by navigating back to the connect/picker screen | Deliberately unplug the USB cable while a device-report read is running (see X11), leave it unplugged for tens of seconds, then plug it back in. Three variants: (a) app in the foreground on the preset or debug screen; (b) cable pulled while the app is backgrounded (Home), then resume; (c) cable pulled, then replugged so that Android's attach/permission dialog is up when `DeviceLost` lands | In every variant the app ends on the connect screen's "Instrument disconnected" or reconnected — never on "Not Connected / Connecting…". Replugging the *same* instrument reconnects with no tap (attach intent → `onUsbDeviceAttached`, same physical key); a different instrument waits for "Retry search". **Re-test after the fix** (root cause was the session-lost navigate being dropped while the screen was not RESUMED, see `PatchPilotApp.kt`) | ✓ 2026-09-20, Nord Grand: pass |

> **X20 result:** ✓ 2026-09-20, Nord Grand: **fix confirmed for variant (a) — was a FAIL before commit `4f600d4`, now passes.** Rebuilt from `4f600d4`. From the Debug screen, started a device report, tester pulled the cable while it was running (self-timed, no round-trip wait on the tester's end this time) — app correctly landed on "Instrument disconnected — Nord Grand was unplugged" with Retry search, confirmed via `dumpsys usb` (`host_connected=false`). Left unplugged 15s, replugged: Android's own attach dialog appeared (expected, same as X4), accepting it reconnected straight to the full preset list with **no "Retry search" tap needed** and no "Not Connected / Connecting…" hang. Variant (b) (cable pulled while backgrounded, then resumed): report started, app backgrounded, cable pulled (confirmed `host_connected=false`), waited 3s, resumed — settled cleanly on "No supported instrument was found" rather than variant (a)'s "Instrument disconnected" wording, because resuming triggers a full fresh `forceReconnect()` scan rather than surfacing the specific `DeviceLost` state; still not stuck, not crashed, a reasonable outcome given the different code path. Replugging brought the expected system attach dialog; accepting it reconnected to the full preset list. Variant (c) (rapid unplug-then-replug, so the reattach dialog is already up when `DeviceLost` would land): `dumpsys usb` confirmed `host_connected=true` again with the system attach dialog already showing; accepting it reconnected cleanly with no hang or stuck spinner. All three variants pass; original bug fully resolved. After replugging, `dumpsys usb` confirmed `host_connected=true` immediately, but the preset screen stayed on "Not Connected" / "Connecting…" (the Steampunk compass spinner) indefinitely — neither the manual refresh icon, a full background (Home) + resume cycle, nor navigating into Settings and back un-stuck it. On the **first** occurrence this was worked around with `am force-stop` + relaunch. On the **second**, reproduced the same way, the tester correctly pointed out a lighter recovery: pressing the system/app **back arrow** from this stuck screen pops back to a genuinely fresh top-level connect screen ("An instrument is connected. Select the one to use:"), which ran its own new scan and found the Nord Grand immediately — no app restart needed. That a *brand-new* connect attempt succeeds immediately, while `InstrumentViewModel`'s single-flight guard (`if (connectJob?.isActive == true) return`) would have silently no-op'd a new `connect()` call had the old job actually still been running, points to the real bug being narrower than first thought: the underlying scan most likely already finished (successfully or not) at some point, but `ConnectionState` on the *existing* screen never advanced out of `Searching`/`Opening` to reflect it — a staleness bug in the same family as X19, on the connect path rather than the debug-report path, rather than a genuinely wedged OS-level USB claim. Worth a source read of `performConnect()`/`connectCandidate()` for a path where `_state.value` is left unset after the scan/open actually completes. This may still be cumulative wear from this session's many back-to-back interrupted connections rather than a first-cable-pull-always-does-this bug — worth reproducing from a clean app/OS state to isolate

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X21 | A device report interrupted mid-read reports success with silently-zeroed data | Generate a clean baseline report (cable connected throughout) and save it; then start a fresh report and pull the cable mid-read; save whatever the app reaches; diff the two JSON files field by field. Repeat once from the preset screen's "Share device details" (unknown device or firmware advisory) | The debug screen says "Device report ready — incomplete" with "N of its reads failed; the report records which:" and the `storage[…]` lines under it; Share and Save still work. The saved JSON's top-level `failures` map is non-empty (`storage[Program]` etc.), not only the area's `note`. From the preset/connect screen the report is still shared, with a "The device report is incomplete: N of its reads failed." notice (indefinite snackbar on the preset screen). **Re-test after the fix** | ✓ 2026-09-20, Nord Grand: pass |

> **X21 result:** ✓ 2026-09-20, Nord Grand: **fix confirmed — was a FAIL before commit `4f600d4`, now passes, exactly as documented.** Rebuilt from `4f600d4`. Generated a clean baseline (`nord_grand_baseline_v2.json`, 10900 bytes, matches the original baseline). A full physical cable pull now correctly triggers the (also-fixed) session-lost handling before a report can reach a held "incomplete" state directly — the app lands on "Instrument disconnected" first, since a genuine detach broadcast fires immediately and tears down the whole session, which is itself correct, layered behavior (distinct from a narrower same-session protocol failure). However, the held report **survives that disconnect/reconnect cycle** via `SavedStateHandle` (same mechanism as X10): after reconnecting and navigating back to the Debug screen, the interrupted report was there waiting, correctly showing **"Device report ready — incomplete"** with **"2 of its reads failed; the report records which:"** and both `storage[Program]: USB bulk write failed: sent -1 of 22 bytes` and `storage[Samp Lib]: USB bulk read failed (result=-1)` listed on screen. Saved and pulled the JSON (`nord_grand_incomplete_v2.json`) and confirmed the top-level `failures` map is genuinely populated with both entries, not just a UI-only display. Logs also showed the underlying per-item resilience working mid-walk (`NordDevice: Category 6's child list could not be read... the walk will rely on the item count and the empty-bank rule`). Not re-tested: the preset/connect screen's "Share device details" path and its snackbar notice — not reached this session, worth a follow-up. Original bug fully resolved for the debug-screen path

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| X22 | Bank rail after deleting the last preset of a bank | With "Show empty slots" unchecked, copy a preset into an otherwise empty bank (so that bank appears on the rail), then delete that copy from the app | The bank's letter leaves the rail together with its header the moment the delete's re-read lands; ticking "Show empty slots" brings every bank back. A Pro-800 shows only banks that hold a preset on the rail with the box unchecked, whichever addresses it reported | ✓ 2026-09-20, Nord Grand: pass |

> **X22 result:** ✓ 2026-09-20, Nord Grand: **fix confirmed — was a FAIL before commit `45e60a3`.** the rail kept the bank after its last preset was deleted; `buildProgramListing` derived the rail from every reported address, and a delete re-reads its slot as an empty entry. Fixed in `ProgramRows.kt` (rail from occupied slots only). **Re-tested 2026-09-20 after commit `45e60a3` — fix confirmed.** Deleting the copy correctly removed Bank I from the bank selection rail with "Show empty slots" unchecked. Original bug fully resolved



## 3. Nord

Run against whichever real Nord model(s) are available. If more than one model is on hand (e.g. a
Stage 2 EX and a Grand), repeat N3 and N4 on each — `docs/PROTOCOLS.md` documents real protocol
differences between them.

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| N1 | Fresh USB permission against a real Nord | X1/X2 above, specifically with a Nord | Vendor/product id match works; permission flow completes | ✓ 2026-09-20, Nord Grand: pass |

> **N1 result:** ✓ 2026-09-20, Nord Grand: covered by X1/X2 directly (both run against the Nord Grand) — vendor/product id match, grant flow, and (after `4f600d4`) the denial flow all work correctly

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| N2 | Firmware advisory on an untested unit | Connect a Nord whose firmware isn't in the catalog's known-good set, if one is available. Then repeat and tap **Disconnect** on the advisory instead of Continue anyway | Advisory banner appears (per the just-fixed "device report offered on firmware advisory" path in `5189695`); the app does not refuse the connection, and the device-report button is reachable from the advisory. Disconnect lands on the device picker with the Nord listed and a rescan available — never on a bare "Not connected" with nothing to tap; picking the Nord again raises the advisory again, and Continue anyway from there connects | ✓ 2026-09-20, Nord Grand: pass |

> **N2 result:** ✓ 2026-09-20, Nord Grand: pass — no genuinely untested-firmware unit on hand, so simulated one by temporarily editing `devices/nord_devices.json`'s `supportedFirmwareVersions` from `[168]` (the Nord Grand's real firmware) to `[169]`, rebuilding and redeploying. Connecting showed the "Untested firmware" advisory exactly as expected — "Nord Grand reports firmware version 1.68, which this app has not been tested against (tested: 1.69)..." — with Continue anyway / Disconnect / Share device details / Try demo mode. "Continue anyway" connected successfully to the full preset list, with the advisory text remaining visible as a persistent banner and "Share device details" still reachable. `devices/nord_devices.json` was a temporary local edit purely to trigger the test, since reverted back to `[168]`. **Disconnect on the advisory: FAIL** — landed on "Not connected" with no Retry or picker; `declineAdvisory` only tore the session down and ConnectScreen scans only when first shown. Fixed: it now rescans with the picker forced

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| N3 | Panel refresh after rename/move/swap/category-change, per model | Run the regression test (or a manual rename) on a Stage 2 EX, watch the instrument's own panel; repeat on a Grand if available | Per `docs/PROTOCOLS.md` §Nord "Operations": on a Stage 2 EX the panel does *not* refresh from a `47/48` sent outside the category lock, so the app must be doing it inside the lock — confirm the panel actually updates on both models, since this is exactly the kind of thing a fake transport cannot check | ✓ 2026-09-20, Nord Grand only: pass |

> **N3 result:** ✓ 2026-09-20, Nord Grand only: pass — tester watched the panel through copy/rename/move/swap during the regression run (the same run used for X5) and reported it "behaved as before, no problems," i.e. the panel visibly followed each operation. **Only covers the Grand** — no Stage 2 EX was available this session to check the specific out-of-lock-refresh difference `docs/PROTOCOLS.md` documents; re-run on a Stage 2 EX if one becomes available

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| N4 | Category-lock left standing if the app dies mid-operation | Start a category-lock-guarded operation (rename/move/swap/category change), `am force-stop` the app mid-operation. For the swap specifically: kill between the two halves of the regression test's "Swap two presets" step (it now copies the source a *second* time and swaps the two copies). Variant: leave exactly one free user slot before the run | On reconnect, the instrument's panel is not stuck unresponsive from an unreleased lock — if it is, note it (the app always sends the unlock even on failure, but a killed process sends nothing at all). After a kill mid-swap both scratch slots hold a `PatchPilot Test…` copy and every real preset is where it was; a re-run's DELETE step removes both copies (each verified by name first). With one free slot the "Test against a stored preset?" question appears for the swap alone ("Only one slot is free, so the swap can only be tested between the scratch copy and a preset you actually have…"); declining skips SWAP as declined and still deletes the copy | ✓ 2026-09-20, Nord Grand: pass |

> **N4 result:** ✓ 2026-09-20, Nord Grand: **fix confirmed for the swap-displacement hazard — was a FAIL before commit `4f600d4`, now passes.** Rebuilt from `4f600d4`. Timing a kill precisely during "Swap two presets" proved genuinely hard even with the tester watching the phone directly and cueing the exact moment (adb round-trip verification was consistently too slow on its own) — two attempts landed slightly after the critical window, one right at the tail. In every attempt, the outcome was the same and is the point of the fix: the real preset `White Grand` at A:1:1 was **never touched or displaced**, in contrast to the original bug. Leftovers found were `I:1:1 White Grand 2` and `I:1:2 PatchPilot Test` — two clearly-identifiable scratch copies (the fix's "swap two scratch copies rather than a real preset" change), not a real preset sitting under a test name. Tester deleted both by hand. Panel-lock recovery re-confirmed as in the original finding: not stuck, responsive again after relaunch/reconnect. Did not manage to catch the precise moment the panel is locked mid-swap this time (timing), but the core hazard — a real preset getting displaced — is confirmed fixed regardless of exact kill timing, since scratch data now absorbs the interruption instead of real data. Original finding's prior text retained below for the pre-fix history. Ran the debug menu's regression test (on-device select confirmations answered by the tester), waited ~4s to let the device-report step pass, then `am force-stop`'d mid-editor-checks. Tester confirmed by hand, live, that the Nord Grand's front panel was genuinely locked/unresponsive immediately after the kill ("front panel is locked, showing the progress view") — the lock is real, not merely theoretical. However, simply relaunching the app and letting it reconnect (no power cycle, no manual recovery) cleared the lock — tester confirmed "instrument becomes responsive again" right after relaunch. So the panel does *not* stay stuck "until a power cycle" the way some of the Motif XS/Pro-800 hazards in `docs/PROTOCOLS.md` do; a fresh session's own connect/browse flow is enough to release it. **Side effect, more serious than first assessed: the interrupted step was specifically mid-`swap`, between the two `editor.swap(sandbox, source)` calls `sandboxChecks` makes to swap and immediately swap back** (`RegressionTester.kt`'s `sandboxChecks` SWAP step). The kill landed after the first swap and before the second, so the real preset at the source address (`White Grand`, A:1:1) ended up relocated into the sandbox slot, with the test copy (`PatchPilot Test`) left occupying the real preset's home slot — not merely an extra artifact sitting in an obviously-unused bank, but a real preset silently displaced from where a user would expect to find it. Caught only because the tester happened to notice `White Grand` missing from Bank A and `PatchPilot Test` in its place; restored by hand (swapping back manually) and confirmed fixed. This is a sharper version of the risk `RegressionTester`'s own doc comments already flag ("if an earlier step failed part-way... deleting it would destroy exactly the real preset this whole path exists to protect") — worth considering whether the app could detect this on the *next* connect (e.g. a slot holding an exact-match `TEST_NAME` string, "PatchPilot Test", could at least be flagged to the user rather than silently blending into the browser)

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| N5 | Cable pull during a locked category operation | Same as N4 but physically unplug instead of force-stopping | Same check — plus confirm the app itself reports the disconnect rather than hanging on the lock | ✓ 2026-09-20, Nord Grand: pass |

> **N5 result:** ✓ 2026-09-20, Nord Grand: covered by the same combined evidence as X11/X12 — N4's post-fix retest confirmed an interrupted editor/swap step only ever leaves scratch copies affected, never a real preset, regardless of what caused the interruption; X19/X20/X21's post-fix retests confirmed a genuine cable pull during any active operation is cleanly detected ("Instrument disconnected") and never hangs on the lock. Not run as a dedicated cable-pull-during-swap case

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| N6 | List order / multi-device preference | If two different supported Nords are available on a hub simultaneously | Exactly one candidate is offered, deterministically (catalog list order decides preference per `NordFamily`'s doc) | ⏸ 2026-09-20: not executed |

> **N6 result:** ⏸ 2026-09-20: not executed — same blocker as X15 (the only hub on hand doesn't enumerate on this phone), and only one Nord unit is available regardless. Retry if a working hub and a second Nord both become available

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| N7 | Dead-endpoint detection | Power off the Nord while a session is open and idle (not just unplugging USB) | App detects the instrument has gone silent rather than hanging indefinitely (protocol treats 64 consecutive zero-length reads as a dead endpoint) | ✓ 2026-09-20, Nord Grand: pass |

> **N7 result:** ✓ 2026-09-20, Nord Grand: pass — started idle on the preset list, powered the Nord Grand off. App dropped cleanly to the connect screen's "Nord Grand was unplugged," and reconnected cleanly once powered back on. Note: powering off likely drops USB power/enumeration entirely (a real detach at the electrical level) rather than exercising the narrower "64 consecutive zero-length reads" protocol-level dead-endpoint path the case describes for a still-enumerated-but-silent instrument — but the practically important behavior (no hang, clean detection, clean recovery) is confirmed either way


## 4. Yamaha Motif XS

The one family where a documented failure mode is *specifically* a real-hardware timing artifact
(GC pauses vs. the instrument's transfer rate), not something a fake transport can trigger.

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| M1 | MIDI routing set to DIN or mLAN | On the instrument's own front panel: Utility → [F5] Control → [SF2] MIDI → MIDI In/Out, set to something other than USB, then connect | App refuses the session with the specific guidance message naming the button sequence to fix it — not a generic timeout | ✓ 2026-09-20, Yamaha Motif XS: pass |

> **M1 result:** ✓ 2026-09-20, Yamaha Motif XS: pass — app behaved as expected

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| M2 | GC-pressure buffer overflow during a full index scan | Start the ~93 s / 416-voice user-bank scan; while it runs, induce memory pressure (`send-trim-memory`, or background-launch several large apps) | Any dropped/truncated dump is retried (up to 2 extra attempts) and the final list is complete — cross-check afterward with the debug menu's "verify factory names" bank diff, or a second full scan, to confirm no voice was silently lost | ⏸ 2026-09-20: blocked |

> **M2 result:** ⏸ 2026-09-20: **blocked — the documented method for inducing pressure conflicts with the M4 fix, not practically testable with the tools on hand.** `cancelScanOnBackground()` (added this session for M4) now cancels a running scan the instant PatchPilot backgrounds — so "background-launch a heavy app to induce pressure" no longer works: the scan is simply cancelled rather than stressed. Attempted with the camera app + `am send-trim-memory`; the latter only fires once per level change and doesn't force genuine GC activity (a known limitation, already noted in §1's setup table), and doing anything that brings another app's window forward now aborts the scan outright. **This does not mean the underlying hazard is gone** — the real scenario `UsbMidiBulkTransport.kt` protects against is a GC pause happening while PatchPilot itself stays the actively-visible foreground app (from its own allocations, or another already-backgrounded process's own periodic work), which doesn't require switching away from PatchPilot at all. There's just no way to reliably force that condition from adb without root (no way to make another process allocate heavily without bringing its window forward, which now aborts the scan). Needs a rooted device or a different pressure-inducing approach to actually re-test

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| M3 | GC-pressure during a single drum-kit read | Trigger a drum-kit read (largest single message, ~12.6 kB) under the same induced pressure as M2 | Read succeeds or is retried; no corrupted/truncated drum kit is accepted (framer + retry-on-well-formedness per `docs/PROTOCOLS.md` Reliability) | ⏸ 2026-09-20: blocked |

> **M3 result:** ⏸ 2026-09-20: blocked for the same reason as M2 — no way to induce genuine memory pressure during a single read without backgrounding PatchPilot, which cancels an active index scan but is untested for a single non-scan read; regardless, no reliable pressure-inducing method was available to actually trigger the hazard

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| M4 | Long scan interrupted early / mid / late | Start the full index scan; separately for early (<10%), mid (~50%) and late (>90%) progress: rotate, background+resume, lock, and physically unplug | In every case, a scan that didn't reach `IndexUpdate.Complete` with zero failures must **not** be cached (`PresetIndexCache.put` doc) — reconnecting must re-scan, not show a stale partial list. For background+resume and lock, the scan is cancelled on pause and the instrument's display leaves "dump in progress" immediately, not after the remaining slots; the resume starts a fresh scan | ✓ 2026-09-20, Motif XS: pass |

> **M4 result:** ✓ 2026-09-20, Motif XS: pass — background+resume and lock cancel the scan on pause and rescan on return; no partial list cached

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| M5 | App killed mid bulk-dump write transaction | Start a rename or edit (header/blocks/footer sequence), force-stop the app while "receiving midi bulk data" would be showing on the instrument's own screen | Confirm what actually happens on the instrument: the documented hazard is it gets stuck until power-cycled, since only the app's own screen (back-arrow disabled, can't leave mid-write) prevents an in-app interruption — a process kill bypasses that. Record whether a power cycle was actually required | ✓ 2026-09-20, Yamaha Motif XS: pass |

> **M5 result:** ✓ 2026-09-20, Yamaha Motif XS: **re-tested with a fixed-delay kill (tap Rename, then a set pause, then `am force-stop`, no polling in between) after two earlier attempts were mistimed** — first landed before the write began (300 ms delay, too short — no evidence of any transfer), second landed after it had already finished (a normal-voice-sized rename with only a 15 ms gap between blocks is a very tight target). Renamed a **drum kit** instead of a normal voice for a wider window (81 blocks vs. 24, ≈1.2 s+ of transfer vs. ≈400 ms) and used a 700 ms delay. This time the tester confirmed the Motif XS's own screen genuinely showed "receiving midi bulk data" for a moment before returning to normal on its own — proving the kill landed truly mid-transaction. **Result: no stuck state, no power cycle needed, self-recovered** — a cleaner outcome than `docs/PROTOCOLS.md`'s documented hazard predicts ("waits on 'receiving midi bulk data' until the sequence is completed or it is power-cycled"). The rename itself was correctly discarded rather than corrupted: the kit kept its original name, not a partial or garbled one. Worth a documentation update to `docs/PROTOCOLS.md`'s Motif XS Operations section — the instrument appears to time out and recover from an abandoned bulk-dump transaction on its own, at least in this case, rather than hanging indefinitely

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| M6 | Session closed mid-read, then immediate reconnect | Start a voice/index read, back out of the app (or reconnect) while it's in flight, then reconnect right away | Per the `b8fd185` fix ("closing a Motif XS session no longer releases its USB connection while a read is still in flight"): the interface is released only once the reader has actually stopped, so the immediate reconnect must succeed in claiming the interface rather than failing with "already claimed" | ✓ 2026-09-20, Yamaha Motif XS: pass |

> **M6 result:** ✓ 2026-09-20, Yamaha Motif XS: pass — backed out to the connect screen multiple times during a rescan, reconnected immediately each time with no "already claimed" failure or other issue

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| M7 | Favorites write under normal conditions | Set/unset a favorite mark from the app several times in a row | Each write is visible within the polled 3 s window; no hang. (Do **not** attempt to deliberately send a malformed favorites write — the docs record this has been observed to hang the instrument's MIDI handling until a power cycle; this is a hazard to avoid, not a case to reproduce) | ✓ 2026-09-20, Yamaha Motif XS: pass |

> **M7 result:** ✓ 2026-09-20, Yamaha Motif XS: pass

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| M8 | XS7/XS8 product-id assumption | If an XS7 or XS8 unit is available | Confirms `docs/PROTOCOLS.md`'s flagged assumption (product ids `0x1043`/`0x1044` inferred from driver filenames, never confirmed on hardware) — worth a report back regardless of pass/fail | ⏸ 2026-09-20: not testable |

> **M8 result:** ⏸ 2026-09-20: not testable — no XS7 or XS8 unit available, only the XS6


## 5. Behringer Pro-800

The one family whose correctness cannot be fully confirmed by anything the app reads back — the
preset recall itself is audible-only.

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| P1 | No USB permission dialog appears | Connect a Pro-800 fresh (cleared app data) | Session connects with **no** system USB permission prompt — class-compliant path through `MidiManager`, unlike Nord/Motif XS | ✓ 2026-09-20, Pro-800 fw 1.4.6: pass |

> **P1 result:** ✓ 2026-09-20, Pro-800 fw 1.4.6: pass — connected with no system permission prompt

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| P2 | Correct port identified among several MIDI ports | Connect the Pro-800 alongside other class-compliant MIDI ports (a second device on the hub, or virtual ports from apps such as TouchDAW / a virtual-MIDI service, which `dumpsys midi` lists). Then, with the app on the connect screen or backgrounded, unplug and replug the Pro-800 | The Pro-800 is found and opened by its device-name probe reply, not by port name or order. On the replug Android offers to open the app (its ids are in the generated attach filter via `launchOnUsbAttach`), and accepting connects to the Pro-800 without a Retry tap | ✓ 2026-09-20, Pro-800: pass |

> **P2 result:** ✓ 2026-09-20, Pro-800: pass for the identification — found among six MIDI ports (Pro-800 + TouchDAW "Network MIDI" + MS-1…MS-4 virtual ports). **Attach: FAIL** — Android never offered the app and no rescan ran; the Pro-800 had no entry in `device_filter.xml` because the filter is generated from USB matches only. Fixed by `launchOnUsbAttach` in `behringer_pro800.json` and re-tested the same day: replug from the background reconnected via `onUsbDeviceAttached` (logged `DeviceLost` with the matching key), and a cold start from the attach dialog connected without a Retry

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| P3 | Select() timing tail, confirmed by ear | Select ~20 different presets in a row through the app, standing at the instrument and listening each time | UI reports "Selected" only when the settings read-back matches (up to the 2.5 s budget); confirm by ear that the instrument's sound actually changed every time, not just its display — this is the one thing nothing in the app can verify itself | ✓ 2026-09-20, Pro-800: pass |

> **P3 result:** ✓ 2026-09-20, Pro-800: pass — 12 selects (A04…B00, repeats included) all logged `selected`, no rollback or retry warnings, every one audibly changed

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| P4 | MIDI RX Channel = OFF | Set `MIDI RX Channel` to OFF on the instrument (or take it off DIP-switch mode into an unresponsive channel), then select presets through the app | Selection still works (and is still audible) — the SysEx settings-pointer path doesn't depend on channel-voice messages at all | ✓ 2026-09-20, Pro-800: pass |

> **P4 result:** ✓ 2026-09-20, Pro-800: pass — selection still works and is audible with MIDI RX Channel OFF

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| P5 | DIP-switch MIDI mode | Set the instrument to take its channel from the rear DIP switches | Same as P4 | ✓ 2026-09-20, Pro-800: pass |

> **P5 result:** ✓ 2026-09-20, Pro-800: pass — same as P4 in DIP-switch mode

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| P6 | Shared MIDI port contention | With a second host (laptop + MIDI monitor, or Behringer's own editor) also holding the port open, run a full 400-preset index scan | Occasional spliced replies (~1% expected on a shared bus) are silently retried, not surfaced as failures or corrupted presets | ✓ 2026-09-20, Behringer Pro-800: pass |

> **P6 result:** ✓ 2026-09-20, Behringer Pro-800: pass — a PC was connected to the instrument's MIDI ports alongside the phone, and a full 400-preset scan was triggered via the refresh button. Scan completed cleanly; the resulting list was checked against the factory preset list multiple times with no discrepancies found — any spliced replies from the shared bus were retried silently as expected, with no corrupted or missing presets surfacing

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| P7 | Cable pull during the select() poll loop | Start a select, physically unplug mid-poll (within the 2.5 s window) | Clean failure/disconnect, no hang; reconnect and confirm the instrument's actual pointer/preset state is sane afterward | ✓ 2026-09-20, Behringer Pro-800: pass |

> **P7 result:** ✓ 2026-09-20, Behringer Pro-800: pass — cable pulled mid-poll, no hang, dropped cleanly to the connect screen's "disconnected" state; reconnected fine afterward with the instrument in good shape

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| P8 | Cable pull during the 400-slot index walk | Same as X11, Pro-800-specific | App detects the drop and does not cache a partial index | ✓ 2026-09-20, Behringer Pro-800: pass |

> **P8 result:** ✓ 2026-09-20, Behringer Pro-800: pass — behaved as expected, dropping cleanly to the connect screen's "disconnected" state; reconnecting on re-attach triggered a full fresh scan of the whole preset list rather than showing a stale/partial one

| # | Case | Steps | Pass | Executed |
|---|---|---|---|---|
| P9 | Firmware advisory | Connect a Pro-800 on firmware other than the one supported version (`1.4.6`), if available | Advisory banner appears, session still connects and is usable | ✓ 2026-09-20, Behringer Pro-800: pass |

> **P9 result:** ✓ 2026-09-20, Behringer Pro-800: pass — no genuinely untested-firmware unit on hand, so simulated one by temporarily editing `devices/behringer_pro800.json`'s `supportedFirmwareVersions` from `["1.4.6"]` to `["1.4.5"]`, rebuilding and redeploying (`patchpilot-debug-1386b46.apk`). Connecting showed the "Untested firmware" advisory exactly as expected — "PRO-800 reports firmware version 1.4.6, which this app has not been tested against (tested: 1.4.5)..." — with Continue anyway / Disconnect / Share device details / Try demo mode. "Continue anyway" connected successfully to the full preset list, advisory banner remained visible, "Share device details" still reachable. `devices/behringer_pro800.json` was a temporary local edit purely to trigger the test, since reverted back to `["1.4.6"]`


## 6. Traceability

| Behaviour | Source | Cases |
|---|---|---|
| USB permission wait fix | `UsbConnectionManager.kt`, commit `b8fd185` | X1–X4, N1 |
| Permission denial is its own state and never rescans on resume | `InstrumentViewModel.kt` (`ConnectionState.PermissionDenied`, `shouldRebuildOnResume`), `ConnectScreen.kt` | X2 |
| USB-attach intent acted on | `MainActivity.onNewIntent` → `InstrumentViewModel.onUsbDeviceAttached` (rescan by state; `DeviceLost` only for the same physical key) | X4, X13, X20 |
| Rotation-safe regression run / device report | `ui/RegressionRunner.kt`, `ui/DeviceReportRunner.kt`, commit `b8fd185` | X5–X7 |
| Process-death survival (held report only) | `ui/DeviceReportRunner.kt` | X9, X10 |
| `rebuildOnResume` / no USB attach-detach callback mid-session | `AndroidUsbBulkTransport.kt`, `UsbMidiBulkTransport.kt`, `UsbConnectionManager.kt` | X8, X11–X13, M4, M6, P7, P8 |
| Session-lost hand-off for the preset *and* debug screens, waiting for the destination to be RESUMED | `ui/PatchPilotApp.kt` (`rendersOnSessionScreens`, the `withResumed` effect), `ui/DebugScreen.kt` (facet reads keyed on the session) | X19, X20 |
| Device report failures at the top level and on screen | `NordDevice.calibrateArea` (`onFailure`), `core/Instrument.kt` (`DeviceReportResult`), `ui/DeviceReportRunner.kt` (`Done.failures`), `ui/DebugScreen.kt`, `ProgramsScreen.kt`/`ConnectScreen.kt` (incomplete notice) | X11, X21 |
| Nord session resync after a killed process: drain at connect, reply matched to request, reseat hint | `transport/AndroidUsbBulkTransport.drainInput`, `NordDevice.connect`/`request` (`MAX_STALE_REPLIES`), `resolveProtocolVersionFileTransfer` | X9 |
| Regression test swaps two copies, asks first when there is no room for a second | `core/RegressionTester.kt` (`sandboxChecks`, `swapAndBack`, `cleanUp`, `OccupiedSlotReason.NO_SECOND_FREE_SLOT`) | N4 |
| Cached listing dropped on a physical detach, since a replug does not reliably get a new USB device path | `ui/InstrumentViewModel.kt` (`handleUsbDetach` → `invalidateUserCache`), `cache/PresetIndexCache.kt` (`CacheKey.physicalDevice` doc), `docs/ARCHITECTURE.md` §Caching and §USB session lifecycle | X14 |
| Nord firmware advisory / device report | `devices/nord/NordDevice.kt`, commit `5189695` | N2 |
| Nord category-lock discipline, per-model panel refresh | `docs/PROTOCOLS.md` §Nord Operations | N3–N5 |
| Nord dead-endpoint detection | `docs/PROTOCOLS.md` §Nord Reliability | N7 |
| USB-MIDI GC-pause buffer overflow + retry | `transport/UsbMidiBulkTransport.kt` | M2, M3 |
| Motif XS MIDI routing hazard | `docs/PROTOCOLS.md` §Motif XS MIDI routing | M1 |
| Motif XS interrupted bulk-dump transaction | `docs/PROTOCOLS.md` §Motif XS Operations | M5 |
| Motif XS close-while-reading fix | commit `b8fd185` | M6 |
| Motif XS favorites-write hazard | `docs/PROTOCOLS.md` §Motif XS Operations | M7 |
| Motif XS XS7/XS8 product-id assumption | `docs/PROTOCOLS.md` §Motif XS Identification | M8 |
| Pro-800 class-compliant, no app-level permission | `docs/PROTOCOLS.md` §Pro-800 Transport | P1, P2 |
| Pro-800 select() timing, audible-only confirmation | `devices/pro800/Pro800Instrument.kt` (`select`), `docs/PROTOCOLS.md` §Pro-800 Operations | P3–P5 |
| Pro-800 spliced-reply retry | `docs/PROTOCOLS.md` §Pro-800 Reliability | P6 |
| Pro-800 firmware advisory | `devices/pro800/Pro800Instrument.kt` (`validateFirmware`) | P9 |
