# Device protocols

The wire protocols Patch Pilot uses to talk to each supported instrument, limited to the messages the app sends. This is a reference for maintainers, not a full specification of any vendor's protocol. Every statement is measured on the instrument named unless it is marked as an assumption. The USB ids, bank layouts and tested firmware versions are in the catalog files under `devices/`, which are authoritative where this document and the catalog differ.

Each family is described under the same headings: transport, identification, framing, messages, operations, hazards, reliability.

## Nord family

### Transport

Raw USB bulk transfers on a vendor-specific interface (interface 0), not USB-MIDI. Bulk OUT endpoint `0x03`, bulk IN endpoint `0x82`; interrupt endpoint `0x81` is present and unused. The instruments also expose a class-compliant USB-MIDI interface, which the app ignores.

### Identification

USB vendor id `0x0FFC` (Clavia DMI AB). Every supported Nord model shares this vendor id and the wire protocol below; product id, bank/group/slot geometry, name length and tested firmware versions are the only per-model differences and are declared in `devices/nord_devices.json`. The framing and message tables were decoded against a Nord Stage 2 EX (product id `0x0021`) and a Nord Grand (`0x002B`) and have since been exercised on the other six models in the catalog.

The firmware version is read with one vendor control request: `bmRequestType 0xC0`, `bRequest 4`, `wLength 2`, answered by a little-endian 16-bit code (`168` displays as `1.68`).

### Framing

Every message is a 16-byte header, a payload and a 2-byte CRC-16 trailer, all big-endian:

| Bytes | Field | Contents |
|---|---|---|
| 0–3 | Total length | Length of the whole message, header through CRC. |
| 4–7 | Protocol id | 6 = UI, 7 = Ctrl, 12 = file transfer (content database). |
| 8–11 | Protocol version | The version that protocol was negotiated at (see Handshake). |
| 12–15 | Sub-opcode | The request or reply within the protocol. |
| 16..N−2 | Payload | Sub-opcode-specific data. |
| N−2..N | CRC-16 | See below. |

CRC: CRC-16/CCITT-FALSE (polynomial `0x1021`, initial value `0xFFFF`, no reflection, no final XOR) over every byte before the CRC field, appended big-endian.

A reply is correlated to its request by arriving next and by its header: every reply carries its request's protocol id and the request's sub-opcode plus one (2/3, 30/31, 47/48 and so on, in every table below). The protocol carries no sequence number and exactly one request is outstanding at a time, so the next message read is normally the answer to the last message sent - but a session that dies mid-reply (the app killed while a read was in flight) leaves the rest of that reply queued on the instrument, where the next session's first read receives it. The app therefore drains the IN endpoint once at connect, before the device-info query, and checks every reply's protocol id and sub-opcode against the request; a reply answering some other request is discarded and the next one read, up to four times, after which the session is refused as out of step. Anything still buffered in the app when a request goes out is stale by construction and is discarded. A reply may span more than one USB read and is reassembled using its total-length field; a declared length outside 18..65,536 bytes is treated as a framing fault and the buffer is dropped.

### Handshake

On connect the app sends one message: `DEVICE_INFO_QUERY` (protocol 7, version 0, sub-opcode 2/3), with an empty payload. The reply is a 1-byte count followed by that many `(protocol id, version)` byte pairs. A Nord Grand answers `05 | 06 01 | 07 00 | 0a 02 | 0c 0a | 0d 00` (protocol 6 at version 1, 7 at 0, 10 at 2, 12 at 10, 13 at 0); a Nord Stage 2 EX answers the same except protocol 12 at version 8. The protocol-12 version is used in the header of every content-database request. A version outside 3..10 is refused, because every reply layout the app knows was decoded from that range. Protocols 10 and 13 are advertised and never used. The UI protocol (6) is expected at version 1 and the Ctrl protocol (7) at version 0; a different value is logged, and a different UI version additionally stops the app from sending anything on protocol 6.

