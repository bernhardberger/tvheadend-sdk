package at.bernhardberger.tvheadend.sdk.core

import java.text.Collator

/**
 * Whether this channel has a user-visible channel number.
 *
 * TVHeadend reports `0` for a channel without a number, so only a major [Channel.number] greater
 * than zero is displayable or dialable.
 */
public val Channel.hasChannelNumber: Boolean
    get() = (number ?: 0L) > 0L

/**
 * Returns the standard TVHeadend channel order, comparing names with the default-locale collator.
 *
 * See [channelComparator] with an explicit collator for the ordering rules. The default locale is
 * captured when this function is called, so a retained comparator does not follow later locale
 * changes. Each call creates its own collator; the returned comparator is not safe for concurrent
 * use, so create one per sort or per thread.
 */
public fun channelComparator(): Comparator<Channel> = channelComparator(Collator.getInstance())

/**
 * Returns the standard TVHeadend channel order, comparing names with [collator].
 *
 * Channels with a [hasChannelNumber] number come first, ascending by major number, then minor
 * number with an absent minor first, then name. Unnumbered channels, including those TVHeadend
 * reports with number `0`, follow ascending by name. Missing or blank names sort after named
 * channels. [Channel.id] breaks remaining ties, so the order is total.
 *
 * The comparator retains [collator]: do not change the collator while the comparator is in use,
 * and share the comparator across threads only if [collator] supports concurrent comparisons.
 */
public fun channelComparator(collator: Collator): Comparator<Channel> {
    val names = Comparator<String?> { left, right ->
        val leftBlank = left.isNullOrBlank()
        val rightBlank = right.isNullOrBlank()
        when {
            leftBlank || rightBlank -> leftBlank.compareTo(rightBlank)
            else -> collator.compare(left, right)
        }
    }
    return compareBy<Channel, Long?>(nullsLast()) { it.visibleNumber }
        .thenBy(nullsFirst()) { channel -> channel.visibleNumber?.let { channel.numberMinor } }
        .thenBy(names) { it.name }
        .thenBy { it.id.value }
}

private val Channel.visibleNumber: Long?
    get() = number?.takeIf { it > 0L }
