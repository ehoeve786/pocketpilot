package app.pocketpilot.core.capabilities

import app.pocketpilot.capability.api.screen.GlobalAction
import app.pocketpilot.capability.api.screen.InputController
import app.pocketpilot.capability.api.screen.Key
import app.pocketpilot.capability.api.screen.SnapshotOptions
import app.pocketpilot.capability.api.screen.SwipeDirection
import app.pocketpilot.capability.api.screen.Target
import app.pocketpilot.capability.api.screen.passwordFieldRefused
import app.pocketpilot.capability.api.screen.swipePoints
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * INJECT_INPUT across backends. Points, keys and system actions go to the first available backend in
 * spec priority order (Shizuku, then Accessibility). An element goes to the backend that took its
 * snapshot when that backend can act, since it can use node actions; otherwise it becomes a point at
 * the element's centre.
 */
class ResolvingInputController(
    /** In priority order. */
    private val backends: List<InputController>,
    private val reader: ResolvingScreenReader,
    scope: CoroutineScope,
) : InputController {
    override val backendId: String = "resolver"
    override val available: StateFlow<Boolean> = anyAvailable(scope, backends)

    override suspend fun tap(target: Target) {
        val (backend, resolved) = route(target)
        backend.tap(resolved)
    }

    override suspend fun longPress(
        target: Target,
        durationMs: Long,
    ) {
        val (backend, resolved) = route(target)
        backend.longPress(resolved, durationMs)
    }

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ) = backends.firstAvailable().swipe(fromX, fromY, toX, toY, durationMs)

    override suspend fun swipeOn(
        target: Target.OnElement,
        direction: SwipeDirection,
        durationMs: Long,
    ) {
        val same = sameBackend(target)
        if (same != null) return same.swipeOn(target, direction, durationMs)
        val (fromX, fromY, toX, toY) = swipePoints(reader.bounds(target), direction).toList()
        backends.firstAvailable().swipe(fromX, fromY, toX, toY, durationMs)
    }

    /**
     * Text prefers the backend that took the element's snapshot, then the others in priority order.
     * When the field came from another backend's snapshot, it is tapped first and the text goes to
     * whatever field has focus.
     */
    override suspend fun typeText(
        text: String,
        target: Target.OnElement?,
        submit: Boolean,
    ) {
        if (target == null) {
            refusePasswordFocus()
            return backends.firstAvailable().typeText(text, null, submit)
        }
        val same = sameBackend(target)
        if (same != null) return same.typeText(text, target, submit)
        val backend = backends.firstAvailable()
        val bounds = reader.bounds(target)
        backend.tap(Target.AtPoint(bounds.centerX, bounds.centerY))
        delay(FOCUS_SETTLE_MS)
        refusePasswordFocus()
        backend.typeText(text, null, submit)
    }

    override suspend fun pressKey(key: Key) {
        // Enter submits and Delete edits whatever field has the keyboard, so neither goes to a password field.
        if (key == Key.ENTER || key == Key.DEL) refusePasswordFocus()
        backends.firstAvailable().pressKey(key)
    }

    /**
     * Refuses when the field with the keyboard is a password field. Checked here on a fresh read of the screen, since
     * keys and text without an element go to the first backend in priority order, and not every backend looks.
     * When the screen cannot be read, the backend's own check stands.
     */
    private suspend fun refusePasswordFocus() {
        val elements =
            try {
                reader.snapshot(SnapshotOptions(maxElements = FOCUS_SEARCH_ELEMENTS, internal = true)).elements
            } catch (e: ToolException) {
                return
            }
        if (elements.any { it.password && it.focused }) throw passwordFieldRefused()
    }

    override suspend fun globalAction(action: GlobalAction) = backends.firstAvailable().globalAction(action)

    /** The input backend matching the reader that took [target]'s snapshot; throws STALE_ELEMENT for unknown elements. */
    private fun sameBackend(target: Target.OnElement): InputController? {
        reader.bounds(target)
        val owner = reader.ownerOf(target).backendId
        return backends.firstOrNull { it.backendId == owner && it.available.value }
    }

    private fun route(target: Target): Pair<InputController, Target> =
        when (target) {
            is Target.AtPoint -> {
                backends.firstAvailable() to target
            }

            is Target.OnElement -> {
                sameBackend(target)?.let { it to target }
                    ?: reader.bounds(target).let { backends.firstAvailable() to Target.AtPoint(it.centerX, it.centerY) }
            }
        }

    private companion object {
        const val FOCUS_SETTLE_MS = 400L
        const val FOCUS_SEARCH_ELEMENTS = 5_000
    }
}