The app also reads `CAPABILITY_QUERY` (protocol 6, sub-opcode 4/5, empty payload) for the device report only. Its reply is not decoded; the raw bytes are included in the report.

### Content database (protocol 12)

Presets are addressed as `(bank, item)` pairs within named root categories. All multi-byte fields below are 4-byte big-endian words unless noted.

| Sub-opcode | Name | Request | Reply |
|---|---|---|---|
| 0/1 | Root category list | *(empty)* | Count byte at offset 4, then per category: name length, ASCII name, and a trailer whose first word is the category's storage allocation unit in bytes. |
| 2/3 | Get category child | `categoryIndex` | `status`, echoed `categoryIndex`, a 1-byte child count, then per child: name length, ASCII name, 4-byte capacity (slots per bank). |
| 4/5 | Select category | `categoryIndex` | Locks the instrument's panel to that category; reply not decoded. |
| 6/7 | Unlock category selection | *(empty)* | Releases the lock from 4/5; reply not decoded. |
| 8/9 | Get item count | `categoryIndex` | `status`, `itemCount`, `free`, `used`, `reclaimable`, `unitCode` (six words). |
| 20/21 | Delete item | `bank`, `item` | `status`, echoed `bank`, echoed `item`. |
| 22/23 | Copy program | `srcBank`, `srcItem`, `dstBank`, `dstItem` | `status` plus the echoed request. The destination must be empty; status 4 ("file exists") is the occupied-destination refusal. The instrument names the copy itself (appending a number, "Synth Strings" → "Synth Strings 2"), so the app reads the destination's item record afterwards to learn the name. |
| 24/25 | Move program | `srcBank`, `srcItem`, `dstBank`, `dstItem` | `status` plus the echoed request. The destination must be empty. |
| 26/27 | Swap programs | `srcBank`, `srcItem`, `dstBank`, `dstItem` | `status` only. Both positions must be occupied; an empty one answers status 1. |
| 28/29 | Set name | `bank`, `item`, name length, ASCII name | `status`, echoed `bank`, echoed `item`. An oversized name is accepted with status 0 and silently truncated to the instrument's stored maximum (16 characters on every model measured). |
| 30/31 | Fetch item | `bank`, `item` | The item's metadata record (below), not its program data. |
| 32/33 | Cursor next item | `bank`, previous item index (`-1` to start), direction `0` | A "bank exhausted" flag at offset 0 and the next item index at offset 8; walks a category one item at a time. |
| 47/48 | Select preset | `bank`, `item` | `status` plus echoed `bank`/`item`. Loads the preset. Sent inside the category lock it also refreshes the instrument's display after a write; see "Operations". |
| 51/52 | Set category | `bank`, `item`, `categoryId` | `status` plus echoed `bank`/`item`. Writes the category tag alone and leaves the name untouched. Every id 0..31 is accepted and stored; one the instrument cannot name displays as `No Cat`. |

Sub-opcodes 40/41 (dependency list), 45/46 (enable invalidation), 57/58 (reset) and 61/62 (content version) are named in the code and never sent.

Item record layout (sub-opcode 30/31 reply):

| Bytes | Field |
|---|---|
| 0–3 | Status |
| 4–7 | Bank |
| 8–11 | Item |
| 12–15 | Data size: the exact byte length of the item's data blob |
| 16–19 | Content-type tag (ASCII, NUL-padded: `ngp\0`, `npno`, `nsmp`, ...) |
| 20–27 | Not decoded |
| 28–31 | Tag-specific. On an `ngp` program record this is the category id written by 51/52 and shown per row. On an `nsmp` sample record it is two 2-byte halves, a (category, sub-category) pair. Decoded only when the tag is `ngp`. |
| 32–35 | Name length |
| 36..+len | Name (ASCII) |
| +8 bytes | Not decoded |
| +4 bytes | Content id (CRC-32 of the item's data), present only at file-transfer protocol version 10 and later |

The per-category trailer length in the root category list depends on the negotiated protocol version, not on the model: 26 bytes below version 5, 28 from version 5, 29 from version 10 (a Nord Grand at version 10 measures 29, a Nord Stage 2 EX at version 8 measures 28). The app derives the length from the version and checks that it walks the reply exactly; for a version outside the known range, or where the version's length does not fit, it searches for the single trailer length in 0..128 that walks the reply and lands on its end.

Storage accounting: the cursor (32/33) is the authority on how many items a category holds. `GET_ITEM_COUNT`'s item count can read 0 for a category that is not empty (a Nord Stage 2 EX's `Live` and `Settings` categories), so the walk follows the cursor and uses a non-zero count only as an upper bound. The storage figures (`free`, `used`, `reclaimable`, `unitCode`) are reliable for the program categories; a `unitCode` of 0 means the area is counted in bytes. Each area's allocation unit is stated by the instrument in the root category list; the device report additionally fits an independent unit from the area's item sizes (the `U` for which `Σ ceil(size / U)` matches `used`) as a read-only cross-check. No read or write depends on that fit.

