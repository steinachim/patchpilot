# Payloads written over a slot to erase it

**Real instrument data, not constructed.** Erasing a preset means writing something
over it, and this project's rule is that it never invents bytes for a wire it does
not fully understand. Every file here was captured from real traffic: read back from
the instrument, or recorded from the manufacturer's own editor.

| File | Bytes | What it is |
|---|---|---|
| `motifxs_blank_normal.bin` | 1,886 | An initialized Motif XS Normal Voice, name cleared. |
| `motifxs_blank_drum.bin` | 12,588 | The same for a Drum Voice. |
| `axefx3_empty_preset.bin` | 49,323 | What Axe-Edit III sends to delete an Axe-Fx III preset: 17 raw SysEx messages. |
| `subsequent37_panel_initialized.bin` | 488 | The program a Subsequent 37's own front-panel Initialize produces: one raw 488-byte payload, no SysEx framing. |

## Motif XS

Each Motif XS file is a fully initialized voice — the instrument's own factory-default
parameters — with its 20 name bytes cleared through the documented write path, so it
reads as empty without leaving any of the previous voice's sound behind. Clearing a
name alone is not sufficient: a slot with its parameters intact still sounds like what
it was, even once it displays as unnamed.

These are the only payloads the app writes over a cleared Motif XS slot (a delete, or the source of a move),
chosen by the destination bank's kind (Normal or Drum).

## Axe-Fx III

`axefx3_empty_preset.bin` is the dump write Axe-Edit III sends when it deletes a preset,
recorded from the editor clearing a slot: 16 `0x78` chunk messages of fill data (mostly
`0xFF`) and the `0x79` end marker `00 00 00`. The app replays the messages verbatim, then
stores the slot. The instrument does not store the fill; it takes the transfer as an erase,
and the slot then reads `<EMPTY>`. An Axe-Fx III dump cannot be built: its end marker is a
checksum over the wire bytes whose algorithm is unknown, so a blank can only be replayed
exactly as recorded (see `docs/PROTOCOLS.md`). SHA-256
`cb64bd85166fd81e4849736a317858edbc588593d961065d319e80166c3be380`.

## Subsequent 37

`subsequent37_panel_initialized.bin` is what the instrument's front-panel Initialize produces, stored to a slot and read back: the 488-byte program payload, name `Init Preset`, category byte `0x77` (none), checksum closing. The app packs it into a `05 01` write when it deletes a preset or empties the source of a move, and reads the slot back to confirm it.

It is not what a slot that was never written holds. That program is also named `Init Preset` but differs at 60 byte offsets, and no write can recreate "never written". The app treats both as empty by their name alone.

## Adding one

Do not hand-construct a replacement for another instrument or voice kind. A blank must come
from the instrument or its own editor, never be assembled from a specification.
