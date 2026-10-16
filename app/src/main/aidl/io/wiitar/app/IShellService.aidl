// Runs inside a Shizuku "user service" process with ADB shell (uid 2000) privileges.
package io.wiitar.app;

interface IShellService {
    // Transaction code reserved by Shizuku for tearing the service down.
    void destroy() = 16777114;

    int uid() = 1;

    // Runs one of the app's fixed diagnostic commands through `sh -c`, returns stdout+stderr.
    String run(String command) = 2;
}