### Operations

Every preset operation runs inside a category lock: `SELECT_CATEGORY` for the `Program` category, the operation, then `UNLOCK_CATEGORY_SELECTION`. The unlock always runs, on failure too, because a lock left standing makes the instrument's panel unresponsive until the session ends. The `Program` category's index is looked up by name in the root category list before the lock is taken.

Listing walks the `Program` category with the cursor and fetches each item's record. The bank count for the walk is the category's own child count (2/3), not the program bank letters: a Nord Stage 2 EX addresses programs in four banks while giving `Piano` six children. A category that reports itself exhausted having yielded nothing ends the walk unless a non-zero item count says more is to come.

After a rename, move, swap or category change the app re-selects the affected slot(s) with 47/48 while still inside the lock. On a Nord Stage 2 EX none of those writes refreshes the panel on its own, and a 47/48 sent outside the lock does nothing there; on a Nord Grand a 47/48 outside the lock changes the active preset. Every reply carrying `(status, bank, item)` is checked on all three: a non-zero status is a refusal, and an echo naming a different slot means the instrument acted on the wrong one.

A delete frees no space until the instrument's own reclaim runs: the units move from `used` to `reclaimable`.

### Reliability

Every reply is CRC-checked before it is parsed, and every fixed-offset field read is bounds-checked, so a short or malformed reply is reported as a protocol error rather than an exception. A run of 64 consecutive zero-length reads is treated as a dead endpoint. A reply whose header answers a different request than the one just sent is discarded rather than parsed (see "Framing" above): observed on a Nord Grand after the app was force-stopped mid-read, whose next session read the tail of the interrupted reply as its device-info answer. A bulk endpoint that answers every transfer with an error after such a kill is not recoverable from the app; the connect failure says to unplug and replug the cable.

## Behringer Pro-800

### Transport

Class-compliant USB-MIDI through Android's `MidiManager`; no USB host permission is involved.

### Identification

The instrument enumerates with USB vendor id `0x1397`, product id `0x125F`, but the app does not match on them: every MIDI port Android's MIDI service publishes is probed, and a port is identified by sending the device-name request (type `0x06`) and checking that the reply starts with the Pro-800 header and type `0x07`. Port names are never used. The catalog format allows a `usbHint` that would restrict the probe to ports backed by a given USB device; the Pro-800 entry sets none, so a Pro-800 reached through a separate USB-MIDI interface is found as well.

### Framing

SysEx messages of the form `F0 00 20 32 00 01 24 00 <type> [params] F7`: SysEx start, the 3-byte Behringer manufacturer id, the 3-byte Pro-800 product id, the device number, then the type and any parameters. The device number is `00` by default and ignored in a request, but it is stamped into every reply and can be changed with message type `0x00`, which the app never sends. The app's reply matcher requires `00` there, so an instrument renumbered by another host is not recognised until it is set back.

Data bytes above `0x7F` are carried with an overflow byte at every position that is a multiple of 8 (counting from the start of the payload); bit *n* of that byte is the stripped high bit of the byte at offset *n*+1. The app decodes a record once into a dense 8-bit array and expresses field offsets in dense coordinates (`dense = raw − raw/8 − 1` for a raw offset that is not a multiple of 8).

