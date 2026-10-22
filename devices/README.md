# devices/

The per-instrument catalogs the app is built from. One file per instrument family, named for what it holds; the name is cosmetic, since each file declares its own `family` and that is what the Android registry dispatches on.

| File | Covers | Shape |
|---|---|---|
| `nord_devices.json` | every supported Nord model | its own native shape, described by `nord_devices.schema.json` |
| `behringer_pro800.json` | the Behringer Pro-800 | the generic `FamilyCatalog` shape |
| `yamaha_motif_xs.json` | the Yamaha Motif XS6, XS7 and XS8 | the generic `FamilyCatalog` shape |
| `fractal_axefx3.json` | the Fractal Audio Axe-Fx III, Mark I and Mark II | the generic `FamilyCatalog` shape |
| `moog_subsequent37.json` | the Moog Subsequent 37 | the generic `FamilyCatalog` shape |
| `motifxs_factory_voices.json` | the Motif XS's 1,217 factory voice names and category assignments | its own shape (bank → slot → name and categories) |
| `blanks/*.bin` | what the Motif XS, Axe-Fx III and Subsequent 37 families write over a slot to erase it | raw SysEx or a raw program; see `blanks/README.md` |

`nord_devices.schema.json` is a JSON Schema for `nord_devices.json` and can be used with any schema validator or editor plugin.

## How the app uses this directory

The `sync<Variant>DeviceCatalog` Gradle task copies every `*.json` file except the schemas, plus `blanks/*.bin`, into a generated assets directory under `android/app/build/` on every build; nothing under `src/main` holds a copy. Each family loads its own asset (`NordFamily.kt`, `Pro800Family.kt`, `MotifXsFamily.kt`, `AxeFx3Family.kt`, `Subsequent37Family.kt`), and the unit tests decode the real files from `devices/` itself (`CatalogParsesTest` for the Pro-800, Motif XS, Axe-Fx III and Subsequent 37 catalogs, `NordCatalogTaggerTest` and `MotifXsFactoryVoicesTest` for the other two), so a malformed catalog fails the unit tests rather than only the connect screen on a phone.

The `generate<Variant>UsbDeviceFilter` task generates the `xml/device_filter.xml` resource, in a generated resource directory under `android/app/build/`, from the USB vendor/product ids in these files. Android reads that file to launch the app when a matching USB device is attached, before any app code runs, so it cannot be loaded from an asset. The build generates it before it merges resources, and `CatalogParsesTest` checks it against the catalogs. A USB-matched device (every Nord, the Motif XS) contributes an entry by itself; one found through `MidiManager` (the Pro-800, the Axe-Fx III, the Subsequent 37) contributes one only through its `launchOnUsbAttach` ids, since Android would otherwise never offer the app when it is plugged in.

## Match types

A device entry's `match` decides which bus finds it and how:

- `usb`: a USB vendor/product id pair, opened on the USB host bus. `endpointOut`/`endpointIn` default to the Nord vendor interface's `0x03`/`0x82`; the Motif XS uses `0x01`/`0x82`. `midiCable`, when present, says the bulk endpoints carry USB-MIDI event packets on that cable rather than a vendor protocol, and the family wraps the transport in a `UsbMidiBulkTransport`.
- `midiIdentity`: a MIDI port whose device answers `probeHex` with a reply starting `replyPrefixHex`. `usbHint` narrows which ports are probed and never decides on its own. `launchOnUsbAttach` is a vendor/product id pair for the generated attach filter, so Android offers the app when the instrument is plugged in; the MIDI scan also probes a port with these ids first, and it never narrows probing. The probe must be read-only and idempotent.

The Nord file is in its own shape and every entry is a `usb` match on Clavia's vendor id `0x0FFC`.

## Display names

`name` is the full model name, shown wherever the instrument is named. The optional `variant`, in both catalog shapes, is the trailing part of `name` that tells a model from its siblings (`"name": "Yamaha Motif XS6", "variant": "6"`). Models of one family whose names differ only in their variant share one line of the connect screen's supported-instrument list: `Yamaha Motif XS6 / 7 / 8`. A model without a `variant` keeps a line of its own, so a new sibling joins a group only if it and the existing model both carry one. `SupportedModelsTest` checks that every `variant` ends its `name`.

## What is measured and what is transcribed

Everything in `nord_devices.json`, `behringer_pro800.json`, `yamaha_motif_xs.json`, `fractal_axefx3.json` and `moog_subsequent37.json` was read off an instrument or from its behaviour on the wire, with these exceptions:

