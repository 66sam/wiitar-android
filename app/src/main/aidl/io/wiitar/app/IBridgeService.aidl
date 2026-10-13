// The guitar bridge, running in a Shizuku user service with ADB shell (uid 2000) privileges.
package io.wiitar.app;

interface IBridgeService {
    // Transaction code reserved by Shizuku for tearing the service down.
    void destroy() = 16777114;

    // Starts bridging every connected guitar (and any that connect later).
    // config: the shared BridgeConfig.encode(); profiles: Profiles.encode() of per-guitar
    // settings keyed by guitar id. Returns status().
    String start(String config, String profiles) = 1;

    // Applies new settings to running guitars immediately.
    void configure(String config, String profiles) = 2;

    // Human-readable status: guitars, sources, latency, errors.
    String status() = 3;

    // LiveState.SIZE ints per bridged guitar, in slot order.
    int[] liveState() = 4;

    // Smoothed gravity vector of the guitar in [slot] (Vec3.encode()), or empty.
    String gravity(int slot) = 5;

    // Records the next control press on any guitar; poll captureResult().
    void beginCapture() = 6;

    // "" while waiting, else "<GuitarSource name>|<PhysicalInput.encode()>|<slot>".
    String captureResult() = 7;

    // Rumbles the Wii Remote in [slot] for [millis] ms (to see which guitar is which player).
    void rumble(int slot, int millis) = 8;

    // Bridge latency in microseconds: [samples, average, 95th percentile, max].
    long[] latency() = 9;

    // One "slot<TAB>guitar id<TAB>battery %" line per bridged guitar, in slot order.
    String[] guitars() = 10;
}