### Messages

| Type | Name | Parameters | Reply |
|---|---|---|---|
| `0x01` | Status | *(reply only)* | Two bytes; the second (offset 10 of the message) is the code: `0` success, `1` failure. A write answers `01 00 00`; a read of an out-of-range address answers `01 00 01`. |
| `0x02`, `0x04` | Unknown | *(none)* | Answered with fixed payloads of types `0x03` and `0x05` that are not decoded. Sent only by the device report, which records the raw replies. |
| `0x06` / `0x07` | Device name request / reply | *(none)* | ASCII `"PRO-800"`. |
| `0x08` / `0x09` | Firmware request / reply | `0x00` | Three version bytes (major, minor, patch) at offset 10; offset 9 echoes the request's parameter. |
| `0x32` | Reset mode | `0x00` | Recalls the preset the settings block points at and discards unsaved panel edits. Answers a status. Only parameter `0x00` is safe; see "Hazards". |
| `0x77` | Request dump | Program number as LSB, MSB (7 bits each) | Answered by type `0x78`; by a bare `F0 F7` if the slot is empty; or by a `0x01` failure status if the address is out of range. |
| `0x78` | Dump | Program number (LSB, MSB) plus the encoded record | Also the write command: sending it with a record writes that slot. An empty payload sets the slot to uninitialized. The instrument answers a write with a status, which the app does not wait for; it verifies by reading the slot back. |

### Addressing and records

Presets occupy addresses 0..399, displayed as `A00`..`D99`. Address 510 holds a settings block, read and written with the same `0x77`/`0x78` mechanism, which is never listed as a preset.

A record's length varies with the format version stored at raw offset 5: formats 110 and 111 append fields after the name, and the instrument truncates trailing padding, so records of 155 to 173 dense bytes are all valid. The name is 16 characters at raw offsets 172..189 (dense 150..165), NUL-padded; a preset may carry no name at all. A bare `F0 F7` reply is the only signal that a slot is empty; length says nothing about it.

### Operations

Listing is 400 sequential dump requests, streamed in batches of 25.

Selection writes the instrument's own selection pointer; no MIDI channel message is involved. The pointer is two fields of the settings block: `Current Preset Number` (raw offset 6, two bytes) holding the flat program number 0..399, and `Current Bank` (raw offset 23, one byte) holding 0..3. The firmware takes the slot digits from the first modulo 100 and the bank letter from the second, and does not cross-validate them: a mismatched pair is accepted and survives a power cycle. The sequence is:

1. Read the settings block (`0x77` at 510).
2. Patch both fields in one `0x78` at 510, changing only those value bytes and the overflow bits that carry their high bits. The block is 46 raw bytes and ends mid-group, so its last overflow byte governs five value bytes; a decode/re-encode round trip would zero the two unowned bits, whose meaning is unknown, so the block is patched in place.
3. Poll the read-back every 200 ms for up to 2.5 s until both fields match. Most writes are visible on the next read; some take over a second.
4. Send `0x32 0x00`. The settings write moves the pointer, and the display and every readable field follow it, while the voice engine keeps playing the previous preset until this recall.

Ordinary channel messages (Bank Select, Program Change) are not used because nothing acknowledges them and the receive channel is not always knowable: the instrument can take it from its rear DIP switches, can be set to ignore MIDI entirely, and has a separate `MIDI PC Mode` that disables Program Change reception. The SysEx sequence works in every one of those configurations, including `MIDI RX Channel = OFF`, and was confirmed audibly on hardware: the instrument's sound changes, not only its display. Nothing readable reports the audible recall, so a regression in step 4 is invisible to every check the app can make.

