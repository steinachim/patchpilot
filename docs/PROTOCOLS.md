# Device Protocols

This document describes the wire protocols PatchPilot uses to talk to each supported instrument, limited to the commands the app actually sends. It is a reference for maintainers, not a full specification of each vendor's protocol.

## Nord Stage 2EX / Nord Grand

**Transport**: Raw USB bulk transfers on a vendor-specific interface (not USB-MIDI). Bulk OUT endpoint `0x03`, bulk IN endpoint `0x82`; interrupt endpoint `0x81` is present but unused.

**Identification**: USB vendor ID `0x0FFC` (Clavia DMI AB). Product ID `0x0021` identifies a Nord Stage 2EX, `0x002B` a Nord Grand. Both instruments speak the identical wire protocol; all behavioral differences between them are expressed as data (bank/group/slot geometry, supported firmware versions) rather than separate code paths.

**Framing**: Every message is a 16-byte header, a payload, and a 2-byte CRC-16 trailer, all big-endian:

| Bytes | Field | Contents |
|---|---|---|
| 0–3 | Total length | Length of the whole message, header through CRC. |
| 4–7 | Protocol ID | Which of the three protocols the message belongs to (6 = UI, 7 = Ctrl, 12 = file transfer / content database). |
| 8–11 | Protocol version | The version of that protocol this exchange is negotiated at (see Handshake). |
| 12–15 | Sub-opcode | The specific request or reply within the protocol. |
| 16..N−2 | Payload | Sub-opcode-specific data. |
| N−2..N | CRC-16 | See below. |

A reply is correlated to its request by protocol ID and sub-opcode, not by a sequence number. A large reply is not guaranteed to arrive in a single USB read and is reassembled from multiple reads using the total-length field.

**CRC**: CRC-16/CCITT-FALSE — polynomial `0x1021`, initial value `0xFFFF`, no input/output reflection, no final XOR — computed over every byte of the message *before* the CRC field (header + payload) and appended big-endian.

**Handshake**: on connect, the app sends exactly one handshake message: `DEVICE_INFO_QUERY` (protocol 7, sub-opcode 2/3), an empty-payload request. The reply is a 1-byte count followed by that many `(protocol ID, version)` byte pairs — e.g. a Nord Grand answers `05 | 06 01 | 07 00 | 0a 02 | 0c 0a | 0d 00` (5 entries: protocol 6 at version 1, protocol 7 at version 0, protocol 10 at version 2, protocol 12 at version 10, protocol 13 at version 0), where a Nord Stage 2EX answers the same except protocol 12 at version 8. The protocol-12 version is what the app uses for everything below; a version outside the 3–10 range it was written against is refused. Protocols 10 and 13 are advertised but never used by the app.

The app also reads `CAPABILITY_QUERY` (protocol 6, sub-opcode 4/5), also an empty-payload request. Its reply is a protocol/capability version block the app does not decode — the raw bytes are only ever shown in the diagnostic device report, never parsed into a field the app uses.

**Content database (protocol 12)** — the app's core operation set, addressing presets as `(bank, item)` pairs within named root categories. All multi-byte fields below are 4-byte big-endian words unless noted.

| Sub-opcode | Name | Request | Response |
|---|---|---|---|
| 0/1 | Root category list | *(empty)* | Count byte at offset 4, then per category: name length + ASCII name + a version-length trailer whose first word is the category's storage allocation unit in bytes. |
| 2/3 | Get category child | `categoryIndex` | `status`, echoed `categoryIndex`, a 1-byte child count, then per child: name length + ASCII name + a 4-byte capacity (slots per bank). |
| 4/5 | Select category | `categoryIndex` | Locks the instrument's UI to that category; reply not decoded. |
| 6/7 | Unlock category selection | *(empty)* | Releases the lock from 4/5; reply not decoded. |
| 8/9 | Get item count | `categoryIndex` | `status`, `itemCount`, `free`, `used`, `reclaimable`, `unitCode` (six 4-byte words). |
| 20/21 | Delete item | `bank`, `item` | `status`, echoed `bank`, echoed `item`. |
| 22/23 | Copy program | `srcBank`, `srcItem`, `dstBank`, `dstItem` | `status` + echoed request. Destination must be empty (occupied → status 4, "file exists"); the instrument names the copy itself, so the app re-reads the destination's item record afterward to learn the name it chose. |
| 24/25 | Move program | `srcBank`, `srcItem`, `dstBank`, `dstItem` | `status` + echoed request. Destination must be empty. |
| 26/27 | Swap programs | `srcBank`, `srcItem`, `dstBank`, `dstItem` | `status` only (no echo). Both positions must already be occupied. |
| 28/29 | Set name | `bank`, `item`, name length, ASCII name | `status`, echoed `bank`, echoed `item`. An oversized name is accepted and silently truncated to the instrument's stored maximum rather than rejected. |
| 30/31 | Fetch item | `bank`, `item` | The item's metadata record (see layout below) — not its program data. |
| 32/33 | Cursor next item | `bank`, previous item index (`-1` to start), direction (`0`) | A "bank exhausted" flag at offset 0 and the next item index at offset 8; used to walk a category one item at a time without a directory query. |

