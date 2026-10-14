package at.bernhardberger.tvheadend.sdk.consumer

import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.testing.FakeTvheadendSession
import kotlin.time.Instant

// Deliberately no opt-in: ordinary 0.27 construction and observation remain stable.
// This bridge also lets Java exercise create, whose EventId value-class parameter
// has always given it a JVM-mangled name that Java source cannot call directly.
public fun stableEpgEvent(start: Instant, stop: Instant): EpgEvent = EpgEvent.create(
    id = EventId(1), start = start, stop = stop, title = "Programme",
)

public fun stablePositionalEpgEvent(start: Instant, stop: Instant): EpgEvent = EpgEvent.create(
    EventId(1), null, start, stop, "Programme", null, null, null, null,
    null, null, null, null, null, null, null, null, null, null, null, null, null,
)

public fun stableEpgSnapshot(fake: FakeTvheadendSession, start: Instant, stop: Instant): EpgSnapshot {
    val event = stableEpgEvent(start, stop)
    val (id, channelId, eventStart, eventStop, title) = event
    check(id == EventId(1) && channelId == null && eventStart == start && eventStop == stop)
    check(title == "Programme" && event == stablePositionalEpgEvent(start, stop))
    check(event.hashCode() == stablePositionalEpgEvent(start, stop).hashCode())
    fake.epgRepository.scriptSearch(listOf(event))
    return EpgSnapshot.create(events = listOf(event))
}