Rename, move, swap, delete and copy are composed from `0x77` reads and `0x78` writes; the instrument has none of them as commands. Every write is followed by a read-back that is compared byte for byte over the common prefix (the instrument truncates trailing padding, so the stored record may be shorter than what was sent). A rename preserves the record's format version rather than upgrading it, and grows the record to the end of the name field where the stored record ended earlier; the name field ends one byte before format 110's first appended field. A move writes the destination before erasing the source; a swap rolls the first write back if the second fails; the previous contents of every overwritten address are kept in memory for the session.

### Hazards

Five message types are named in the code and never sent. Their effects were measured on hardware:

| Type | Effect |
|---|---|
| `0x7D` | Factory reset. No confirmation, no undo. |
| `0x03` with parameter `0x30` | Reboots into the bootloader: the display reads `boot`, the panel is unresponsive and USB re-enumerates; only a power cycle returns it, with presets and firmware intact. Every other parameter from `0x00` to `0x3F`, plus `0x40`, `0x60` and `0x7F`, answers a plain OK status and does nothing. |
| `0x32` with a non-zero parameter | Detaches the panel's input handling: the display runs a lamp test (`8888`), then shows its content with a decimal point after every character, and the knobs and value wheel stop working. `0x32 0x00` restores it without a power cycle. |
| `0x0E` | The parameter is a flag: `0x00` sets RX and TX to DIP-switch mode; anything else writes `MIDI RX Channel` = 249, out of range, which makes the instrument ignore all channel-voice MIDI. Recovered by an ordinary settings write. |
| `0x50` | Writes preset name bytes directly, without the record-length bookkeeping `0x77` reports from. Sent with no payload it blanks the name of every occupied preset in the library, persistently, recoverable only by a factory reset. Sent correctly it stores at most 14 characters and reads back inconsistently with the display. The app renames through a `0x77`/`0x78` round trip, as Behringer's own editor does. |

### Reliability

A reply carries no request id, so a dump is matched to its request by its echoed address. On a shared MIDI port (another application reading the same instrument) about 1% of replies arrive spliced: one loses its `F7` and runs into the next, producing a message with the right header, type and address and another record's tail. The app rejects a record longer than its own format version permits (every splice measured ran to 228..231 bytes against a maximum of 210), which leaves the exchange listening for a valid reply and retrying. A record shorter than the name field (possible for a genuinely unnamed preset, which ends at its last non-zero parameter) is read a second time and accepted only if both reads agree, since a splice does not reproduce and truncation does.

The Pro-800's SysEx messages and record formats are documented in more depth in <https://github.com/steinachim/pro800_manager_plugin>.

## Yamaha Motif XS

### Transport

The instrument exposes one vendor-specific USB interface and no class-compliant USB-MIDI interface, so `MidiManager` does not see it. The app opens the raw bulk endpoints (OUT `0x01`, IN `0x82`) and packs standard 4-byte USB-MIDI event packets itself, on cable 3. Cables 0 and 3 both carry the full protocol; cables 4..7 accept a request and answer on 3; the rest are silent. Cable 3 is what the vendor's editor uses. The instrument sends Active Sensing on cable 0, which the app filters out by cable.

The instrument paces its output at about 12,468 bytes per second in full 64-byte transfers: a normal voice dump (~1.9 kB) takes ~160 ms, a drum kit (~12.6 kB) about a second. Reads use one 64-byte transfer per call, so the last read of a dump does not wait out a timeout.

### Identification

USB vendor id `0x0499` (Yamaha), product id `0x1042` for the XS6. **Assumption:** `0x1043` and `0x1044` are catalogued as the XS7 and XS8. Yamaha's Windows driver package ships `yum1043.inf` and `yum1044.inf` alongside the XS6's `yum1042.inf`, and the two previous Motif generations assigned sequential product ids by keybed size, but no Yamaha text ties these ids to the model names and neither has been confirmed on a unit. All three share one catalog configuration, since Yamaha's combined MIDI implementation chart documents one protocol for the three.

