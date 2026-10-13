// The guitar bridge, running in a Shizuku user service with ADB shell (uid 2000) privileges.
package io.wiitar.app;

interface IBridgeService {
    // Transaction code reserved by Shizuku for tearing the service down.
    void destroy() = 16777114;

    // Starts the bridge if it isn't running. tiltAxis: 0 = X, 1 = Y, 2 = Z (used without a
    // calibration). tiltCalibration: TiltCalibration.encode() or empty. buttonLayout:
    // ButtonLayout.encode() or empty for the defaults. Returns status().
    String start(int tiltAxis, boolean tiltInvert, String tiltCalibration, String buttonLayout) = 1;

    // Human-readable status: sources, virtual device, live values, errors.
    String status() = 2;

    // Current smoothed gravity vector as Vec3.encode(), or empty before the first sample.
    String gravity() = 3;

    // Applies a new tilt calibration (TiltCalibration.encode(), or empty to clear) immediately.
    void setTiltCalibration(String tiltCalibration) = 4;

    // Applies a new button layout (ButtonLayout.encode()) immediately.
    void setButtonLayout(String buttonLayout) = 5;
}
