/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.bridge

/** Rolling latency samples (microseconds) with summary statistics. Not thread-safe. */
class LatencyStats(private val capacity: Int = 500) {
    private val samples = LongArray(capacity)
    private var count = 0
    private var next = 0

    fun add(micros: Long) {
        if (micros < 0 || micros > 10_000_000) return // clock mismatch or a stale event; ignore
        samples[next] = micros
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        count = 0
        next = 0
    }

    /** [samples, average, 95th percentile, max], all 0 when empty. */
    fun summary(): LongArray {
        if (count == 0) return longArrayOf(0, 0, 0, 0)
        val sorted = samples.copyOf(count).sorted()
        val p95 = sorted[((count - 1) * 95) / 100]
        return longArrayOf(count.toLong(), sorted.sum() / count, p95, sorted.last())
    }
}