On connect the app sends a Universal Device Inquiry, `F0 7E 00 06 01 F7` (device id `0x00`, which is what the vendor's editor uses; whether the `7F` broadcast id is answered is untested), and accepts any reply whose second byte is `7E` and fifth byte is `02` (`F0 7E nn 06 02 ... F7`). Only the four bytes before `F7` are read, as the firmware version; the manufacturer, family and model fields are not checked, and the session continues if no reply arrives. The reply distinguishes the three models (family member code `35 06` / `36 06` / `37 06`), but the product id already has.

### MIDI routing

The Motif XS routes MIDI to exactly one destination, chosen under Utility → [F5] Control → [SF2] MIDI → MIDI In/Out: the DIN sockets, USB, or mLAN. Set to anything but USB it still enumerates, still grants permission and still opens its endpoints, and then answers nothing. The app therefore refuses the session at connect time when two different probes go unanswered, the Universal Device Inquiry and Yamaha's own mode request at `0A 00 01`, and shows the button sequence above. One unanswered probe is not enough: the identity reply is allowed to be missing on an instrument that otherwise works. Silence has other causes (a cable, another application holding the device), so the message presents the routing setting as the thing to check rather than as a diagnosis.

### Framing

SysEx messages of the form `F0 43 <type|device> 7F <model> ... F7`: manufacturer id `0x43`, the message type in the high nibble of byte 2 and the device number (`0x00`) in the low nibble, then a fixed `0x7F` and a model byte, `0x03` in everything the host sends and `0x0B` in everything the instrument sends.

| Type | Name | Layout | Reply |
|---|---|---|---|
| `0x00` | Bulk dump | 14-bit byte count (7 bits per byte), 3-byte address, data, checksum | Acknowledged by type `0x60` (`F0 43 60 02 F7`) if accepted; silence if the address is read-only, the checksum is wrong, or the count disagrees with the message length. The count covers the data only; a whole message is `count + 12` bytes. |
| `0x10` | Parameter change | 3-byte address, 1 data byte | Sent by the instrument in answer to a parameter request and as the echo of a selection; sent by the host to switch mode (no reply). |
| `0x20` | Dump request | 3-byte address | A type-`0x00` bulk dump at that address. |
| `0x30` | Parameter request | 3-byte address | A type-`0x10` reply carrying the value. |
| `0x40` | Select | 3-byte address (`65 00 00`, `65 00 01` or `65 00 02`), 1 value byte | Undocumented; used by the vendor's editor. Echoed as type `0x10`. |
| `0x60` | Acknowledge | *(reply only)* | The `0x02` payload byte is constant and carries no information. |

Checksum: the two's complement of the sum of every byte from the count through the last data byte, masked to 7 bits.

A block sent back to the instrument is always rebuilt from its address and payload with model byte `0x03`, never echoed verbatim: the checksum covers the model byte, so a copied block is wrong twice.

### Addresses

Two address families coexist and share a framing: Yamaha's documented parameter and bulk addresses, and an undocumented extension the vendor's editor drives.

Documented:

- `0A 00 01`: the current mode, read with a parameter request and set with a parameter change. `0` Voice, `1` Performance, `2` Pattern, `3` Song, `4` Master.
- `40 00 nn` / `46 00 nn`: the Common block of the Normal or Drum voice in the edit buffer, one byte per parameter request. Offsets `0x00`..`0x13` are the 20-byte name, NUL-padded; `0x18`..`0x1B` are the four category bytes (`main1, sub1, main2, sub2`). Answered in Voice and Song mode, silent in Performance mode.
- `0E mm nn` (Bulk Header) and `0F mm nn` (Bulk Footer): a stored voice at bank byte `mm`, slot `nn`. A dump request at the header address answers with a sequence: header, a fixed run of parameter blocks (24 for a Normal Voice, 81 for a Drum Voice), footer. Sending such a sequence writes the voice, and the footer commits it to flash. The blocks are a flat 7-bit array with no MSB packing.

Undocumented extension:

- `0C mm nn`: one stored voice as a single bulk dump. `mm` is the bank byte from the catalog's bank table (`00`..`07` PRE1..PRE8, `09` GM, `0A`..`0C` USER 1..3, `20` PRE DR, `21` GM DR, `28` USER DR); `0x08` is unmapped, and USER DR sits detached at `0x28`. Writing a whole payload to a `0C` address is how delete, copy, move and swap are composed.
- `11 00 00`: the store marker, a zero-payload bulk dump. Commits everything written to `0C` addresses since the last commit; see "Operations".
- `71 mm 00`: the favorite marks of bank `mm`, one raw byte per slot.
- `65 00 00` / `65 00 01` / `65 00 02`: bank-select MSB, bank-select LSB and program of a voice selection, one type-`0x40` message each.

An unmapped middle byte in a dump request is not merely refused: it puts an *Illegal Bulk Data* message on the instrument's own screen, once per attempt. The app takes every bank byte from the catalog table and never sweeps a range.

### The `0C` payload

The payload's first two bytes are not decoded. From offset 2 it is MSB-packed: groups of 8 bytes, the first carrying the high bits of the seven that follow it (bit *j* for the *j*-th byte), unpacked into a dense stream.

The dense stream opens with an ASCII figure pair, two decimal numbers of 1..4 digits each, colon-separated and colon-terminated (`256:256:`, `227:146:`, `0:0:`). They are the voice's two category assignments, each `main × 16 + sub`; `256` (`16 × 16`) is `NoAsg`, no assignment. Because the figures are variable width, the name that follows does not start at a fixed offset, and a category can never be written by patching the figures in place. The name is the printable run after the pair, bounded at 64 bytes, and its true length is settled by a repeat of the name 6 bytes after its end: a greedy printable read overshoots by one when the first byte after the name happens to be printable, and the repeat sits one byte early in that case. A name that is not corroborated by its repeat reads as empty, which is how an empty slot presents; the wire has no emptiness flag. The rest of the payload is not decoded.

The categories decoded this way were checked against the shipped factory table on 205 user voices whose names match factory voices, with no mismatches.

### Operations

Listing reads the four user banks (416 voices, about 93 s) with one `0C` dump request each, matched on the echoed address and accepted only if well-formed (count and checksum), so a truncated dump is retried rather than accepted. The eleven factory banks (1,217 voices) are never read for a listing: reading them would take about seven and a half minutes for data that is identical on every Motif XS, so their names and categories ship with the app in `devices/motifxs_factory_voices.json`, transcribed from Yamaha's published voice lists (the two drum banks' categories were read off an instrument, since the drum voice list publishes none). The debug menu can read one factory bank and diff it against the shipped table.

