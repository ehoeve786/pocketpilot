package app.pocketpilot.capability.screencapture

import android.graphics.Bitmap
import app.pocketpilot.capability.api.screen.Frame
import app.pocketpilot.capability.api.screen.ImageEncoder
import app.pocketpilot.capability.api.screen.ImageFormat
import java.io.ByteArrayOutputStream

/** Scales with bilinear filtering and encodes with the platform codecs. */
class BitmapImageEncoder : ImageEncoder {
    private companion object {
        const val MAX_QUALITY = 100
    }

    override fun encode(
        frame: Frame,
        width: Int,
        height: Int,
        format: ImageFormat,
        quality: Int,
    ): ByteArray {
        require(width > 0 && height > 0) { "An image needs a size above zero, not ${width}x$height" }
        val source = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        var scaled: Bitmap? = null
        try {
            scaled = if (width == frame.width && height == frame.height) source else Bitmap.createScaledBitmap(source, width, height, true)
            val out = ByteArrayOutputStream()
            val compressFormat =
                when (format) {
                    ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
                    ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                }
            // PNG ignores quality; JPEG and WebP take 0 to 100, so anything else is clamped rather than thrown.
            check(scaled.compress(compressFormat, quality.coerceIn(0, MAX_QUALITY), out)) { "Encoding $format failed" }
            return out.toByteArray()
        } finally {
            if (scaled != null && scaled !== source) scaled.recycle()
            source.recycle()
        }
    }
}
