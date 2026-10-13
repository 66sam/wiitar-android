/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.bridge

/** The input devices that make up one connected Wii Remote + guitar. */
data class GuitarDevices(
    /** Stable for the life of the Bluetooth connection: the HID device's sysfs path. */
    val key: String,
    /** Device the guitar's buttons are read from (the kernel's, or the Odin's copy of it). */
    val guitar: KernelInputDevice,
    val source: GuitarSource,
    val accelerometer: KernelInputDevice?,
    /** The Wii Remote's own device (buttons, rumble). */
    val remote: KernelInputDevice?,
)

/** Finds connected guitars in /proc/bus/input/devices. */
object GuitarDiscovery {
    const val GUITAR_NAME = "Nintendo Wii Remote Guitar"
    const val ACCEL_NAME = "Nintendo Wii Remote Accelerometer"
    const val REMOTE_NAME = "Nintendo Wii Remote"
    const val NINTENDO = 0x057e

    /**
     * @param readable whether the device's /dev/input node can be opened.
     *
     * The kernel's guitar device is used when readable. On the AYN Odin it is
     * hidden and re-published with another vendor ID; those copies can't be
     * traced back to a Wii Remote, so the Nth hidden guitar is matched with the
     * Nth copy in creation order.
     */
    fun find(devices: List<KernelInputDevice>, readable: (KernelInputDevice) -> Boolean): List<GuitarDevices> {
        val kernelGuitars = devices.filter { it.name == GUITAR_NAME && it.vendor == NINTENDO }.sortedBy { it.hidParent }
        val copies = devices.filter { it.name == GUITAR_NAME && it.vendor != NINTENDO && readable(it) }
            .sortedBy { inputNumber(it) }
        var hiddenIndex = 0
        return kernelGuitars.mapNotNull { kernel ->
            val (device, source) = if (readable(kernel)) {
                kernel to GuitarSource.KERNEL_GUITAR
            } else {
                val copy = copies.getOrNull(hiddenIndex++) ?: return@mapNotNull null
                copy to GuitarSource.ODIN_GUITAR
            }
            GuitarDevices(
                key = kernel.hidParent,
                guitar = device,
                source = source,
                accelerometer = devices.firstOrNull { it.name == ACCEL_NAME && it.hidParent == kernel.hidParent && readable(it) },
                remote = devices.firstOrNull { it.name == REMOTE_NAME && it.hidParent == kernel.hidParent },
            )
        }
    }

    private fun inputNumber(d: KernelInputDevice) = d.sysfs.substringAfterLast("/input").toIntOrNull() ?: Int.MAX_VALUE
}
