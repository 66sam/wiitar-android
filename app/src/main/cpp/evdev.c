/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The few evdev ioctls Java can't make. Every function returns 0 on success
 * or -errno.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/input.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

/*
 * EVIOCGRAB: while grabbed, events from this evdev device go only to this fd,
 * so Android (and every game) stops seeing the device. The kernel releases the
 * grab when the fd is closed, including when the process dies.
 */
JNIEXPORT jint JNICALL
Java_io_wiitar_app_Evdev_grab(JNIEnv *env, jclass clazz, jint fd, jboolean grab) {
    (void) env;
    (void) clazz;
    return ioctl(fd, EVIOCGRAB, grab ? 1 : 0) == 0 ? 0 : -errno;
}

/* Timestamp this fd's events with CLOCK_MONOTONIC (Java's System.nanoTime) for latency measurement. */
JNIEXPORT jint JNICALL
Java_io_wiitar_app_Evdev_useMonotonicClock(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    int clock = CLOCK_MONOTONIC;
    return ioctl(fd, EVIOCSCLOCKID, &clock) == 0 ? 0 : -errno;
}

/* Rumbles the device at [path] (a force-feedback evdev node) for [millis]; blocks meanwhile. */
JNIEXPORT jint JNICALL
Java_io_wiitar_app_Evdev_rumble(JNIEnv *env, jclass clazz, jstring jpath, jint millis) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return -ENOMEM;
    int fd = open(path, O_RDWR | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) return -errno;

    struct ff_effect effect;
    memset(&effect, 0, sizeof(effect));
    effect.type = FF_RUMBLE;
    effect.id = -1;
    effect.u.rumble.strong_magnitude = 0xffff;
    effect.replay.length = (unsigned short) millis;
    if (ioctl(fd, EVIOCSFF, &effect) < 0) {
        int err = errno;
        close(fd);
        return -err;
    }

    struct input_event play;
    memset(&play, 0, sizeof(play));
    play.type = EV_FF;
    play.code = (unsigned short) effect.id;
    play.value = 1;
    if (write(fd, &play, sizeof(play)) < 0) {
        int err = errno;
        close(fd);
        return -err;
    }
    usleep((useconds_t) millis * 1000);
    close(fd); /* also removes the effect */
    return 0;
}
