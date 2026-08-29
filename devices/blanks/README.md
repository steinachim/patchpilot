# Payloads written over a slot to erase it

**Real instrument data, not constructed.** Erasing a preset means writing something
over it, and this project's rule is that it never invents bytes for a wire it does
not fully understand. Each file here is a fully initialized voice — the instrument's
own factory-default parameters — with its 20 name bytes cleared through the
documented write path, so it reads as empty without leaving any of the previous
voice's sound behind. Clearing a name alone is not sufficient: a slot with its
parameters intact still sounds like what it was, even once it displays as unnamed.

| File | Bytes | What it is |
|---|---|---|
| `motifxs_blank_normal.bin` | 1,886 | An initialized Motif XS Normal Voice, name cleared. |
| `motifxs_blank_drum.bin` | 12,588 | The same for a Drum Voice. |

These are the only payloads the app writes when deleting or moving a Motif XS voice, chosen by the destination bank's kind (Normal or Drum). Do not hand-construct a replacement for another instrument or voice kind — a blank must be read back from a real, initialized voice on the instrument itself, never assembled from a specification.
