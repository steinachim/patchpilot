# Architecture

PatchPilot is a native Android app for browsing, organizing, and editing presets on supported hardware synthesizers over USB. It is a thin client: there is no server, no account system, and no persistent storage beyond an in-memory session cache. All state lives either on the connected instrument or in the current app session.

## Package layout

All source lives under `de.thewolfwalkexperience.software.patchpilot`.

| Package | Responsibility |
|---|---|
| `core/` | Device-agnostic domain model. The `Instrument` interface is the central abstraction the rest of the app is built around. |
| `transport/` | Byte-level I/O. `Transport` and its `AndroidUsbBulkTransport`/`AndroidMidiTransport`/`UsbMidiBulkTransport` implementations carry bytes with no protocol knowledge, so device code can be tested without hardware. |
| `midi/` | SysEx message framing (`SysExFramer`) and request/response correlation (`SysExExchange`), shared by the two families that use SysEx. |
| `discovery/` | Finds candidate instruments on USB and MIDI buses and merges them into one deduplicated list. |
| `catalog/` | Loads per-family JSON device descriptors and maps a discovered device to the family implementation that should handle it. |
| `devices/nord/`, `devices/pro800/`, `devices/motifxs/` | One protocol implementation per device family, each adapting its wire protocol onto `core.Instrument`. |
| `demo/` | An in-memory fake instrument for exploring the UI without hardware. |
| `usb/` | Android USB host APIs: device enumeration and runtime permission handling. |
| `cache/` | An in-memory decorator that caches a browsed preset index across app-foreground cycles. |
| `ui/` | Jetpack Compose screens, one shared ViewModel, and navigation. |
| `ui/theme/` | The theme system: light/dark Material and an alternate "Steampunk" skin, and the seam screens use to draw themselves differently per theme. |

## The `Instrument` abstraction

`core.Instrument` is an interface with a mandatory `browser` facet (list/select presets) and several optional, nullable facets (`editor`, `selector`, `transfer`, `report`, ...). A device family implements only the facets its instrument actually supports; the UI checks for a facet's presence rather than catching an "unsupported operation" exception. This was chosen over a single fat interface (which would force every family to implement no-ops for capabilities it lacks) and over a capability-enum-plus-fat-interface split (which still requires every caller to check the enum before calling). Nullability makes the check and the capability the same piece of information.

The abstraction is deliberately drawn above the wire-message level: the UI consumes "a list of named, addressable slots," not the shape of any device's protocol messages. There is no shared message or plugin-ABI type across families — a CRC-framed bulk protocol (Nord), a SysEx byte stream over class-compliant MIDI (Pro-800), and a SysEx byte stream over raw bulk (Motif XS) have no useful common representation below the operation level.

Related types:
- `SlotAddress` — a canonical `(bank, slot)` address, with a per-family `AddressFormat` responsible for rendering and parsing the family's own id shape. The UI never parses an id string itself.
- `IndexUpdate` / `Flow<IndexUpdate>` — preset listing is a stream, not a single suspending call, because some instruments (Pro-800, Motif XS) have no directory and must be queried slot-by-slot to learn each preset's name. Rows appear incrementally as they arrive rather than only after a complete scan.
- `InstrumentException` — distinguishes a device-state block (`BlockedByDeviceState`, e.g. wrong mode) from a capability gap (`NotSupported`), so the UI can react differently to each.
- `RegressionTester` — a facet-driven, family-agnostic self-test that exercises whatever facets an instrument exposes.

## Transport and discovery

`transport.Transport` is the shared interface; `AndroidUsbBulkTransport`, `AndroidMidiTransport`, and `UsbMidiBulkTransport` are its production implementations (the last hand-packs USB-MIDI event packets over a raw bulk endpoint, for a device with no class-compliant MIDI interface). Device code depends only on `Transport`, so protocol logic is unit-testable against a scripted fake with no hardware or emulator involved.

`discovery.DeviceDiscovery` merges USB and MIDI candidates into a `DeviceMatch` (`Usb` or `MidiIdentity`). A MIDI device is always matched by an identity probe — a read-only, idempotent request the family declares — never by port name, since port names are not a reliable way to identify an instrument.

## Catalog and adding a new device family

Each family is described by one JSON file under `devices/` (vendor/product id or MIDI identity match, bank geometry, supported firmware versions). `InstrumentRegistry` maps a family name to its factory. Android's `res/xml/device_filter.xml` — which the OS reads to decide whether to launch the app when a USB device is attached — is generated from these JSON files by a Gradle task rather than maintained by hand, so a new device can't be added to the catalog without also being wired into USB attach handling.

Adding a new device that speaks an already-implemented family's protocol (see "Nord family scope" below) is a catalog entry: vendor/product id, bank layout, and supported firmware version. Adding a genuinely new protocol requires a new package under `devices/`, implementing at minimum `Instrument.browser`, with no shared wire-level type to conform to.

`catalog.UnknownDevicePolicy` governs whether an unrecognized USB device may be opened on a guess against a known family's protocol. This is permitted only for a vendor id belonging to a family with at least two verified members sharing one protocol — today, only Nord/Clavia. A single-member family (Behringer, Yamaha) contributes no inference about sibling devices from the same vendor.

