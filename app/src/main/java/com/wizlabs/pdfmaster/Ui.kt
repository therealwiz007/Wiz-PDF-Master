package com.wizlabs.pdfmaster

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Wiz black & yellow palette -------------------------------------------------------------
val WizBg = Color(0xFF0B0B0D)
val WizBar = Color(0xFF060607)
val WizPanel = Color(0xFF17171B)
val WizPanelHi = Color(0xFF222228)
val WizLine = Color(0x1FFFFFFF)
val WizYellow = Color(0xFFFFC800)
val WizYellowDeep = Color(0xFFE0A800)
val WizInk = Color(0xFF111114)
val WizText = Color(0xFFF5F5F7)
val WizMuted = Color(0xFF9A9AA3)
val WizGood = Color(0xFF4ADE80)
val WizBad = Color(0xFFF87171)
val WizGlass = Color(0x99101012)

private val WizColors = darkColorScheme(
    primary = WizYellow,
    onPrimary = WizInk,
    primaryContainer = WizYellowDeep,
    onPrimaryContainer = WizInk,
    secondary = WizYellow,
    onSecondary = WizInk,
    background = WizBg,
    onBackground = WizText,
    surface = WizPanel,
    onSurface = WizText,
    surfaceVariant = WizPanelHi,
    onSurfaceVariant = WizMuted,
    surfaceContainer = WizPanel,
    surfaceContainerHigh = WizPanelHi,
    surfaceContainerHighest = WizPanelHi,
    surfaceContainerLow = WizBg,
    outline = WizLine,
    error = WizBad
)

private val WizType = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
)

private val WizShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun WizTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WizColors, typography = WizType, shapes = WizShapes, content = content)
}

@Composable
fun rememberTick(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}

/** Round translucent icon button for use over camera / images. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp
) {
    val tick = rememberTick()
    val bg by animateColorAsState(if (active) WizYellow else WizGlass, tween(180), label = "bg")
    val fg by animateColorAsState(
        when { !enabled -> Color.White.copy(alpha = .35f); active -> WizInk; else -> Color.White },
        tween(180), label = "fg"
    )
    Surface(
        onClick = { tick(); onClick() },
        enabled = enabled,
        shape = CircleShape,
        color = bg,
        contentColor = fg,
        border = if (active) null else BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
        }
    }
}

/** Selectable pill used for filters and toggles. */
@Composable
fun WizChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val tick = rememberTick()
    val bg by animateColorAsState(if (selected) WizYellow else WizPanelHi, tween(160), label = "chip")
    Surface(
        onClick = { tick(); onClick() },
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = if (selected) WizInk else WizText,
        border = if (selected) null else BorderStroke(1.dp, WizLine),
        modifier = modifier
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Big tile on the home screen ("Scan", "Open PDF" ...). */
@Composable
fun ActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    val tick = rememberTick()
    Surface(
        onClick = { tick(); onClick() },
        shape = RoundedCornerShape(22.dp),
        color = if (primary) WizYellow else WizPanel,
        contentColor = if (primary) WizInk else WizText,
        border = if (primary) null else BorderStroke(1.dp, WizLine),
        modifier = modifier
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (primary) WizInk.copy(alpha = .10f) else WizYellow.copy(alpha = .14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = if (primary) WizInk else WizYellow, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (primary) WizInk.copy(alpha = .7f) else WizMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = WizMuted)
        trailing()
    }
}
