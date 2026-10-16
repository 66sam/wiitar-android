**Real whammy and the full touch strip on the AYN Odin.**

The Odin's controller layer takes over Wii guitars and drops the whammy bar.
Wiitar now opens the guitar the moment it connects, before that happens, so
the whammy bar works and the touch strip presses individual frets. Check
*Advanced → Latency & status*: it shows `KERNEL_GUITAR` when this worked.

Also in this release:
- The guitar picture on the home screen shows the analog stick.
- Every build's release APK is now signed with the release key, so test
  builds install over a released copy.
- The Wii Remote's light session is released properly when a guitar
  disconnects.
- System probe: checks for the Wii Remote's raw HID channel and the system
  log, replacing the Odin mapping-app checks.
- Removed the "Let Wiitar handle Wii guitars" toggle; it isn't needed and
  couldn't work.

**Needs [Shizuku](https://shizuku.rikka.app/)** running. See the README for
setup. Updating from 0.1.0 keeps your settings.

Install `wiitar-0.1.1.apk` from the assets below.
