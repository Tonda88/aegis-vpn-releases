package com.aegis.vpn.util

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} Б"
    else String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "00:00"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

fun formatPing(ms: Long?): String = if (ms == null || ms <= 0) "—" else "$ms мс"
