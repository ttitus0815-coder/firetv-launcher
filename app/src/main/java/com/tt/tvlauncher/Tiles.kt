package com.tt.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap

/**
 * A home-screen tile. [packages] are candidate package names; the first one installed wins.
 * [logoBottomOnly] keeps just the lowest part of a stacked logo (Prime Video's smile, not its wordmark).
 */
data class TileSpec(val id: String, val label: String, val packages: List<String>, val logoBottomOnly: Boolean = false)

const val SETTINGS_ID = "settings"

val DEFAULT_TILES = listOf(
    TileSpec("appletv", "Apple TV", listOf("com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv")),
    TileSpec("disney", "Disney+", listOf("com.disney.disneyplus")),
    TileSpec("hulu", "Hulu", listOf("com.hulu.plus", "com.hulu.livingroomplus")),
    TileSpec("netflix", "Netflix", listOf("com.netflix.ninja")),
    TileSpec("peacock", "Peacock", listOf("com.peacock.peacockfiretv", "com.peacocktv.peacockandroid")),
    TileSpec("plex", "Plex", listOf("com.plexapp.android")),
    TileSpec("prime", "Prime Video", listOf("com.amazon.firebat", "com.amazon.avod", "com.amazon.amazonvideo.livingroom"), logoBottomOnly = true),
    TileSpec("youtube", "YouTube", listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv")),
)

/** Persists the tile order (Settings is always last and not part of the order). */
class TileOrderStore(context: Context) {
    private val prefs = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)

    fun load(): List<TileSpec> {
        val byId = DEFAULT_TILES.associateBy { it.id }
        val saved = prefs.getString(ORDER_KEY, null)?.split(",")?.mapNotNull { byId[it] }.orEmpty()
        // Append any tiles missing from the saved order so new defaults still show up.
        return saved + DEFAULT_TILES.filter { it !in saved }
    }

    fun save(tiles: List<TileSpec>) {
        prefs.edit().putString(ORDER_KEY, tiles.joinToString(",") { it.id }).apply()
    }

    companion object {
        // Bumped when the default order changed to alphabetical, so the old saved order is dropped once.
        private const val ORDER_KEY = "order_alphabetical"
    }
}

