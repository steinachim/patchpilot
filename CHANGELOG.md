# Changelog

## Next release
- Add: the Yamaha Motif XS browser can now show the instrument's 1,217 factory voices, and the favorites marked on its own front panel, chosen with a selector above the list; previously only the four user banks were listed at all. The factory names ship with the app instead of being read over MIDI, which would take about seven and a half minutes, and a factory voice can be copied into a user slot. Favorites are read-only: the instrument offers no documented way to set one, so the app only reports what is already marked.
- Add: a Yamaha Motif XS that is connected but not answering is now identified as such on the connect screen, which explains that its MIDI In/Out setting most likely needs to be set to USB and gives the steps to change it. Previously the app connected anyway and every operation timed out separately without saying why.
- Change: the Behringer Pro-800 now loads presets by writing its settings block over SysEx instead of sending bank select and program change, so it no longer needs to know the instrument's MIDI channel — including when the channel comes from the rear DIP switches or MIDI receive is off, where loading a preset previously could not work at all.
- Fix: the connect screen no longer lists an instrument after it's unplugged, and tapping it can no longer hang waiting for USB permission.
- Add: recognize the Yamaha Motif XS7 and XS8 (in addition to the XS6), sharing the XS6's protocol and catalog config. USB product IDs are inferred from Yamaha driver files, not yet confirmed on real hardware.

## 0.9
Initial release.
