package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import java.util.Collections

/** Ranked, explicitly linked DVR entries from one immutable [SessionObservation]. */
@ExperimentalTvheadendApi
public class DvrEventEntries private constructor(
    /** The same entry returned by the matching `SessionObservation.dvrEntryForEvent` overload. */
    public val selected: DvrEntry,
    otherActive: List<DvrEntry>,
) {
    /**
     * Remaining SCHEDULED, RECORDING or RECORDING_ERROR entries in selection rank order.
     * The selected entry is excluded; the list is immutable.
     */
    public val otherActive: List<DvrEntry> = Collections.unmodifiableList(otherActive.toList())

    override fun equals(other: Any?): Boolean =
        this === other || other is DvrEventEntries && selected == other.selected && otherActive == other.otherActive

    override fun hashCode(): Int = 31 * selected.hashCode() + otherActive.hashCode()

    override fun toString(): String = "DvrEventEntries(<redacted>)"

    internal companion object {
        @JvmSynthetic
        internal fun create(selected: DvrEntry, otherActive: List<DvrEntry>): DvrEventEntries =
            DvrEventEntries(selected, otherActive)
    }
}
