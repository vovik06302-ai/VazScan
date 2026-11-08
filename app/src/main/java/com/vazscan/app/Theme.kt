package com.vazscan.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val VazCyan = Color(0xFF26C6DA)
val VazTeal = Color(0xFF00897B)
val VazViolet = Color(0xFF7E57C2)
val VazAmber = Color(0xFFFFB300)

fun vazDark(): ColorScheme = darkColorScheme(
    primary = VazCyan,
    onPrimary = Color(0xFF00232A),
    secondary = VazViolet,
    onSecondary = Color.White,
    tertiary = VazAmber,
    background = Color(0xFF0D1B2A),
    onBackground = Color(0xFFE3F2FD),
    surface = Color(0xFF16263A),
    onSurface = Color(0xFFE3F2FD),
    surfaceVariant = Color(0xFF1B3149),
    onSurfaceVariant = Color(0xFF9FB3C8),
    outlineVariant = Color(0xFF2E4A66),
    primaryContainer = Color(0xFF0E4A55),
    onPrimaryContainer = Color(0xFFB2EBF2)
)

fun vazLight(): ColorScheme = lightColorScheme(
    primary = VazTeal,
    onPrimary = Color.White,
    secondary = VazViolet,
    onSecondary = Color.White,
    tertiary = VazAmber,
    background = Color(0xFFF2F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE3EEF5),
    onSurfaceVariant = Color(0xFF546E7A),
    outlineVariant = Color(0xFFB0BEC5),
    primaryContainer = Color(0xFFB2EBF2),
    onPrimaryContainer = Color(0xFF00363D)
)
