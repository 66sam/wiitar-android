// The guitar bridge, running in a Shizuku user service with ADB shell (uid 2000) privileges.
package io.wiitar.app;

interface IBridgeService {
    // Transaction code reserved by Shizuku for tearing the service down.
    void destroy() = 16777114;

    // Starts the bridge if it isn't running. tiltAxis: 0 = X, 1 = Y, 2 = Z. Returns status().
    String start(int tiltAxis, boolean tiltInvert) = 1;

    // Human-readable status: sources, virtual device, live values, errors.
    String status() = 2;
}
