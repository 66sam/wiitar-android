/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.wiitar.bridge.LiveState
import io.wiitar.protocol.Fret

/**
 * A simple drawn guitar showing one guitar's [live] state (a [LiveState]
 * slice): frets light up in their colours, the strum bar shows direction,
 * plus the whammy, tilt meter and touch strip when available.
 */
@Composable
fun GuitarView(live: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().aspectRatio(2.6f)) {
        val w = size.width
        val h = size.height
        fun on(i: Int) = live.getOrElse(i) { 0 } != 0

        // Neck and headstock.
        drawRoundRect(Palette.panelRaised, Offset(w * 0.02f, h * 0.36f), Size(w * 0.62f, h * 0.28f), CornerRadius(h * 0.06f))
        drawRoundRect(Palette.panelRaised, Offset(0f, h * 0.30f), Size(w * 0.09f, h * 0.40f), CornerRadius(h * 0.08f))

        // Body.
        val bodyTopLeft = Offset(w * 0.58f, h * 0.08f)
        val bodySize = Size(w * 0.41f, h * 0.84f)
        drawRoundRect(Palette.panel, bodyTopLeft, bodySize, CornerRadius(h * 0.32f))
        drawRoundRect(Palette.line, bodyTopLeft, bodySize, CornerRadius(h * 0.32f), style = Stroke(width = 2f))

        // Touch strip, only once the guitar has shown it has one.
        if (on(LiveState.TOUCH_PRESENT)) {
            val left = w * 0.18f
            val right = w * 0.58f
            val y = h * 0.24f
            drawLine(Palette.line, Offset(left, y), Offset(right, y), strokeWidth = h * 0.05f, cap = StrokeCap.Round)
            val touch = live[LiveState.TOUCH]
            if (touch != LiveState.NO_TOUCH) {
                val x = left + (right - left) * ((touch / 1000f + 1f) / 2f)
                drawCircle(Palette.amber, h * 0.045f, Offset(x, y))
            }
        }

        // Frets.
        val fretBits = live.getOrElse(LiveState.FRETS) { 0 }
        Fret.entries.forEachIndexed { i, fret ->
            val center = Offset(w * (0.21f + i * 0.088f), h * 0.5f)
            val r = h * 0.09f
            val color = Palette.fret(fret)
            if (fretBits and (1 shl fret.ordinal) != 0) {
                drawCircle(color.copy(alpha = 0.25f), r * 1.7f, center)
                drawCircle(color, r, center)
            } else {
                drawCircle(Palette.panel, r, center)
                drawCircle(color.copy(alpha = 0.55f), r, center, style = Stroke(width = 3f))
            }
        }

        // Strum bar: the half being pushed lights up.
        val strum = live.getOrElse(LiveState.STRUM) { 0 }
        val barLeft = w * 0.69f
        val barW = w * 0.06f
        val barTop = h * 0.28f
        val barH = h * 0.44f
        drawRoundRect(Palette.panelRaised, Offset(barLeft, barTop), Size(barW, barH), CornerRadius(barW / 3))
        if (strum != 0) {
            val top = if (strum < 0) barTop else barTop + barH / 2
            drawRoundRect(Palette.amber, Offset(barLeft, top), Size(barW, barH / 2), CornerRadius(barW / 3))
        }

        // Plus above, minus below.
        drawButtonGlyph(Offset(w * 0.80f, h * 0.30f), h * 0.05f, plus = true, lit = on(LiveState.PLUS))
        drawButtonGlyph(Offset(w * 0.80f, h * 0.70f), h * 0.05f, plus = false, lit = on(LiveState.MINUS))

        // Whammy bar swings down as it's pushed.
        val whammy = live.getOrElse(LiveState.WHAMMY) { -1 }
        val pivot = Offset(w * 0.86f, h * 0.66f)
        if (whammy < 0) {
            drawLine(
                Palette.line, pivot, Offset(w * 0.98f, h * 0.80f), strokeWidth = h * 0.03f, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        } else {
            val push = whammy / 1000f
            drawLine(
                if (push > 0.05f) Palette.amber else Palette.textDim,
                pivot, Offset(w * 0.98f, h * (0.80f + push * 0.14f)), strokeWidth = h * 0.03f, cap = StrokeCap.Round,
            )
        }

        // Tilt meter; amber once star power is triggered.
        val tilt = live.getOrElse(LiveState.TILT) { -1 }
        if (tilt >= 0) {
            val left = w * 0.92f
            val top = h * 0.16f
            val meterH = h * 0.36f
            val meterW = w * 0.018f
            drawRoundRect(Palette.panelRaised, Offset(left, top), Size(meterW, meterH), CornerRadius(meterW / 2))
            val fill = meterH * (tilt / 1000f)
            drawRoundRect(
                if (on(LiveState.STAR_POWER)) Palette.amber else Palette.textDim,
                Offset(left, top + meterH - fill), Size(meterW, fill), CornerRadius(meterW / 2),
            )
        }
    }
}

private fun DrawScope.drawButtonGlyph(center: Offset, r: Float, plus: Boolean, lit: Boolean) {
    drawCircle(if (lit) Palette.amber else Palette.panelRaised, r * 1.6f, center)
    val c = if (lit) Palette.background else Palette.textDim
    drawLine(c, Offset(center.x - r * 0.7f, center.y), Offset(center.x + r * 0.7f, center.y), strokeWidth = r * 0.35f)
    if (plus) drawLine(c, Offset(center.x, center.y - r * 0.7f), Offset(center.x, center.y + r * 0.7f), strokeWidth = r * 0.35f)
}
