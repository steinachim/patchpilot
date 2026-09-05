# Changelog

## Next release
- Change: the Behringer Pro-800 now loads presets by writing its settings block over SysEx instead of sending bank select and program change, so it no longer needs to know the instrument's MIDI channel — including when the channel comes from the rear DIP switches or MIDI receive is off, where loading a preset previously could not work at all.
- Fix: the connect screen no longer lists an instrument after it's unplugged, and tapping it can no longer hang waiting for USB permission.
- Add: recognize the Yamaha Motif XS7 and XS8 (in addition to the XS6), sharing the XS6's protocol and catalog config. USB product IDs are inferred from Yamaha driver files, not yet confirmed on real hardware.

## 0.9
Initial release.