fun PackageManager.resolvePackage(spec: TileSpec): String? =
    spec.packages.firstOrNull { pkg ->
        try {
            getApplicationInfo(pkg, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

/** An app's logo for the dock, as a white silhouette (or the full-color icon if that fails). */
class Glyph(val bitmap: Bitmap, val monochrome: Boolean)

fun PackageManager.tileGlyph(pkg: String, bottomOnly: Boolean = false): Glyph? {
    val drawable = try {
        getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        return null
    }
    // Keep the icon's own shape (some, like Prime Video's, are wide banners) and render it large
    // enough to stay sharp on a 1080p screen.
    val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: 192
    val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: 192
    val scale = maxOf(1f, 384f / maxOf(iw, ih))
    val icon = drawable.toBitmap((iw * scale).toInt(), (ih * scale).toInt(), Bitmap.Config.ARGB_8888)
    val glyph = whiteLogo(icon, bottomOnly)?.let { Glyph(it, monochrome = true) } ?: Glyph(icon, monochrome = false)
    // Keep logos in GPU memory only.
    return glyph.bitmap.copy(Bitmap.Config.HARDWARE, false)?.let { Glyph(it, glyph.monochrome) } ?: glyph
}

/**
 * Keeps whatever differs from the icon's background color, as white with matching alpha, then
 * crops to the logo. A YouTube icon becomes the play button, Netflix the N, and so on.
 */
private fun whiteLogo(icon: Bitmap, bottomOnly: Boolean): Bitmap? {
    val w = icon.width
    val h = icon.height
    val px = IntArray(w * h)
    icon.getPixels(px, 0, w, 0, 0, w, h)
    // Sample just inside the corners: inside any rounded mask, outside the logo.
    val insetX = w / 8
    val insetY = h / 8
    val samples = listOf(
        px[insetY * w + insetX], px[insetY * w + w - 1 - insetX],
        px[(h - 1 - insetY) * w + insetX], px[(h - 1 - insetY) * w + w - 1 - insetX],
    )
    if (samples.any { (it ushr 24) < 200 }) return null
    // Blend the four corner colors across the icon, so gradient backgrounds drop out too.
    fun channel(shift: Int) = samples.map { ((it shr shift) and 0xFF).toFloat() }
    val (rTL, rTR, rBL, rBR) = channel(16)
    val (gTL, gTR, gBL, gBR) = channel(8)
    val (bTL, bTR, bBL, bBR) = channel(0)
    fun lerp2(tl: Float, tr: Float, bl: Float, br: Float, fx: Float, fy: Float) =
        (tl + (tr - tl) * fx) * (1 - fy) + (bl + (br - bl) * fx) * fy

    val out = IntArray(w * h)
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    for (y in 0 until h) for (x in 0 until w) {
        val c = px[y * w + x]
        val a = (c ushr 24) and 0xFF
        val fx = ((x - insetX).toFloat() / (w - 1 - 2 * insetX)).coerceIn(0f, 1f)
        val fy = ((y - insetY).toFloat() / (h - 1 - 2 * insetY)).coerceIn(0f, 1f)
        val d = kotlin.math.abs(((c shr 16) and 0xFF) - lerp2(rTL, rTR, rBL, rBR, fx, fy)) +
            kotlin.math.abs(((c shr 8) and 0xFF) - lerp2(gTL, gTR, gBL, gBR, fx, fy)) +
            kotlin.math.abs((c and 0xFF) - lerp2(bTL, bTR, bBL, bBR, fx, fy))
        val alpha = (((d - 60f) * 255f / 120f).toInt().coerceIn(0, 255) * a / 255)
        out[y * w + x] = (alpha shl 24) or 0xFFFFFF
        if (alpha > 80) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
    }
    if (maxX < 0) return null
    if (bottomOnly) {
        // Keep only the shape that reaches lowest (Prime Video's smile under its wordmark), plus
        // any piece touching it, like the smile's arrowhead.
        val ink = BooleanArray(w * h) { (out[it] ushr 24) > 80 }
        val seen = BooleanArray(w * h)
        val boxes = mutableListOf<IntArray>()
        for (start in 0 until w * h) {
            if (!ink[start] || seen[start]) continue
            val stack = ArrayDeque<Int>().apply { add(start) }
            seen[start] = true
            val box = intArrayOf(w, h, -1, -1)
            var size = 0
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val x = i % w; val y = i / w
                size++
                box[0] = minOf(box[0], x); box[1] = minOf(box[1], y); box[2] = maxOf(box[2], x); box[3] = maxOf(box[3], y)
                for (n in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                    if (n < 0 || n >= w * h || seen[n] || !ink[n]) continue
                    if ((n == i - 1 || n == i + 1) && n / w != y) continue
                    seen[n] = true
                    stack.add(n)
                }
            }
            if (size >= 20) boxes += box
        }
        boxes.maxByOrNull { it[3] }?.let { lowest ->
            val m = w / 40
            val keep = intArrayOf(lowest[0], lowest[1], lowest[2], lowest[3])
            for (b in boxes) {
                val touches = b[0] <= lowest[2] + m && b[2] >= lowest[0] - m && b[1] <= lowest[3] + m && b[3] >= lowest[1] - m
                if (touches) {
                    keep[0] = minOf(keep[0], b[0]); keep[1] = minOf(keep[1], b[1]); keep[2] = maxOf(keep[2], b[2]); keep[3] = maxOf(keep[3], b[3])
                }
            }
            // Clear everything outside the kept area so the wordmark doesn't peek in.
            for (y in 0 until h) for (x in 0 until w) if (x !in keep[0]..keep[2] || y !in keep[1]..keep[3]) out[y * w + x] = 0x00FFFFFF
            minX = keep[0]; minY = keep[1]; maxX = keep[2]; maxY = keep[3]
        }
    }
    val coverage = (maxX - minX + 1) * (maxY - minY + 1).toFloat() / (w * h)
    // Nothing found, or "logo" is the whole icon: the background guess was wrong.
    if (coverage < 0.01f || coverage > 0.9f) return null
    val pad = 2
    val x0 = (minX - pad).coerceAtLeast(0); val y0 = (minY - pad).coerceAtLeast(0)
    val x1 = (maxX + pad).coerceAtMost(w - 1); val y1 = (maxY + pad).coerceAtMost(h - 1)
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).let { Bitmap.createBitmap(it, x0, y0, x1 - x0 + 1, y1 - y0 + 1) }
}

fun PackageManager.launchIntent(pkg: String): Intent? =
    (getLeanbackLaunchIntentForPackage(pkg) ?: getLaunchIntentForPackage(pkg))
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun Context.openFireTvSettings() {
    val intents = listOfNotNull(
        Intent(Settings.ACTION_SETTINGS),
        packageManager.launchIntent("com.amazon.tv.settings.v2"),
    )
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // try the next one
        }
    }
}

fun Context.bringLauncherToFront() {
    startActivity(
        Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
    )
}

/**
 * Fire OS drops our accessibility service from the enabled list whenever the app is force-stopped.
 * With WRITE_SECURE_SETTINGS granted over adb, put it back.
 */
fun Context.ensureHomeServiceEnabled() {
    if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
    val name = android.content.ComponentName(this, HomeButtonService::class.java).flattenToString()
    val current = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val enabled = current.split(':').filter { it.isNotBlank() && it != "null" }
    if (enabled.none { it.equals(name, ignoreCase = true) }) {
        Settings.Secure.putString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (enabled + name).joinToString(":")
        )
    }
    Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
}
