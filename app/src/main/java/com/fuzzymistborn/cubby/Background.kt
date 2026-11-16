package com.fuzzymistborn.cubby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import java.io.File

/** Home screen backdrop: the system wallpaper (default), a gradient preset or a chosen photo. */
enum class HomeBackground(val label: String, val colors: List<Color>) {
    SYSTEM("Wallpaper", emptyList()),
    OCEAN("Ocean", listOf(Color(0xFF1E88E5), Color(0xFF0D3B66))),
    FOREST("Forest", listOf(Color(0xFF43A047), Color(0xFF1B4D2E))),
    SUNSET("Sunset", listOf(Color(0xFFEF6C57), Color(0xFF7B2D6B))),
    NIGHT("Night", listOf(Color(0xFF3F4C8A), Color(0xFF12122B))),
    BERRY("Berry", listOf(Color(0xFFB4457E), Color(0xFF4A1942))),
    PHOTO("Photo", emptyList());

    companion object {
        fun fromName(name: String?): HomeBackground = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Draws [bg] behind the content; the system wallpaper (or a photo not loaded yet) draws nothing. */
fun Modifier.homeBackground(bg: HomeBackground, photo: ImageBitmap?): Modifier = when {
    bg.colors.isNotEmpty() -> background(Brush.verticalGradient(bg.colors))
    bg == HomeBackground.PHOTO && photo != null -> paint(BitmapPainter(photo), contentScale = ContentScale.Crop)
    else -> this
}

private const val PHOTO_FILE = "home_background.jpg"
private const val PHOTO_MAX_SIDE = 2048

fun hasBackgroundPhoto(context: Context): Boolean = File(context.filesDir, PHOTO_FILE).exists()

/** The saved photo, or null. Blocking: call off the main thread. */
fun loadBackgroundPhoto(context: Context): ImageBitmap? = runCatching {
    val file = File(context.filesDir, PHOTO_FILE)
    if (!file.exists()) return null
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)).asImageBitmap()
}.getOrNull()

/**
 * Copies the picked image into app storage, scaled down so a huge photo can't exhaust memory.
 * Blocking: call off the main thread. Returns false (leaving any earlier photo alone) on failure.
 */
fun saveBackgroundPhoto(context: Context, uri: Uri): Boolean = runCatching {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > PHOTO_MAX_SIDE) {
            val scale = PHOTO_MAX_SIDE.toFloat() / longest
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1),
            )
        }
    }
    val tmp = File(context.filesDir, "$PHOTO_FILE.tmp")
    tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    tmp.copyTo(File(context.filesDir, PHOTO_FILE), overwrite = true)
    tmp.delete()
    true
}.getOrDefault(false)
