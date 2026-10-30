package app.pocketpilot.capability.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.Display
import app.pocketpilot.capability.api.BackendIds
import app.pocketpilot.capability.api.screen.Frame
import app.pocketpilot.capability.api.screen.ScreenCapturer
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** CAPTURE_SCREEN, third backend in spec section 6: the Accessibility screenshot API (Android 11+). */
class A11yScreenCapturer : ScreenCapturer {
    override val backendId: String = BackendIds.ACCESSIBILITY
    override val available: StateFlow<Boolean> = A11yBridge.connected

    override suspend fun capture(): Frame {
        val service = A11yBridge.require()
        val first = takeScreenshot(service)
        // The system allows roughly three screenshots a second; wait once and retry when asked to.
        val result =
            if (first is Shot.Failed && first.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                delay(RETRY_DELAY_MS)
                takeScreenshot(service)
            } else {
                first
            }
        return when (result) {
            is Shot.Taken -> {
                result.frame
            }

            is Shot.Failed -> {
                if (result.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    throw ToolException(ToolErrorCode.PROTECTED_SCREEN, "The app on screen blocks screenshots")
                } else {
                    throw ToolException(ToolErrorCode.INTERNAL_ERROR, "The screenshot failed (code ${result.code})", "Retry in a moment")
                }
            }
        }
    }

    private sealed interface Shot {
        class Taken(
            val frame: Frame,
        ) : Shot

        class Failed(
            val code: Int,
        ) : Shot
    }

    private suspend fun takeScreenshot(service: AccessibilityService): Shot =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val frame =
                            buffer.use {
                                val hardware = Bitmap.wrapHardwareBuffer(it, screenshot.colorSpace)
                                val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                hardware?.recycle()
                                software?.let { full ->
                                    // Scaled down before the pixels are copied out, so the long-lived array is the small one.
                                    val bitmap = fitWithin(full, MAX_EDGE_PX)
                                    val pixels = IntArray(bitmap.width * bitmap.height)
                                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                    if (bitmap !== full) bitmap.recycle()
                                    full.recycle()
                                    Frame(bitmap.width, bitmap.height, pixels)
                                }
                            }
                        continuation.resume(frame?.let(Shot::Taken) ?: Shot.Failed(-1))
                    }

                    override fun onFailure(errorCode: Int) = continuation.resume(Shot.Failed(errorCode))
                },
            )
        }

    /** The bitmap scaled so its longer edge is at most [maxEdge] pixels; the same bitmap when it already fits. */
    private fun fitWithin(
        bitmap: Bitmap,
        maxEdge: Int,
    ): Bitmap {
        val longer = maxOf(bitmap.width, bitmap.height)
        if (longer <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longer
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val RETRY_DELAY_MS = 400L
        const val MAX_EDGE_PX = 1_600

        // One shared thread: every capturer, and every screenshot, runs in order instead of each leaving a thread behind.
        val screenshotExecutor: Executor = Executors.newSingleThreadExecutor()
    }
}
