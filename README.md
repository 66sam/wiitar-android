# wiitar-android

Use a Wii Remote + Guitar Hero guitar as a game controller on Android
(Clone Hero, YARG), with no PC and no adapter dongle.

> **Status: early.** The Wiimote protocol layer (`:protocol`) is written and
> unit-tested. The Android app is waiting on a real-device test that decides
> how it connects; see [Roadmap](#roadmap) and
> [First on-device test](#first-on-device-test).

## How it works (and why it isn't the obvious design)

The obvious design is "open L2CAP sockets to the Wiimote from an app, then
inject gamepad events with an AccessibilityService". On stock Android neither
half works:

- **App-level L2CAP is Bluetooth LE only.** `BluetoothDevice.createL2capChannel()`
  (API 29) opens LE credit-based channels. A Wiimote is classic Bluetooth
  (BR/EDR) HID on PSMs 0x11/0x13, which apps can't open and which the
  system's HID service already holds. This is why Dolphin on Android supports
  real Wiimotes only through a DolphinBar.
- **An AccessibilityService can't create a gamepad.** It can dispatch
  gestures and global actions, but it can't register an input device or send
  key/axis events to another app. Clone Hero and YARG (Unity) only see real
  `InputDevice`s.

What *does* exist: Android's Generic Kernel Image ships the Linux
`hid-wiimote` driver (`CONFIG_HID_WIIMOTE=y` in `gki_defconfig`). That driver
handles Guitar Hero guitars, extension hot-plug, and one input device per
Wiimote. Once Android's own Bluetooth stack pairs the Wiimote, every app
should see it as a normal controller. The hard part is pairing (the Wiimote
wants a binary PIN), which an app can supply via `BluetoothDevice.setPin()`.

So the plan has two paths:

| | Non-root (default) | Root (optional) |
|---|---|---|
| Connection | System Bluetooth HID host + kernel `hid-wiimote` | `/dev/hidraw*` for the Wiimote |
| Decoding | kernel driver | this repo's `:protocol` module |
| Output to games | the kernel's input device | virtual gamepad via `/dev/uinput` |
| Remapping | in Clone Hero / YARG's own binding screens | in-app, any input to any button/axis |
| Touch strip, tilt tuning | whatever the kernel exposes | full |
| App's job | pairing helper, slot/LED view, diagnostics | everything |

Both paths still need hardware confirmation. That is the next step.

## First on-device test

Please run this on the target device (AYN Odin 3 Max) before more Android
code is written. The result decides the app's architecture.

1. Plug the guitar into the Wii Remote.
2. Open **Settings → Bluetooth → Pair new device**.
3. Press the red **SYNC** button (under the battery cover). The LEDs blink.
4. If **Nintendo RVL-CNT-01** (or `-TR`) appears, tap it.
   - If it asks for a PIN, cancel and note that it asked.
   - Wiimotes stay discoverable for about 20 s; press SYNC again if it drops off.
5. If it pairs, open Clone Hero → Settings → Controls → add a controller,
   then press frets, strum, and the whammy bar.
6. Optional, if `adb` is available:
   `adb shell getevent -lp` (lists input devices; look for "Nintendo Wii Remote*")
   and `adb shell getevent -l` while playing.

Report: did it show up, did it pair, did it ask for a PIN, did Clone Hero see
input, and what `getevent` printed.

## Project layout

```
protocol/   Pure Kotlin, no Android dependencies. Wiimote report building/parsing,
            extension detection and setup, guitar decoding, accelerometer tilt,
            and a transport-agnostic session state machine. Fully unit tested.
app/        (not yet) Android app: pairing helper, diagnostics, player slots,
            root uinput backend.
```

### Protocol module overview

- `OutputReports`: LEDs (0x11), report mode (0x12), status request (0x15),
  memory write/read (0x16/0x17).
- `InputReport.parse`: status (0x20), read data (0x21), ack (0x22), and
  every data mode 0x30-0x37 and 0x3D. Accepts packets with or without the
  0xA1 header.
- `WiimoteSession`: on connect, sets the player LEDs and reads the
  accelerometer calibration (EEPROM 0x0016). On every status report it
  detects extension plug/unplug. Extension setup writes 0x55 → 0xA400F0 and
  0x00 → 0xA400FB (no encryption), reads the 6-byte ID at 0xA400FA, then the
  16-byte calibration block at 0xA40020. Finally it sets report mode 0x35
  (buttons + accelerometer + 16 extension bytes). Unplug/replug resets this
  and runs it again. Answers that belong to an extension that has since been
  unplugged are ignored. Every byte in or out is emitted as an event for the
  diagnostics screen.
- `GuitarDecoder`: five frets, strum up/down, plus/minus, analog stick,
  whammy, and the World Tour-style touch strip.
- `TiltCalculator`: guitar tilt from the Wiimote accelerometer (the Wiimote
  sits inside the guitar body). The axis and direction are configurable,
  since they depend on how the Wiimote is mounted.

Notes on the original spec:
- Mode **0x34** is buttons + 19 extension bytes with *no* accelerometer. Tilt
  needs the accelerometer, so the session uses **0x35**.
- Guitar Hero guitars aren't documented to use the 0xA40020 calibration block.
  It is always read and shown raw (with a checksum check) for diagnostics.
  Whammy is calibrated from what the guitar actually reports: the first
  sample is taken as rest, and the range widens as the bar is pressed.
  Nothing is hardcoded to one guitar's values.
- **Rock Band Wii guitars don't plug into a Wii Remote.** They use their own
  USB dongle, so they can't be supported by this approach. On Wii, only
  Guitar Hero-family guitars (GH3, World Tour, GH5, Band Hero, Warriors of
  Rock) use the Wiimote extension port.

### Building and testing

```
./gradlew :protocol:test
```

Requires JDK 17+. The protocol module builds without the Android SDK.

## Supported extension IDs

| ID (0xA400FA-FF) | Device | Status |
|---|---|---|
| `00 00 a4 20 01 03` | Guitar Hero guitar (GH3, World Tour, GH5, Band Hero, WoR) | decoded |
| `01 00 a4 20 01 03` | GH World Tour drums | detected only |
| `03 00 a4 20 01 03` | DJ Hero turntable | detected only |
| others | Nunchuk, Classic/Pro, Taiko, uDraw, MotionPlus | detected only |

World Tour-style touch strips can't be told apart by ID. The decoder enables
the strip once it has seen both the idle value (0x0F) and a touched value, so
guitars without a strip are unaffected.

## Known-compatible guitars

| Guitar | Device / Android | Path | Result |
|---|---|---|---|
| *(none tested yet)* | | | |

## Adding support for a new guitar / extension ID

1. Connect it and open the diagnostics screen. It shows the 6-byte extension
   ID, the raw calibration block, and live extension bytes. Until the app
   exists, the same data comes from `WiimoteEvent.ExtensionConnected` /
   `RawInput`.
2. If the ID isn't in the table, add it to `ExtensionType` in
   `protocol/src/main/kotlin/io/wiitar/protocol/Extensions.kt`. If its data
   uses the standard guitar layout (check the live bytes while pressing each
   fret), use family `GUITAR`.
3. If the layout differs, add a decoder next to `GuitarDecoder`, plus a unit
   test built from the bytes you captured (see `GuitarTest`).
4. Add a row to the compatibility table and open a PR.

## Roadmap

1. ✅ Protocol layer + unit tests
2. ⏳ On-device test of system pairing + kernel driver (above)
3. App: pairing helper (`setPin` with the Wiimote's reversed address),
   per-player LED/slot view, and a diagnostics screen
4. Root backend: hidraw → `:protocol` → uinput virtual gamepad, plus a
   remapping UI with "press the input to bind" capture, and per-device
   settings
5. Multiplayer: one virtual controller per Wiimote, with a slot assignment UI

Each step ends with a request to test on real hardware.

## References

- WiiBrew: [Wiimote](https://wiibrew.org/wiki/Wiimote),
  [Extension Controllers](https://wiibrew.org/wiki/Wiimote/Extension_Controllers)
- Linux `hid-wiimote` driver (`drivers/hid/hid-wiimote-*.c`)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). GPL-licensed Wiimote code (wiiuse,
cwiid, the Linux driver) may be used as a reference.