Selection sends the three `65 00 0x` messages in the order the vendor's editor does and waits for the echo of the third only. It takes effect in Voice mode only; in Performance and Song mode the messages draw no echo and change nothing, so the app reads the mode first and refuses with an offer to switch. The switch is a documented parameter change to `0A 00 01`, sent only after the user confirms, since it audibly changes what the instrument plays; it draws no reply, so the app polls the mode every 250 ms for up to 3 s until Voice is read back. Bank-select pairs are per bank: every normal bank uses MSB `0x3F` except GM (`0x00`), and GM DR uses `0x7F`; PRE1, GM and GM DR all use LSB `0x00` and are told apart only by the MSB. An unrecognised pair is ignored rather than refused, so a wrong value loads a voice from whatever bank was current and reports success.

Rename and category change use the documented path: read the stored voice at `0E mm nn`, patch the Common block's name bytes (`0x00`..`0x13`) or category bytes (`0x18`..`0x1B`) on a copy, and send every block back between a freshly built header and footer with a 15 ms gap between blocks, matching the vendor editor's pacing. The footer commits to flash; no store marker is needed. Every block is checked for well-formedness before anything is sent, and exactly one Common block must be present, because once the header is on the wire the instrument is in a transaction: left mid-sequence it waits on "receiving midi bulk data" until the sequence is completed or it is power-cycled. The app therefore never stops a sequence part way. Editing the edit buffer's Common block with parameter changes does not reach the stored slot; only the panel's own Store does, and that is not a message a host can send. The name is NUL-padded (revision C0 of the Data List; revision B0 says space-padded and does not match the instrument). After a rename, if the instrument is in Voice mode and its edit buffer still shows the old name, the app re-selects the slot so the panel catches up.