**Item record layout** (sub-opcode 30/31 reply):

| Bytes | Field |
|---|---|
| 0–3 | Status |
| 4–7 | Bank |
| 8–11 | Item |
| 12–15 | Data size — the exact byte length of the item's underlying blob |
| 16–19 | Content-type tag (ASCII, e.g. `ngp `, `npno`, `nsmp`) |
| 20–31 | Unparsed |
| 32–35 | Name length |
| 36..+len | Name (ASCII) |
| +8 bytes | Unparsed trailer |
| +4 bytes | Content ID (CRC-32 of the item's data) — present only at file-transfer protocol version ≥ 10 |

**Storage accounting**: the cursor (sub-opcode 32/33) is the authority on how many items a category holds — `GET_ITEM_COUNT`'s item count is not, and can read `0` for a category that is not actually empty (observed on a Nord Stage 2EX), so browsing walks the cursor rather than trusting this figure. What `GET_ITEM_COUNT` reliably gives the app is the storage figures (`free`, `used`, `reclaimable`, `unitCode`). Separately, the app can *independently re-derive* each storage area's allocation unit from the area's own item sizes (fitting the unit `U` for which `Σ ceil(size / U)` matches the reported `used` figure) and compares it against the unit the instrument states directly in the root category list. This is a read-only cross-check surfaced only in the diagnostic device report, not a dependency of any read or write operation — it exists because a previous hardcoded assumption about a category's unit turned out to be wrong, and deriving it from the instrument directly removes the need to trust or maintain such a constant at all.

**Item record layout derivation**: the per-category trailer length (26/28/29 bytes, protocol version-dependent) depends on the protocol version negotiated during the handshake, not on which Nord model is connected — a Nord Grand and Nord Stage 2EX report different versions (10 vs. 8) and therefore parse to different layouts under otherwise identical code. Deriving both from the negotiated version, with a self-describing fallback (walking the response to find the length that fits) for a version outside the known 3–10 range, is what lets a single implementation serve every Nord model without per-device branching — including tolerating an unknown firmware revision.

## Behringer Pro-800

**Transport**: Class-compliant USB-MIDI, reached through Android's `MidiManager` — no USB host permission required.

**Identification**: USB vendor ID `0x1397`, product ID `0x125F`. The app identifies the specific port to use by sending a device-name SysEx request and checking the reply, rather than relying on MIDI port naming.

**Framing**: SysEx messages of the form `F0 00 20 32 00 01 24 00 <type> [params] F7` — SysEx start, the 3-byte Behringer manufacturer ID, the 3-byte Pro-800 product ID, a CPU ID byte, then the type and any parameters.

| Type | Name | Parameters | Response |
|---|---|---|---|
| 0x01 | Status | — (reply only) | Two bytes; the second (offset 10 of the whole message) is the code — `0` success, `1` failure. A write answers `01 00 00`; a read of an out-of-range address answers `01 00 01`. |
| 0x06 / 0x07 | Device name request / reply | *(none)* | ASCII `"PRO-800"`. |
| 0x08 / 0x09 | Firmware request / reply | `0x00` | Three version bytes (major, minor, patch) at offset 10 of the reply; offset 9 echoes the request's `0x00` parameter and is not part of the version. |
| 0x77 | Request dump | Program number as LSB, MSB (7 bits each) | Answered by type `0x78`; a bare `F0 F7` if the slot is empty; or a `0x01` failure status if the address is out of range. |
| 0x78 | Dump | Program number (LSB, MSB) + encoded program data | Also the write command: sending it with the program's encoded payload writes that slot. An empty payload resets the slot to uninitialized. |
| 0x32 | Reset mode | `0x00` | Recalls the preset the settings block points at, and discards unsaved front-panel edits. Answers a status. **Only parameter `0x00` is safe** — see *Never sent* below. |

**Addressing and record format**: presets are addressed by a single 14-bit index (0–399); address 510 holds a non-preset settings block, which carries both the instrument's MIDI receive channel and its selection pointer (see *Selection* below). A record's length varies with the dump-format version encoded in the record itself (formats 109/110/111 append progressively more fields after the name), so a reader must check the declared version before assuming a fixed record size. A reply can arrive as a splice of two adjacent records under bus load (one loses its terminator and runs into the next); the app rejects any record longer than its own format version permits, and re-reads and compares a suspiciously short record before accepting it, since the protocol has no checksum or length field to catch a splice directly.

**Selection**: presets are selected by writing the instrument's own selection pointer over SysEx. **No MIDI channel is involved at any point.** The pointer lives in the settings block at address 510, in two fields: `Current Preset Number` (raw offset 6, two bytes) and `Current Bank` (raw offset 23, one byte). `Current Preset Number` holds the *flat* program number, not the slot within its bank — the firmware takes the slot digits from it modulo 100 and the bank letter from `Current Bank` — so selecting D05 writes 305 and 3, not 5 and 3. The two fields do not cross-validate: `Current Bank` alone selects the bank, `Current Preset Number` alone moves just the slot digits, and a mismatched pair between them is silently accepted and survives a power cycle rather than being rejected or normalized. Both are therefore written in a single `0x78`, so the pair is never inconsistent even transiently.

Selecting a preset is four steps, and the order of the last two is the substance of it:

1. Read the settings block (`0x77` at 510).
2. Patch the two fields and write the block back (`0x78` at 510).
3. **Poll the read-back until both fields match.** A settings write is not guaranteed to be visible on the next read — most commit immediately, but some take over a second — so checking once and proceeding means acting on a pointer that has not landed. The app polls at 200 ms for up to 2.5 s and fails if it never agrees.
4. **Then, and only then, recall**: `0x32` with parameter `0x00`. The settings write moves the *pointer* but does not load the preset — the display and every "current preset" field follow it while the voice engine keeps playing whatever was loaded before. A recall sent before the pointer commits would load the preset that is on its way out, which is why it follows the confirmed read-back rather than the write.

This replaced an earlier approach that used ordinary MIDI channel messages — Control Change 0 (Bank Select MSB) with the bank number, then a Program Change with the slot number. That path could not be made reliable. Nothing acknowledges either message, so sending on the wrong channel is indistinguishable from success: the app reported a preset loaded while the instrument sat unchanged. Worse, the right channel is not always knowable — the instrument can be configured to take its receive channel from the DIP switches on its back panel, in which case it reports only "the switches decide", and it can be set to ignore MIDI entirely, in which case no channel works at all. A separate `MIDI PC Mode` setting can also disable Program Change reception independently. The SysEx path works in every one of those configurations, and unlike the old one it can be verified.

What still cannot be verified is the *audible* recall. The instrument transmits nothing when it loads a preset, and every surface a client can read — the display, the settings block, the preset list — reports the pointer. So a client can confirm that the instrument was asked to load a preset and that the pointer moved, but only a listener can confirm the sound followed.

**Never sent**: five message types are named as constants in the code but never sent by it. They are named rather than omitted on purpose — an unnamed hazard gets rediscovered by whoever probes next, and gets rediscovered by *sending* it. That is not hypothetical: a blind sweep of `0x50` blanked the name of every occupied preset in a library, and the Nord side of this app lost 202 programs to an equally unlabelled sub-opcode.

| Type | Effect |
|---|---|
| `0x7D` | **Factory reset. No confirmation, no undo.** |
| `0x03` with a parameter of **exactly** `0x30` | **Reboots into the bootloader** — display reads `boot`, panel unresponsive, USB re-enumerates, only a power cycle returns it, with presets and firmware intact. Not a crash and not a range: every other parameter of this type answers a plain OK status and does nothing, which is what makes the type look inert until one specific value. |
| `0x32` with a **non-zero** parameter | Puts the synth into a state where it displays `8888` and stops responding properly to its own controls. Note the parameter: `0x32 0x00` is the ordinary preset recall used for selection above, and is safe. |
| `0x0E` | Its parameter is a flag, not a value: `0x00` sets RX and TX to DIP-switch mode, anything else writes `MIDI RX Channel` = 249, which is out of range and **makes the instrument deaf to all channel-voice MIDI** — notes, Program Changes, CC. Milder than the rest: nothing is lost and the recovery is an ordinary settings write, which needs no channel. Named because the failure is completely silent and a sweep will produce it. |
| `0x50` | Writes preset name bytes directly. A blind sweep of it with no payload blanked the name of **every occupied preset in a library**, persisting through a power cycle and recovered only by a factory reset. Unusable even when sent correctly: names read back one character short of what the display shows (or vice versa, depending on the trailing NUL), and it caps at 14 characters rather than the name field's 16, leaving a 15th character that survives a power cycle and appears in no dump. It writes name bytes without the record-length bookkeeping `0x77` reports from, so the two stay permanently out of step. The app renames through a `0x77`/`0x78` round trip instead; Behringer's own editor does the same. |

**Patching the settings block**: the write in step 2 changes only the value bytes of the two pointer fields and the individual overflow bits that carry their high bits, rather than decoding the block, editing it, and re-encoding it. The settings block is 46 raw bytes, which ends mid-group: its final overflow byte governs five value bytes rather than seven, so two of its bits belong to no value at all. Decoding never reads those bits and re-encoding would rebuild the byte from the decoded values alone and zero them. Whether real hardware puts anything there is unconfirmed, and this is the block holding every global setting, so unaccounted-for bits are left exactly as they were read.

For more in-depth details of the Pro-800's SysEx messages and preset formats, please check my other project at: https://github.com/steinachim/pro800_manager_plugin

## Yamaha Motif XS

**MIDI routing — the instrument must be set to USB**: the Motif XS routes MIDI to exactly one destination, chosen under Utility → [F5] Control → [SF2] MIDI → MIDI In/Out: the DIN sockets, USB, or mLAN. Set to anything but USB it still enumerates as a USB device, still grants permission and still opens its bulk endpoints — and then ignores every byte sent to it, answering nothing. Nothing about the connection looks wrong, so the app probes for this at connect time and refuses the session with the button sequence to fix it, rather than building a session in which every operation times out separately with no way to say why. The check requires **two** different probes to go unanswered — the Universal Device Inquiry and Yamaha's own mode request at `0A 00 01` — since the identity reply is cosmetic and is allowed to be missing on an instrument that otherwise works. Silence has other causes (a cable, another application holding the device), so the app presents this as the thing to check rather than as a diagnosis.

**Transport**: The Motif XS exposes a single vendor-specific USB interface, not a class-compliant USB-MIDI interface, so `MidiManager` cannot see it. The app opens the raw USB bulk endpoints directly (bulk OUT `0x01`, bulk IN `0x82`) and packs standard 4-byte USB-MIDI event packets itself, framing SysEx messages out of that byte stream.

**Identification**: USB vendor ID `0x0499`, product ID `0x1042` for the XS6, `0x1043` for the XS7, `0x1044` for the XS8 — three catalog entries sharing one family, since Yamaha's combined "MOTIF XS6/7/8 MIDI Implementation Chart" documents an identical SysEx protocol for all three keybed sizes. Only the XS6's product ID has been read off real hardware; `0x1043`/`0x1044` are inferred — confirmed as real, distinct Yamaha USB-MIDI product IDs (Yamaha's own Windows driver ships a `yum1043.inf`/`yum1044.inf` pair structurally identical to the XS6's `yum1042.inf`) but not tied to the XS7/XS8 model names by any text Yamaha ships, only by adjacency to the confirmed XS6 and the precedent of the two prior Motif generations assigning strictly sequential product IDs by keybed size. On connect the app sends a Universal Device Inquiry, `F0 7E 00 06 01 F7` (device ID `0x00`, sub-ID1 `0x06` = Inquiry Request, sub-ID2 `0x01` = request), and matches any reply shaped `F0 7E ... 02 ... F7` (sub-ID2 `0x02` = Identity Reply). The standard reply layout carries the manufacturer, family, and model codes plus a 4-byte firmware version; the app reads only the last four bytes before `F7` as the firmware version string and does not otherwise validate the manufacturer/family/model fields — the inquiry is used solely so the connect screen can show a real firmware version, and the app still functions if it goes unanswered. The Identity Reply *does* distinguish the three models in its own right (family member code `35 06`/`36 06`/`37 06` for XS6/XS7/XS8), but the app has no need to read it, since USB product ID already tells the three apart before the inquiry is even sent.

**Framing**: SysEx messages of the form `F0 43 <type|device> 7F <model> ... F7` — manufacturer ID `0x43` (Yamaha) fixed, the message type in the high nibble of byte 2 and the device number (`0x00`) in the low nibble, then a fixed `0x7F` and a model byte distinguishing host-originated (`0x03`) from device-originated (`0x0B`) messages.

| Type | Name | Parameters | Response |
|---|---|---|---|
| 0x00 | Bulk dump | 14-bit byte count (split 7 bits per byte) + 3-byte address + packed payload + checksum | Acknowledged by type `0x60` (`F0 43 60 02 F7`) if accepted; silence if the address is read-only, the checksum is wrong, or the declared count disagrees with the message length. |
| 0x10 | Parameter change | 3-byte address + 1 data byte | Echoed back as the same message type; used both to read replies (mode, name bytes) and to write (mode switch, voice-select confirmation). |
| 0x20 | Dump request | 3-byte address | Answered by a type-`0x00` bulk dump at that address. |
| 0x30 | Parameter request | 3-byte address | Answered by a type-`0x10` parameter-change reply carrying the current value. |
| 0x40 | Select | 3-byte address (fixed `65 00 <00\|01\|02>`) + 1 value byte | An undocumented extension the vendor's own editor uses; echoed as type `0x10`. |
| 0x60 | Acknowledge | — (reply only) | Confirms a bulk dump was accepted; carries no other information — the `0x02` payload byte is constant and means nothing on its own. |

**Addressing**: A voice's Common block — name, category, and other header fields — is addressed at `40 00 00` for a Normal Voice or `46 00 00` for a Drum Voice; the first 20 bytes of either block are the voice name, fixed-width and NUL-padded. Reading or writing a *stored* voice (as opposed to the currently-edited one) uses Yamaha's own Bulk Header (`0x0E`)/Bulk Footer (`0x0F`) addressing: a voice transfers as header → a fixed sequence of parameter blocks → footer, where the footer is what commits the write to Flash ROM.

**Payload encoding**: this applies to the undocumented `0x0C` extension's payload (see Delete/copy/move/swap below), which carries genuine 8-bit values and is therefore packed 7 bits per byte, MSB-first, in groups of 8 packed bytes producing 7 unpacked bytes. The first byte of each group of 8 carries no data of its own — bit *j* of that byte is the high (8th) bit of the *j*-th following byte, whose low 7 bits are stored directly. Unpacking walks the stream 8 bytes at a time, reassembling each full byte as `(low7 | (highBit << 7))`. This is the same scheme used elsewhere in MIDI SysEx to keep all data bytes within the 0–127 range SysEx requires. The documented Bulk Header/Footer blocks (Common/Element, used for rename) are a flat 7-bit array with no MSB packing at all — every value already fits in 7 bits, so there is nothing to unpack.

**Voice names**: a voice name occupies a fixed 20-byte field, but its start is not at a fixed offset. The dump payload's first 2 bytes are skipped (undecoded), and the remainder is unpacked into a dense byte array; that array opens with an ASCII figure pair — two decimal numbers, each 1–4 digits, separated and terminated by colons (e.g. `256:256:`, `83:146:`, `0:0:`) — of undecoded meaning. The name begins immediately after that pair. Because each number's width varies independently, the name's start offset varies with it; reading the name at a constant offset (assuming both numbers are always 3 digits) mis-decodes any voice whose figure pair is narrower, dropping the name's leading character (e.g. `Dyno Straight MW+AS2` decodes as `yno Straight MW+AS2`). The name's true length is then confirmed by finding where the name repeats 6 bytes after its end — a greedy read of the printable run alone can overshoot by one when the byte immediately following the name happens to also be printable.

**Selection**: three parameter-set messages at `65 00 00`/`65 00 01`/`65 00 02` (bank-select MSB, bank-select LSB, program number). Selection only takes effect while the instrument is in Voice mode (readable via a parameter request at `0A 00 01`); in Performance or Song mode the instrument does not acknowledge the selection messages at all (no echo, rather than an echo that just fails to apply), and gives no error either way — the app reads the current mode and refuses the operation itself rather than waiting on an echo that will never arrive. Of the three messages, only the last (the program) is waited on: its echo is what confirms the selection landed, where the first two would echo identically whether they mattered or not.

If the instrument is not in Voice mode, the app offers to switch it: a documented mode-change message (`F0 43 1n 7F 03 0A 00 01 <mode> F7`) sets the mode, but only after explicit confirmation, since it audibly changes what the instrument is playing. The mode change itself draws no reply — it's a parameter *change*, not a request — so the app cannot simply wait on it; instead it polls the mode with repeated parameter requests until Voice mode is read back or a budget elapses, since the switch is not instantaneous (the instrument tears down a Performance and loads a voice) and reading immediately after sending returns the stale value. If the mode never settles within budget, the switch is reported as failed rather than assumed to have worked.

**Rename — the documented path, not the extension below**: renaming a stored voice does not use the `0x40` selection extension or a bulk-dump write to a `0C`-style address at all. Editing the current voice's Common block via parameter writes only changes the edit buffer, not the stored slot, and there is no host-sendable "commit edit buffer to slot" message — that is a physical Store button press. Instead the app: (1) reads the stored voice via the documented Bulk Header/Footer addressing (`0E mm nn`), a sequence of roughly 26 messages; (2) replaces only the Common block's first 20 bytes (the name field, NUL-padded) — every other block is passed back unmodified aside from re-addressing it from the instrument's own model ID to the host's; (3) sends the whole sequence back between a freshly built header and footer — the **footer itself commits to Flash ROM**, so no separate store marker is needed for a rename; (4) reads the name back through the normal browsing path to confirm it stuck.

**Delete, copy, move, and swap — the undocumented `0x0C` extension**: the instrument has no native delete, copy, or move command. These four operations are all composed host-side from one primitive: write a whole voice payload as a bulk dump to a `0C <bank> <slot>` address, then commit with a separate flash-commit marker — a zero-payload bulk dump addressed at `11 00 00`. A write's acknowledgement (`0x60`) means only that the message was well-formed and accepted, not that it reached flash; a refusal (read-only bank, bad checksum, declared length mismatch) is answered with silence and is not buffered. The commit marker is idempotent and, critically, **commits everything pending since the last commit, not just the current operation's writes** — an accepted-but-uncommitted write from an abandoned operation stays armed, even across a reconnect, and is applied by the next commit from anywhere. Because of this, each operation issues all of its writes before its single commit, which is what makes it atomic: if a later write in the sequence is refused, nothing has been committed yet and the instrument is unchanged. Every operation reads the affected slot(s) back afterward and compares bytes exactly, since the commit's own acknowledgement doesn't guarantee the flash write matches what was sent.

- *Delete*: write a blank/initialized payload to the slot, commit, verify.
- *Swap*: read both slots, write each into the other's address, one shared commit, verify both.
- *Copy*: read the source (refusing an empty source or an already-occupied destination — the instrument enforces neither), write to the destination, commit, verify. The name returned is the source's name verbatim, since both slots end up byte-identical and nothing analogous to Nord's auto-disambiguated name is generated.
- *Move*: read the source and a blank payload for its bank, write the destination first and then the blank to the source, one shared commit, verify both.

A blank/initialized payload for delete and move is never fabricated: the app ships one asset per voice kind (Normal, Drum) — an initialized voice with its name bytes cleared through the documented rename path — and uses whichever matches the target bank's kind. Delete and move therefore work even in a fully populated bank, where there would be no empty slot to source a blank from otherwise.

The app also refuses moving or copying a voice between a drum bank and a normal-voice bank, which the instrument does not reject on its own — a normal voice sent to a drum-bank address and a drum kit sent to a normal-bank address are both silently acknowledged (never committed, so the result is deliberately never tested further). Since the instrument accepts the wrong kind of payload without complaint, this check has to live in the app.

**Reliability**: the instrument sends no unsolicited SysEx. A bulk dump written to a stored-voice address is acknowledged on receipt, but the acknowledgement only means the message was well-formed — it does not confirm the write reached flash, since only the commit marker does that. Listing all voices requires reading each one individually rather than a single directory query, which is why the app streams results incrementally rather than waiting for a complete listing.

Please also check the available documentation by the manufacturer: 
https://usa.yamaha.com/products/contents/music_production/downloads/manuals/index.html?l=en&c=music_production&k=Motif+XS
