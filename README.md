# Wiitar

Play Clone Hero and YARG on Android with a Wii Guitar Hero guitar. No PC,
no DolphinBar, no root.

Wiitar pairs the Wii Remote over Bluetooth, reads the guitar plugged into it
and presents it to games as a standard controller, **"Wiitar Guitar 1"**
(2, 3, … for more guitars). Every fret, strum, plus/minus and tilt for star
power can be bound in the game's own controller settings.

> **Status: 0.1, early.** Tested on an AYN Odin 3 Max (Android 15). Other
> devices with a recent Android kernel should work, but haven't been tried.
> Reports are welcome. See [Reporting a problem](#reporting-a-problem).

## What you need

- A **Guitar Hero guitar for Wii** (GH3, World Tour, GH5, Band Hero, Warriors
  of Rock) and a **Wii Remote** to plug into it. Rock Band Wii guitars use a
  USB dongle instead and aren't supported.
- An Android 10+ device.
- **[Shizuku](https://shizuku.rikka.app/)** (free). It gives Wiitar the
  permissions it needs to create a controller, without rooting. It's started
  through Wireless debugging and has to be started again after each reboot.

## Install

Download `wiitar-<version>.apk` from the
[Releases](../../releases) page and open it on your device (allow installing
from your browser or file manager when asked).

## Set up

1. **Install and start Shizuku** (in the Shizuku app: *Start via Wireless
   debugging*). Open Wiitar and tap **Allow** when it asks for Shizuku.
2. **Pair the Wii Remote** once: on Wiitar's home screen tap *Pair a new Wii
   Remote*.
   - Choose **SYNC** (recommended), tap *Fill in automatically*, open the Wii
     Remote's battery cover and press the red **SYNC** button, then tap the
     Wii Remote in the list.
   - After that, pressing any button on the Wii Remote reconnects it.
   - Wii Remotes need a special binary PIN that Android's own pairing dialog
     can't take. Wiitar answers it for you.
3. **Plug the guitar into the Wii Remote.** The bridge starts by itself, and
   the home screen shows the guitar live, with battery level.
4. **In the game**, open the controller settings and bind the controller
   named *Wiitar Guitar 1*. In Clone Hero it can show up as player 2.

## Default layout

| Guitar | "Wiitar Guitar" |
|---|---|
| Green / Red / Yellow / Blue / Orange | A / X / Y / LB / RB |
| Strum up / down | D-pad up / down |
| Plus / Minus | Start / Select |
| Tilt (after calibration) | right stick Y, and right-stick click past the threshold |
| Whammy | right stick X (see the AYN Odin note) |
| Touch strip | `AXIS_RX`, or frets, or whammy (your choice) |

No fret uses B, because Android treats an unused B as "Back".

## Settings (Advanced)

- **Controls**: tap a control, then press what you want on the guitar.
  Choose the touch strip mode (axis, frets, whammy or off), and the gamepad
  button each control sends. With several guitars, each can have its own
  profile.
- **Tilt**: two-step calibration (hold it as you play, then raise the neck).
- **Auto-start**: start the bridge whenever a Wii Remote connects.
- **Latency & status**: the bridge's own delay and Android's, plus status for
  bug reports.
- Pairing helper, guided guitar test, input diagnostics and a system probe,
  for troubleshooting.

### AYN Odin note

The Odin's controller layer hides the guitar's own input device and passes on
a copy without the whammy bar, and with a touch strip that only reports
left/centre/right. Set the touch strip to *whammy* (Advanced → Controls) to
use the strip as whammy on this device.

## Multiplayer

Pair more Wii Remotes the same way. Each guitar gets its own controller
("Wiitar Guitar 2", …), its own player lights where the device allows it, and
a *Buzz* button on the home screen to tell them apart.

## Reporting a problem

Open *Advanced → System probe* (and *Latency & status*), tap *Copy*, and paste
the output into an issue with your device model and Android version.

## How it works

Apps can't open the classic-Bluetooth L2CAP channels a Wii Remote uses (that's
why Dolphin needs a DolphinBar), and they can't create game controllers. But
Android's kernel includes the Linux `hid-wiimote` driver. Once Android pairs
the Wii Remote, the kernel exposes it, its accelerometer and its guitar as
input devices.

Wiitar's bridge runs as a Shizuku service with ADB-shell rights. It:

- reads those devices from `/dev/input`;
- takes exclusive access to the guitar's own device (`EVIOCGRAB`, via a small
  JNI library), so games don't see two controllers;
- computes tilt from the accelerometer;
- creates one virtual gamepad per guitar through `/dev/uhid`.

## Building

```
./gradlew :protocol:test :app:assembleDebug
```

- Needs JDK 17. The app needs the Android SDK and NDK (CMake); the protocol
  module builds without them.
- GitHub Actions builds debug and release APKs on every push.
- The **Release** workflow (Actions → Release → Run workflow, or push a tag
  `v*`) creates a draft release with the APK.
- Releases are signed with the key in the `WIITAR_KEYSTORE_BASE64`,
  `WIITAR_KEYSTORE_PASSWORD`, `WIITAR_KEY_ALIAS` and `WIITAR_KEY_PASSWORD`
  repository secrets; the Release workflow refuses to run without them.
  Other builds use the committed debug key (`app/debug.keystore`), so release
  and test builds can't be installed over each other.

### Layout

```
protocol/   Pure Kotlin, unit tested.
            io.wiitar.protocol: Wii Remote reports, extension setup,
            guitar decoding, tilt, pairing PINs.
            io.wiitar.bridge: evdev/uhid formats, guitar → gamepad mapping,
            settings and profiles.
app/        Android app (Jetpack Compose UI), the Shizuku bridge service,
            and a JNI helper for evdev ioctls.
```

### Supported extension IDs

| ID (0xA400FA-FF) | Device | Status |
|---|---|---|
| `00 00 a4 20 01 03` | Guitar Hero guitar | supported |
| `01 00 a4 20 01 03` | GH World Tour drums | detected only |
| `03 00 a4 20 01 03` | DJ Hero turntable | detected only |

To add a guitar variant, capture its bytes with the diagnostics screens. Then
add the ID to `ExtensionType` (`protocol/.../Extensions.kt`) and a test like
those in `GuitarTest`.

## References

- WiiBrew: [Wiimote](https://wiibrew.org/wiki/Wiimote),
  [Extension Controllers](https://wiibrew.org/wiki/Wiimote/Extension_Controllers)
- Linux `hid-wiimote` driver (`drivers/hid/hid-wiimote-*.c`)
- [Shizuku](https://github.com/RikkaApps/Shizuku)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
