package com.cleanmove.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LiveColor = Color(0xFF2DD4BF)
val ScheduledColor = Color(0xFFF5A524)

private val Scheme = darkColorScheme(
    primary = LiveColor,
    onPrimary = Color(0xFF00201C),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFE6E9EC),
    surface = Color(0xFF14181D),
    onSurface = Color(0xFFE6E9EC),
    surfaceVariant = Color(0xFF1D232A),
    onSurfaceVariant = Color(0xFF9AA4AE),
    error = Color(0xFFFF6B6B),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun CleanMoveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = AppShapes, content = content)
}