### Nord family scope

Nord Stage 2EX and Nord Grand are two catalog entries sharing one implementation (`devices/nord/NordDevice.kt`, `NordInstrument.kt`); every protocol-level behavioral difference between them is expressed as data derived from the instrument's own reported protocol version, not as per-device code branches. This is why a new device already speaking this protocol is expected to require only a catalog entry.

## Caching

`cache.CachingBrowser` decorates a family's `PresetBrowser` and caches a browsed preset index in memory, keyed by instrument identity, firmware version, and bank layout — so a decoder or layout change invalidates the cache by construction rather than requiring an explicit version bump. It targets the cost of resuming a session (re-scanning on every app-foreground event), not first connection, since re-scanning was the actual repeated cost. It is wired only at the ViewModel; no device or family code is aware caching exists. The cache is never persisted to disk: there is no durable identity for a physical instrument across app restarts, and a decoder fix with no wire-protocol change would otherwise require a cache-invalidation mechanism with no reliable trigger.

## UI and concurrency

The UI is Jetpack Compose with Navigation-Compose across a small fixed set of routes (connect, programs, settings, the open-source licenses screens, and a hidden debug/regression screen), backed by one `InstrumentViewModel` that owns the currently connected `Instrument` and exposes a `StateFlow<ConnectionState>`. The ViewModel constructs `UsbConnectionManager` directly rather than through dependency injection — a known simplification, not a pattern to extend.

All blocking device I/O runs on `Dispatchers.IO` under structured concurrency (`viewModelScope` / `rememberCoroutineScope()`). Cleanup code (releasing a device lock) runs under `NonCancellable` so it completes even if the enclosing coroutine is cancelled, and every `catch` block in `ui/` rethrows `CancellationException` before handling any other exception, so cancellation is never mistaken for a failure.

## Theming

`AppTheme` is a two-value enum — `Default` (stock Material 3: follows the system light/dark setting, with dynamic wallpaper-derived color on API 31+) and `Steampunk` (a single committed dark look that does not follow the system setting, closer to a fixed application skin than a Material variant). The user's choice is persisted across launches by `ThemePreferences`, backed by DataStore and keyed by the enum's name rather than its ordinal, so an unrecognized stored value (e.g. after an enum reorder) falls back to `Default` instead of silently landing on the wrong theme.

`PatchPilotTheme` maps the active `AppTheme` to a `ColorScheme`/`Typography`/`Shapes` triple and wraps the app in one `MaterialTheme` — every stock Material component (buttons, dialogs, menus) gets the swap for free. That covers tokens, but not bespoke composables and decoration a token swap can't produce: a pressure-gauge-styled progress indicator, a riveted screen frame, a mechanical slot bezel. Those live behind a separate `ThemeStyle` interface (`DefaultThemeStyle` is stock components/no-op decoration; `SteampunkThemeStyle` draws the bespoke ones), provided as `LocalThemeStyle` alongside `MaterialTheme`. Screens read `LocalThemeStyle.current` and never branch on the raw `AppTheme` value directly, so adding a third theme is implementing `ThemeStyle` once and mapping it in `AppTheme.style` — no screen file changes.

**`MaterialTheme`/the navigation host are composed from exactly one call site**, never once per branch of a `when (appTheme)`. Calling them from two different branches gives the content two different positions in the composition's slot table, so switching themes reads as "the old position's subtree went away, a new one appeared" — which tears down and rebuilds the whole subtree, silently resetting the nav controller's back stack to the start destination. This was caught on a real device (picking Steampunk from Settings dropped straight back to the connect screen), not in review; the fix is to compute the color/type/shape values first and call `MaterialTheme`/content once, after the `when`s, so a theme switch only ever changes what `MaterialTheme` resolves to.

## Demo mode

`demo.DemoInstrument` implements `core.Instrument` directly against an in-memory `DemoLibrary`, rather than simulating wire bytes through a fake transport. This was chosen once a second device family existed, to avoid maintaining a second fake wire protocol purely for demo purposes.

## Testing

The test suite is JUnit-only JVM unit tests against a stubbed Android SDK jar; there are no instrumented tests and no Compose UI tests. `usb/` is thin enough to be verified against real hardware directly rather than through an instrumented test. There is no `InstrumentViewModel` test suite, since it has no dependency-injection seam to substitute `UsbConnectionManager`; decisions worth testing in isolation (e.g. `UnknownDevicePolicy`) are extracted into standalone units instead.

Device protocol tests run against a scripted fake transport (`ReplayTransport` for Nord, equivalents for the other families) that serves fixed request/response pairs and asserts message ordering. Fixture byte sequences are representative sample data covering documented edge cases (variable-length fields, boundary addresses, malformed or spliced replies) rather than values generated by the same code under test, so a test failure reflects a real decoding defect rather than the encoder and decoder agreeing with each other by construction.

## Known limitations

- No dependency injection; the ViewModel constructs its dependencies directly.
- The app manages exactly one connected instrument at a time.
- No on-disk persistence of the preset index cache or user preferences beyond Android's own settings storage.
- No instrumented or Compose UI test coverage.
