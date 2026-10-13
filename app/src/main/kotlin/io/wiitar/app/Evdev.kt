/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app

import java.io.File

/** JNI access to the evdev ioctls Java can't make (see src/main/cpp/evdev.c). */
object Evdev {
    @Volatile
    private var loaded = false

    /**
     * Loads libwiitar_evdev. In the Shizuku service process the default
     * library path may not include the app's lib dir, so fall back to
     * [nativeLibraryDir] (the APK extracts native libs; see build.gradle.kts).
     *
     * @return null on success, else why it failed.
     */
    fun load(nativeLibraryDir: String?): String? {
        if (loaded) return null
        val errors = mutableListOf<String>()
        try {
            System.loadLibrary("wiitar_evdev")
            loaded = true
            return null
        } catch (e: Throwable) {
            errors += "loadLibrary: $e"
        }
        val file = nativeLibraryDir?.let { File(it, "libwiitar_evdev.so") }
        if (file != null && file.exists()) {
            try {
                System.load(file.path)
                loaded = true
                return null
            } catch (e: Throwable) {
                errors += "load(${file.path}): $e"
            }
        } else {
            errors += "no library in $nativeLibraryDir"
        }
        return errors.joinToString("; ")
    }

    /** EVIOCGRAB; returns 0 or -errno. Call [load] first. */
    @JvmStatic
    external fun grab(fd: Int, grab: Boolean): Int

    /** EVIOCSCLOCKID(CLOCK_MONOTONIC), so event times compare with System.nanoTime(); 0 or -errno. */
    @JvmStatic
    external fun useMonotonicClock(fd: Int): Int

    /** Rumbles the force-feedback device at [path] for [millis] ms (blocks); 0 or -errno. */
    @JvmStatic
    external fun rumble(path: String, millis: Int): Int
}
