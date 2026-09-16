package com.tadpole.instrument.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Paper=Color(0xFFF5F1E8)
val Ink=Color(0xFF332C28)
val Muted=Color(0xFF81756A)
val Accent=Color(0xFFB95536)
val Peach=Color(0xFFF0D8C7)
val Line=Color(0xFFDDD4C7)
val Cocoa=Color(0xFF754D39)

@Composable fun TadpoleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme=lightColorScheme(primary=Accent,onPrimary=Color.White,
            primaryContainer=Peach,onPrimaryContainer=Ink,background=Paper,onBackground=Ink,
            surface=Paper,onSurface=Ink,surfaceVariant=Color(0xFFEDE5D9),onSurfaceVariant=Muted,
            outline=Line,secondary=Color(0xFF647B69),secondaryContainer=Peach,onSecondaryContainer=Ink),
        typography=Typography(
            headlineLarge=TextStyle(fontSize=30.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.7).sp),
            titleLarge=TextStyle(fontSize=23.sp,fontWeight=FontWeight.SemiBold),
            labelSmall=TextStyle(fontSize=10.sp,fontFamily=FontFamily.Monospace,letterSpacing=1.5.sp),
            bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
        ),content=content)
}
