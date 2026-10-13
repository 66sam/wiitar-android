/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <errno.h>
#include <jni.h>
#include <linux/input.h>
#include <sys/ioctl.h>

/*
 * EVIOCGRAB: while grabbed, events from this evdev device go only to this fd,
 * so Android (and every game) stops seeing the device. The kernel releases the
 * grab when the fd is closed, including when the process dies.
 *
 * Returns 0 on success or -errno.
 */
JNIEXPORT jint JNICALL
Java_io_wiitar_app_Evdev_grab(JNIEnv *env, jclass clazz, jint fd, jboolean grab) {
    (void) env;
    (void) clazz;
    return ioctl(fd, EVIOCGRAB, grab ? 1 : 0) == 0 ? 0 : -errno;
}
