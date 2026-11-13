package app.pocketpilot.capability.api.screen

import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException

/**
 * The last few snapshots a reader produced, with whatever the backend needs to act on their elements
 * ([T]: live nodes for Accessibility, nothing for Shizuku). Element IDs from older snapshots are stale.
 */
class SnapshotCache<T>(
    private val keep: Int = DEFAULT_KEEP,
    /** Called with a payload when its snapshot leaves the cache, so live resources can be released. */
    private val onEvict: (T) -> Unit = {},
) {
    class Entry<T>(
        val snapshot: Snapshot,
        val payload: T,
        /** False for a snapshot the caller was never shown; see [SnapshotOptions.internal]. */
        val shown: Boolean = true,
    )

    private val entries = LinkedHashMap<String, Entry<T>>()

    @Synchronized
    fun put(
        snapshot: Snapshot,
        payload: T,
        shown: Boolean = true,
    ) {
        entries[snapshot.id] = Entry(snapshot, payload, shown)
        // Shown and internal snapshots are kept [keep] each, so internal ones never push out what the caller read.
        while (entries.values.count { it.shown == shown } > keep) {
            val oldest = entries.entries.first { it.value.shown == shown }
            entries.remove(oldest.key)
            onEvict(oldest.value.payload)
        }
    }

    @Synchronized
    fun contains(snapshotId: String): Boolean = snapshotId in entries

    /**
     * The entry [target] refers to: its snapshot, or the latest one the caller was shown when it names none. Element
     * IDs are positions in one snapshot, so the latest must be the one the caller read them from.
     */
    @Synchronized
    fun entryFor(target: Target.OnElement): Entry<T> =
        (if (target.snapshotId != null) entries[target.snapshotId] else entries.values.lastOrNull { it.shown })
            ?: throw stale("Snapshot ${target.snapshotId ?: "(none taken yet)"} is no longer current")

    fun element(target: Target.OnElement): Element {
        val entry = entryFor(target)
        return entry.snapshot.element(target.elementId)
            ?: throw stale("Element ${target.elementId} is not in snapshot ${entry.snapshot.id}")
    }

    companion object {
        const val DEFAULT_KEEP = 5

        fun stale(message: String) =
            ToolException(ToolErrorCode.STALE_ELEMENT, message, "Take a new screen.snapshot and use its element IDs")
    }
}
