package com.tt.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Environment
import java.io.File

/** A wallpaper plus a heavily blurred copy used for the frosted-glass dock. */
class Wallpaper(val full: Bitmap, val blurred: Bitmap)

/**
 * Paints everything static (wallpaper, shading, the frosted app panel with its border) into a single
 * screen-sized bitmap, so each frame only has to blit one image under the tiles.
 */
fun bakeBackdrop(wallpaper: Wallpaper, width: Int, height: Int, dock: RectF, dockRadius: Float, hairline: Float): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    val screen = coverRect(wallpaper.full, width, height)
    canvas.drawBitmap(wallpaper.full, null, screen, paint)
    // Soft shade behind the buttons (top), the app list (left) and the clock (bottom right).
    paint.shader = LinearGradient(0f, 0f, 0f, height * 0.25f, 0x40000000, 0, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width.toFloat(), height * 0.25f, paint)
    paint.shader = LinearGradient(0f, 0f, width * 0.45f, 0f, 0x59000000, 0, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width * 0.45f, height.toFloat(), paint)
    paint.shader = RadialGradient(width.toFloat(), height.toFloat(), width * 0.5f, 0x80000000.toInt(), 0, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.shader = null

    if (!dock.isEmpty) {
        val path = Path().apply { addRoundRect(dock, dockRadius, dockRadius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(wallpaper.blurred, null, coverRect(wallpaper.blurred, width, height), paint)
        canvas.drawColor(0x7A0E1216)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = hairline
        paint.color = 0x1FFFFFFF
        canvas.drawPath(path, paint)
    }
    return out
}

private fun coverRect(bitmap: Bitmap, width: Int, height: Int): RectF {
    val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}

/**
 * Your own photos from /sdcard/Pictures/TVHome/ (needs READ_EXTERNAL_STORAGE, granted over adb),
 * then the built-in photos.
 */
class WallpaperStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
    private val photoDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TVHome")

    fun all(): List<String> {
        val photos = photoDir.listFiles { f -> f.extension.lowercase() in PHOTO_EXTENSIONS }
            ?.sortedBy { it.name }?.map { PHOTO_PREFIX + it.name }.orEmpty()
        // Photos first: if you added your own, that's what you want to see.
        return photos + BUILT_IN_PHOTOS.keys
    }

    fun current(): String = prefs.getString("current", null)?.takeIf { it in all() } ?: all().first()

    fun next(): String {
        val ids = all()
        val id = ids[(ids.indexOf(current()) + 1) % ids.size]
        prefs.edit().putString("current", id).apply()
        return id
    }

    /**
     * Decodes wallpaper [id], paints the backdrop, and returns it as a GPU-only bitmap, so the
     * launcher doesn't also hold a full-screen copy in regular memory on this 1.7 GB device.
     */
    fun bake(id: String, width: Int, height: Int, dock: RectF, dockRadius: Float, hairline: Float): Bitmap {
        val wallpaper = load(id)
        val backdrop = bakeBackdrop(wallpaper, width, height, dock, dockRadius, hairline)
        wallpaper.full.recycle()
        wallpaper.blurred.recycle()
        return backdrop.copy(Bitmap.Config.HARDWARE, false)?.also { backdrop.recycle() } ?: backdrop
    }

    private fun load(id: String): Wallpaper {
        val bitmap = when {
            id.startsWith(PHOTO_PREFIX) -> loadPhoto(File(photoDir, id.removePrefix(PHOTO_PREFIX)))
            id in BUILT_IN_PHOTOS -> decodeBuiltIn(BUILT_IN_PHOTOS.getValue(id))
            else -> null
        } ?: decodeBuiltIn(BUILT_IN_PHOTOS.values.first())
        return Wallpaper(bitmap, blur(bitmap))
    }

    // Photos are only painted into the backdrop (with dithering), so 16-bit is plenty and halves memory.
    private fun decodeBuiltIn(res: Int): Bitmap =
        BitmapFactory.decodeResource(context.resources, res, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })

    private fun loadPhoto(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    /** Cheap, smooth blur: repeated bilinear halving, then doubling back up. */
    private fun blur(src: Bitmap): Bitmap {
        var b = src
        while (b.width > 48) b = Bitmap.createScaledBitmap(b, b.width / 2, maxOf(1, b.height / 2), true)
        while (b.width < 384) b = Bitmap.createScaledBitmap(b, b.width * 2, b.height * 2, true)
        return b
    }

    companion object {
        private const val PHOTO_PREFIX = "photo:"
        private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        // All from Wikimedia Commons (originally Unsplash), CC0: "Rocky coastline at Big Sur, CA",
        // "Clouds mirrored in a mountain lake", "Alpine landscape", "Colorful sky during sunset",
        // "Cloud over a forest".
        private val BUILT_IN_PHOTOS = linkedMapOf(
            "bigsur" to R.drawable.wallpaper_bigsur,
            "lake" to R.drawable.wallpaper_lake,
            "alpine" to R.drawable.wallpaper_alpine,
            "sunset" to R.drawable.wallpaper_sunset,
            "forest" to R.drawable.wallpaper_forest,
        )
    }
}
