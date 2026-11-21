package app.pocketpilot.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import app.pocketpilot.feature.automations.automationIconRes
import app.pocketpilot.core.designsystem.R as DsR

/**
 * The picture of a home screen icon, from the "Home screen icon" trigger's settings: a white glyph on the chosen
 * background colour, an app's icon, or a picture file. Each is drawn as an adaptive icon, so the launcher shapes it
 * like the other icons. A rule from before icons could be chosen keeps the bolt it was pinned with.
 */
internal object HomeScreenIconArt {
    /** 108dp at the highest density launchers ask for; the launcher shows the middle 72dp. */
    private const val SIZE = 432

    /** How much of the icon a glyph fills, so it sits inside the part every launcher shape shows. */
    private const val GLYPH = 0.42f

    /** How much of the icon an app icon without layers fills. */
    private const val LEGACY = 0.62f

    private const val DEFAULT_BACKGROUND = "#263238"

    fun draw(
        context: Context,
        params: Map<String, String>,
    ): Icon {
        val key = params["icon"]
        if (key.isNullOrBlank()) return Icon.createWithResource(context, DsR.drawable.core_designsystem_ic_bolt)
        val background =
            runCatching { Color.parseColor(params["background"]?.trim().orEmpty().ifEmpty { DEFAULT_BACKGROUND }) }
                .getOrDefault(Color.parseColor(DEFAULT_BACKGROUND))
        val bitmap =
            when (key) {
                "app" -> params["app"]?.takeIf(String::isNotBlank)?.let { appIcon(context, it, background) }
                "image" -> params["image"]?.takeIf(String::isNotBlank)?.let(::picture)
                else -> null
            } ?: glyph(context, automationIconRes(key) ?: DsR.drawable.core_designsystem_ic_bolt, background)
        return Icon.createWithAdaptiveBitmap(bitmap)
    }

    private fun glyph(
        context: Context,
        res: Int,
        background: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        context.getDrawable(res)?.mutate()?.let { drawable ->
            drawable.setTint(Color.WHITE)
            drawCentered(canvas, drawable, GLYPH)
        }
        return bitmap
    }

    /** The app's own icon: its layers fill the icon when it has them, else it sits on the background colour. */
    private fun appIcon(
        context: Context,
        packageName: String,
        background: Int,
    ): Bitmap? {
        val icon = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull() ?: return null
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (icon is AdaptiveIconDrawable) {
            listOfNotNull(icon.background, icon.foreground).forEach { layer ->
                layer.bounds = Rect(0, 0, SIZE, SIZE)
                layer.draw(canvas)
            }
        } else {
            canvas.drawColor(background)
            drawCentered(canvas, icon, LEGACY)
        }
        return bitmap
    }

    /** The picture at [path], cut to a square from its middle; null when it cannot be read. */
    private fun picture(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
    }

    private fun drawCentered(
        canvas: Canvas,
        drawable: Drawable,
        fraction: Float,
    ) {
        val side = (SIZE * fraction).toInt()
        val start = (SIZE - side) / 2
        drawable.bounds = Rect(start, start, start + side, start + side)
        drawable.draw(canvas)
    }
}
