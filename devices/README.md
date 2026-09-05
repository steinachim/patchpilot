# devices/

Per-instrument catalogs. One file per instrument family, named for what it holds — the name is cosmetic, since each file declares its own `family` and that is what the Android registry dispatches on:

| File | Covers | Shape |
|---|---|---|
| `nord_devices.json` | every supported Nord model | its own native shape |
| `behringer_pro800.json` | one Behringer Pro-800 | the generic `FamilyCatalog` shape |
| `yamaha_motif_xs.json` | the Yamaha Motif XS6/XS7/XS8 family | the generic `FamilyCatalog` shape |
| `motifxs_factory_voices.json` | the Motif XS's 1,217 factory voice names | its own shape (bank → slot → name) |

`nord_devices.schema.json` documents `nord_devices.json`'s shape (also usable directly with any JSON Schema validator/editor plugin). `blanks/` holds binary payloads the Motif XS family writes to erase a slot — see `blanks/README.md`.

`motifxs_factory_voices.json` is the one file here that is **transcribed rather than measured**: its names come from Yamaha's own Data List spreadsheets, not from an instrument this project has talked to. It exists because the eleven read-only banks never change and reading them over MIDI costs about seven and a half minutes, so the browser's Factory listing names them from here and issues no round trips at all. It is display text the app never transmits, which is why it does not fall under `blanks/README.md`'s rule that this project never invents bytes for a wire — a wrong entry here is a wrong label, not a malformed write. The browser says where the names came from, and the hidden debug screen can read one bank off a real instrument and diff it against this file. `MotifXsFactoryVoicesTest` pins its bank labels and slot counts against `yamaha_motif_xs.json`, so the two cannot drift apart silently.

Everything below concerns `nord_devices.json` specifically.

## devices/nord_devices.json

The source of truth for per-instrument USB/protocol constants, read by the Android app (`android/app/.../devices/nord/NordFamily.kt`) via a bundled asset copy, synced from this file automatically by the `syncDeviceCatalog` Gradle task before every build (see `android/app/build.gradle.kts`). Never hand-edit anything in `android/app/src/main/assets/` — the whole directory is generated and gitignored.

That task also generates `android/app/src/main/res/xml/device_filter.xml` from the USB ids in these catalogs, so adding a device here is enough to make the app launch when it is attached. A MIDI-matched instrument (the Pro-800) contributes no entry, because it is found through `MidiManager` rather than a `USB_DEVICE_ATTACHED` filter.

The Motif XS *does* contribute one, and is the reason `DeviceMatch.Usb` carries more than a VID/PID pair. It declares no MIDIStreaming interface, so `MidiManager` never sees it and it has to be matched on the USB bus like a Nord — but unlike a Nord its bulk endpoints carry USB-MIDI event packets rather than a vendor protocol. Hence `endpointOut`/`endpointIn` (it uses `0x01` OUT, not the Nord's `0x03`) and `midiCable`, whose presence is what tells the family to wrap the bulk transport in a `UsbMidiBulkTransport`.

See `docs/PROTOCOLS.md` for the Nord wire protocol every entry here is used by, and `android/app/.../devices/nord/NordDevice.kt` for the implementation.

## Adding a new instrument

This assumes the instrument's wire protocol is already understood — vendor/product id, message framing, and the operations needed to browse and manage presets. Adding it to the catalog is then:

1. Gather what the catalog needs: the USB vendor/product id, the bank-letter/group/slot layout, the display width, and at least one known-good firmware version. The file-transfer protocol version is *not* one of them — the instrument announces it at connect time, and no catalog entry declares it.
2. Append one object to the `devices` array in `nord_devices.json`, following `nord_devices.schema.json`. There is no file-transfer protocol version to supply and no root-category trailer length to configure: both are derived from the instrument's own responses at runtime (see `NordDevice.detectProtocolVersionFileTransfer()` and `NordDevice.detectRootCategoryTrailerLen()`).
3. List the instrument's category-tag ids in `programCategoryIds` — an index into the top-level `programCategories` master list. Don't copy another instrument's list: which ids a given Nord offers is genuinely per-product, and an instrument shows `"No Cat"` for every id it doesn't implement. This is the one value here that can't be read off the wire — the wire only ever carries the numeric id — so it has to be determined per instrument and left off (rather than guessed) if it hasn't been. Add `programCategoryNameOverrides` only for ids the instrument displays under a different name than the master list gives them.

   **Nothing to do for `sampleCategories`.** The other top-level master list holds the sample categories nothing in this app currently reads — it exists so a future listing can name a sample's category rather than print two numbers, and needs no per-device subset.
4. **Nothing to do for the storage units.** The instrument states each area's allocation unit itself, in the first word of that category's trailer in the root category list (see `docs/PROTOCOLS.md`), so there is nothing to measure or configure. No catalog field carries a unit — a previously-configured constant here turned out to disagree with what several instruments actually report, which is why the app derives it live instead of trusting a per-device value.
5. If the instrument has content tags of its own, check whether their content-version field is scaled the same way as the shared tags, and add any exception to the top-level `contentVersionScales`.
6. Add a Kotlin test fixture: a hand-written `DeviceProfile` constant in `android/app/src/test/.../devices/nord/NordFixtures.kt` (the JVM tests don't load the asset catalog).
7. **Nothing to do for `device_filter.xml`.** It is generated from the USB ids in these catalogs by the `generateUsbDeviceFilter` Gradle task before every build, so adding the device here is enough. Adding the pair by hand instead is a standing invitation to forget, and it fails silently — the app simply never launches on attach. That is why the task exists.

No new Kotlin source files are needed for a new Nord instrument — that's the point of this catalog.