- The Motif XS7 and XS8 product ids (`0x1043`, `0x1044`) are inferred from Yamaha's driver files and have not been confirmed on a unit; see `docs/PROTOCOLS.md`.
- **Assumption:** the Axe-Fx III Mark II shares the Mark I's USB ids (reported by owners) and differs only in the preset count its model record carries. No Mark II has been seen; it is recognised and connects behind a warning.
- **Assumption:** the Sub 37 and the Subsequent 37 CV share the Subsequent 37's USB ids, so plugging one in also offers the app. Neither is supported; the app identifies the instrument by its reply to a ping, which a Sub 37 may also answer.
- `programCategoryIds` and the category name tables in `nord_devices.json` cannot be read off the wire, which only carries numeric ids; they have to be established from the names the instrument itself displays for each id.

`motifxs_factory_voices.json` is transcribed rather than measured: its names and categories come from Yamaha's published Motif XS Data List (the spreadsheet edition, revision B0, at <https://usa.yamaha.com/files/download/other_assets/7/1198647/motifxs_en_dl_b0.zip> and <https://usa.yamaha.com/files/download/other_assets/4/1198324/motifxs_en_dl2_b0.zip>; the PDF edition, revision C0, at <https://usa.yamaha.com/files/download/other_assets/4/335014/motifxs_en_dl_c0.pdf>; both listed on Yamaha's Motif XS downloads page), except the two drum banks' categories, which were read off an instrument because the drum voice list publishes none. The file's own `source` field carries the same references. The eleven read-only banks never change and reading them over MIDI would take several minutes (extrapolated from the measured per-voice times), so the browser's Factory listing names them from this file and issues no round trips. The file holds display text the app never transmits, so `blanks/README.md`'s rule about wire payloads does not apply: a wrong entry is a wrong label, not a malformed write. The browser says where the names came from, the hidden debug screen can read one bank off a real instrument and diff it against this file, and `MotifXsFactoryVoicesTest` pins its bank labels and slot counts against `yamaha_motif_xs.json`.

## Adding a Nord instrument

This assumes the instrument speaks the protocol in `docs/PROTOCOLS.md`. No Kotlin source changes are needed.

1. Gather the USB vendor/product id, the bank-letter/group/slot layout, the display width, the stored name length, and at least one firmware version the app has been run against. The file-transfer protocol version and each storage area's allocation unit are not catalog fields; the instrument announces both at connect time (`NordDevice.detectProtocolVersionFileTransfer()`, the root category list's trailer).
2. Append one object to the `devices` array in `nord_devices.json`, following `nord_devices.schema.json`.
3. List the instrument's category-tag ids in `programCategoryIds`, an index into the top-level `programCategories` master list. Do not copy another instrument's list: which ids a Nord offers is per product, and an instrument shows `No Cat` for every id it does not implement. Leave the field out rather than guessing if it has not been established; the instrument then gets no category editing. Add `programCategoryNameOverrides` only for ids the instrument displays under a different name than the master list gives them.
4. Add `midiProgramSelect` (`{"msb": 0, "banksPerLsb": N}`) if the manual says how Bank Select and Program Change address the Program banks: `msb` is the Program bank's Bank Select MSB, `banksPerLsb` how many panel banks one LSB value covers. The grid gives the slots per bank, so nothing else is needed; the loader refuses a `banksPerLsb` whose banks do not fit 128 Program Change values. Leave it out where the manual does not say: the preset's Info then states no MIDI values.
5. Optionally, add a hand-written `NordProfile` fixture in `android/app/src/test/.../devices/nord/NordFixtures.kt` if the model's replies differ from the ones the protocol tests already cover; the protocol tests are driven from fixtures rather than from the catalog.

The device report the app shares (from the debug menu, or from the preset screen's "Share device details" for an unrecognised device) contains a `catalogEntry` object in exactly this shape, filled in with what the wire could settle, which is the intended starting point for a new entry.

## Adding an instrument of another family

The Pro-800, Motif XS, Axe-Fx III and Subsequent 37 files each describe one protocol implementation. A new instrument that speaks one of those protocols is a new object in that file's `devices` array (for the Motif XS, with its own `banks` table if its memory map differs from the shared one in `familyConfig`). A new protocol needs a new package under `android/app/src/main/java/.../devices/`, a new catalog file here, and an entry in `InstrumentRegistry`; see `docs/ARCHITECTURE.md`.