Delete, copy, move and swap are composed from one primitive: write a whole voice payload as a bulk dump to a `0C` address, then commit with `11 00 00`. An acknowledged write is held, not applied; the commit writes every held dump to flash, takes about 160 ms against about 19 ms for a data dump, and is idempotent (the vendor's editor sends two). Each operation issues all of its writes before its single commit, which is what makes it atomic: a refused write is answered with silence and is not held, so if a later write in an operation is refused nothing has been committed. Every operation then reads the affected slots back and compares the bytes exactly.

- Delete: write a blank payload, commit, verify.
- Copy: read the source (refusing an empty source or an occupied destination, which the instrument does not), write it to the destination, commit, verify. The copy carries the source's name unchanged.
- Move: read the source, write it to the destination and the blank to the source, one commit, verify both.
- Swap: read both, write each to the other's address, one commit, verify both.

The blank payloads ship with the app (`devices/blanks/`): one initialized Normal Voice and one initialized Drum Voice, each read off an instrument with its name bytes cleared through the documented path. A drum kit and a normal voice are different objects (about 12.6 kB against 1.9 kB, with different block sequences), and the instrument acknowledges a payload of the wrong kind at either address without complaint, so the app refuses any copy or move between a drum bank and a normal bank itself.

Favorites are read with one dump request per catalogued bank at `71 mm 00`. The reply is one byte per slot, not MSB-packed, and as long as the bank (128 for a normal bank, 64 for PRE DR, 32 for USER DR, 1 for GM DR): `0` unmarked, `1` filed under both of the voice's category assignments, `2` under the first only, `3` under the second only. A fourth value is stored verbatim and lists the voice under neither, so the byte is neither a boolean nor a bitmask; the app writes only 0..3. A voice with no category assignment can still be a favorite: marking a category-less user voice from the instrument's own Category Search → FAVORITE writes `2`, and the voice then appears in the FAVORITE bank under no category (43 of the 128 voices in one user bank measured carry no category). Writing a mark means reading the bank's whole table, changing one byte and sending the whole table back with `writeFavorites`, so the declared length is always the instrument's own; an ill-formed write to this instrument has been observed to hang its MIDI handling until a power cycle. The write differs from the `0C` path in four ways: it needs no store marker and reaches non-volatile storage on its own (sending `11 00 00` anyway would commit unrelated held writes); it applies on a delay, so the app polls the table every 150 ms for up to 3 s rather than reading back once; the read-only-bank rule does not apply (PRE1's table accepts a write, because a favorite is the user's data about factory content); and out-of-range values are kept rather than clamped. A category, by contrast, lives inside the voice, so setting one in a factory bank would rewrite a factory voice and is refused.

Sub-categories: Yamaha documents the main category byte and refers to a category list for the sub byte that is in no released file. The shipped `categoryEncoding` in `devices/yamaha_motif_xs.json` was measured on hardware. Its order is not the order Yamaha's voice list prints (`Brass` prints `Orche, Solo, BrsEn` and indexes `Solo, BrsEn, Orche`), and "no sub-category" is one past the main's last sub (`subs.size`: 5 for fourteen mains, 4 for `Bass` and `Dr/Pc`). An unassigned slot is written as `(16, 0)`.

### Reliability

The instrument sends no unsolicited SysEx apart from Active Sensing on cable 0. A bulk dump acknowledgement means the message was well-formed and accepted, not that anything reached flash; only the commit or the footer does that. Dumps dropped or truncated on the bus are ordinary rather than exceptional (the transport buffers deep enough to ride out a garbage-collection pause of about 150 ms at the instrument's transfer rate), so every read is retried twice and matched on well-formedness.

Yamaha's documentation for the Motif XS: <https://usa.yamaha.com/products/contents/music_production/downloads/manuals/index.html?l=en&c=music_production&k=Motif+XS>
