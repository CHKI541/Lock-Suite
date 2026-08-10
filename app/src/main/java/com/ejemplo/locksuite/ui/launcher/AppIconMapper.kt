package com.ejemplo.locksuite.ui.launcher

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class KosherAppIcon(
    val label: String,
    val icon: ImageVector,
    val gradientStart: Color,
    val gradientEnd: Color,
    val isCustom: Boolean = true
)

object AppIconMapper {

    private val musicGradient = Pair(Color(0xFFE040FB), Color(0xFF8E24AA))     // Pink -> Purple
    private val recordGradient = Pair(Color(0xFF00E676), Color(0xFF00796B))    // Green -> Teal
    private val galleryGradient = Pair(Color(0xFF7C4DFF), Color(0xFF303F9F))   // Purple -> Indigo
    private val videoGradient = Pair(Color(0xFFFF5252), Color(0xFFC2185B))     // Red -> Dark Pink
    private val sidurGradient = Pair(Color(0xFF1E3C72), Color(0xFF2A5298))     // Navy -> Blue
    private val notesGradient = Pair(Color(0xFFFFD740), Color(0xFFF57C00))     // Yellow -> Orange
    private val calcGradient = Pair(Color(0xFF90A4AE), Color(0xFF37474F))      // Grey -> Slate
    private val cameraGradient = Pair(Color(0xFFFF5252), Color(0xFFD81B60))    // Red -> Pink
    private val calendarGradient = Pair(Color(0xFFFF6E40), Color(0xFFD84315))  // Coral -> Deep Orange
    private val folderGradient = Pair(Color(0xFF00B0FF), Color(0xFF005792))    // Light Blue -> Dark Blue
    private val clockGradient = Pair(Color(0xFF00E5FF), Color(0xFF0091EA))     // Cyan -> Blue
    private val settingsGradient = Pair(Color(0xFFFF9100), Color(0xFFE65100))  // Orange -> Deep Orange
    private val genericGradient = Pair(Color(0xFF78909C), Color(0xFF455A64))   // Cool Slate

    fun getMapping(packageName: String, label: String): KosherAppIcon {
        val pkgLower = packageName.lowercase()
        val labelLower = label.lowercase()

        // 1. Sidur (Jewish prayer book)
        if (pkgLower.contains("sidur") || pkgLower.contains("siddur") || pkgLower.contains("tefila") ||
            labelLower.contains("sidur") || labelLower.contains("siddur") || labelLower.contains("tefila") ||
            labelLower.contains("סדור") || labelLower.contains("תפילה")) {
            return KosherAppIcon("Sidur", Icons.Filled.MenuBook, sidurGradient.first, sidurGradient.second)
        }

        // 2. Music / MP3
        if (pkgLower.contains("music") || pkgLower.contains("musica") || pkgLower.contains("player") || pkgLower.contains("mp3") ||
            labelLower.contains("music") || labelLower.contains("música") || labelLower.contains("reproductor") || labelLower.contains("mp3") || labelLower.contains("נגן")) {
            return KosherAppIcon("Música", Icons.Filled.MusicNote, musicGradient.first, musicGradient.second)
        }

        // 3. Sound Recorder
        if (pkgLower.contains("record") || pkgLower.contains("grabadora") || pkgLower.contains("voice") || pkgLower.contains("audio") ||
            labelLower.contains("record") || labelLower.contains("grabadora") || labelLower.contains("voz") || labelLower.contains("grabación") || labelLower.contains("הקלטה")) {
            return KosherAppIcon("Grabadora", Icons.Filled.Mic, recordGradient.first, recordGradient.second)
        }

        // 4. Gallery / Photos
        if (pkgLower.contains("gallery") || pkgLower.contains("galeria") || pkgLower.contains("photos") || pkgLower.contains("image") ||
            labelLower.contains("gallery") || labelLower.contains("galería") || labelLower.contains("fotos") || labelLower.contains("imágenes") || labelLower.contains("גלריה")) {
            return KosherAppIcon("Galería", Icons.Filled.Image, galleryGradient.first, galleryGradient.second)
        }

        // 5. Videos
        if (pkgLower.contains("video") || pkgLower.contains("movie") || pkgLower.contains("film") ||
            labelLower.contains("video") || labelLower.contains("videos") || labelLower.contains("películas") || labelLower.contains("סרט")) {
            return KosherAppIcon("Videos", Icons.Filled.PlayArrow, videoGradient.first, videoGradient.second)
        }

        // 6. Notes
        if (pkgLower.contains("note") || pkgLower.contains("keep") || pkgLower.contains("memo") ||
            labelLower.contains("note") || labelLower.contains("notas") || labelLower.contains("keep") || labelLower.contains("bloc") || labelLower.contains("פתק")) {
            return KosherAppIcon("Notas", Icons.Filled.Notes, notesGradient.first, notesGradient.second)
        }

        // 7. Calculator
        if (pkgLower.contains("calc") || labelLower.contains("calc") || labelLower.contains("calculadora") || labelLower.contains("מחשבון")) {
            return KosherAppIcon("Calculadora", Icons.Filled.Calculate, calcGradient.first, calcGradient.second)
        }

        // 8. Camera
        if (pkgLower.contains("camera") || pkgLower.contains("cámara") || labelLower.contains("camera") || labelLower.contains("cámara") || labelLower.contains("מצלמה")) {
            return KosherAppIcon("Cámara", Icons.Filled.PhotoCamera, cameraGradient.first, cameraGradient.second)
        }

        // 9. Calendar
        if (pkgLower.contains("calendar") || pkgLower.contains("calendario") || labelLower.contains("calendar") || labelLower.contains("calendario") || labelLower.contains("לוח שנה")) {
            return KosherAppIcon("Calendario", Icons.Filled.DateRange, calendarGradient.first, calendarGradient.second)
        }

        // 10. Clock / Alarm
        if (pkgLower.contains("clock") || pkgLower.contains("reloj") || pkgLower.contains("alarm") || pkgLower.contains("time") ||
            labelLower.contains("clock") || labelLower.contains("reloj") || labelLower.contains("alarma") || labelLower.contains("שעון")) {
            return KosherAppIcon("Reloj", Icons.Filled.Alarm, clockGradient.first, clockGradient.second)
        }

        // 11. Files / Folder explorer / PDF Reader
        if (pkgLower.contains("file") || pkgLower.contains("folder") || pkgLower.contains("document") || pkgLower.contains("pdf") || pkgLower.contains("explorer") ||
            labelLower.contains("file") || labelLower.contains("archivo") || labelLower.contains("carpeta") || labelLower.contains("documentos") || labelLower.contains("pdf") || labelLower.contains("explorer") || labelLower.contains("קבצים")) {
            val isPdf = pkgLower.contains("pdf") || labelLower.contains("pdf")
            val icon = if (isPdf) Icons.Filled.PictureAsPdf else Icons.Filled.Folder
            val name = if (isPdf) "Lector PDF" else "Archivos"
            return KosherAppIcon(name, icon, folderGradient.first, folderGradient.second)
        }

        // 12. Settings / Configuration (If they have whitelisted settings)
        if (pkgLower.contains("settings") || pkgLower.contains("config") ||
            labelLower.contains("settings") || labelLower.contains("ajustes") || labelLower.contains("configuración") || labelLower.contains("הגדרות")) {
            return KosherAppIcon("Ajustes", Icons.Filled.Settings, settingsGradient.first, settingsGradient.second)
        }

        // Default case for other apps
        return KosherAppIcon(label, Icons.Filled.Android, genericGradient.first, genericGradient.second, isCustom = false)
    }
}
