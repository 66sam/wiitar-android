/*
 * wiitar-android — Wii guitar to Android gamepad bridge
 * Copyright (C) 2026 wiitar-android contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.wiitar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.wiitar.protocol.Fret

/**
 * Wiitar's look: a dark "backstage" palette (warm off-black, amp-light amber)
 * with the five fret colours as the only other accents, condensed uppercase
 * headings, flat panels with hairline borders. No Material components, so it
 * doesn't look like a stock template.
 */
object Palette {
    val background = Color(0xFF12100E)
    val panel = Color(0xFF1B1815)
    val panelRaised = Color(0xFF25211D)
    val line = Color(0xFF3A332C)
    val text = Color(0xFFF3EDE4)
    val textDim = Color(0xFFA89C8E)
    val textFaint = Color(0xFF6E6459)
    val amber = Color(0xFFF2A93B)
    val ok = Color(0xFF63C174)
    val warn = Color(0xFFE5484D)

    fun fret(fret: Fret): Color = when (fret) {
        Fret.GREEN -> Color(0xFF3DBE5A)
        Fret.RED -> Color(0xFFE5484D)
        Fret.YELLOW -> Color(0xFFF5C518)
        Fret.BLUE -> Color(0xFF3B82F6)
        Fret.ORANGE -> Color(0xFFF28C28)
    }
}

object Type {
    /** Android's built-in condensed sans (Roboto Condensed on most devices). */
    private val condensed = FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))

    val wordmark = TextStyle(
        color = Palette.amber, fontSize = 30.sp, fontWeight = FontWeight.Black,
        letterSpacing = 6.sp, fontFamily = condensed,
    )
    val heading = TextStyle(
        color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp, fontFamily = condensed,
    )
    val body = TextStyle(color = Palette.text, fontSize = 15.sp, lineHeight = 21.sp)
    val dim = TextStyle(color = Palette.textDim, fontSize = 13.sp, lineHeight = 18.sp)
    val mono = TextStyle(color = Palette.textDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    val button = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, textAlign = TextAlign.Center)
}

@Composable
fun Text(text: String, style: TextStyle = Type.body, modifier: Modifier = Modifier) =
    BasicText(text, modifier, style)

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) = Text(text.uppercase(), Type.heading, modifier)

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.panel)
            .border(1.dp, Palette.line, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** The one big call to action on a screen. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Palette.amber else Palette.panelRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), Type.button.copy(color = if (enabled) Palette.background else Palette.textFaint))
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Palette.line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, Type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
    }
}

/** A tappable row: title, optional detail, chevron. */
@Composable
fun NavRow(title: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (detail != null) Text(detail, Type.dim)
        }
        Text("›", Type.body.copy(color = Palette.textFaint, fontSize = 22.sp))
    }
}

/** A setup step: status dot, title, detail, optional action. */
@Composable
fun StepRow(done: Boolean, title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (done) Palette.ok else Palette.panelRaised)
                .border(1.dp, if (done) Palette.ok else Palette.line, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, Type.body.copy(fontWeight = FontWeight.SemiBold))
            Text(detail, Type.dim)
        }
        if (action != null && !done) {
            Spacer(Modifier.width(8.dp))
            SecondaryButton(action, onClick = onAction)
        }
    }
}

/** A row of mutually exclusive options. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Palette.line, RoundedCornerShape(8.dp)),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) Palette.panelRaised else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), Type.dim.copy(color = if (on) Palette.amber else Palette.textDim, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, Type.dim)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (checked) Palette.amber else Palette.panelRaised)
                .border(1.dp, Palette.line, RoundedCornerShape(13.dp))
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(if (checked) Palette.background else Palette.textDim))
        }
    }
}

/**
 * Chooses whose settings a screen edits: the shared ones, or one connected
 * guitar's own profile. [selected] is a guitar id, or null for shared.
 */
@Composable
fun ProfilePicker(guitars: List<io.wiitar.app.GuitarInfo>, selected: String?, onSelect: (String?) -> Unit) {
    if (guitars.isEmpty()) return
    val options = listOf<String?>(null) + guitars.map { it.id }
    Segmented(options, selected, { id ->
        if (id == null) "All guitars" else "Player ${guitars.firstOrNull { it.id == id }?.slot ?: "?"}"
    }, onSelect)
    Text(
        if (selected == null) "Shared settings, used by every guitar without its own."
        else if (io.wiitar.app.BridgeClient.hasProfile(selected)) "This guitar has its own settings ($selected)."
        else "Changes here give this guitar its own settings ($selected).",
        Type.dim,
    )
    if (selected != null && io.wiitar.app.BridgeClient.hasProfile(selected)) {
        SecondaryButton("Use shared settings for this guitar") {
            io.wiitar.app.BridgeClient.clearProfile(selected)
            onSelect(selected)
        }
    }
}

