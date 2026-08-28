package at.bernhardberger.tvheadend.sdk.core

import java.io.ByteArrayInputStream
import java.io.InputStream

/** Positive TVHeadend image-cache identifier. */
@JvmInline
public value class ArtworkId(public val value: Int) {
    init {
        require(value > 0) { "Artwork ID must be positive" }
    }

    override fun toString(): String = "ArtworkId(<redacted>)"
}

/** Safe classification of a failed authenticated artwork load. */
public enum class ArtworkFailure {
    CONNECTION_CHANGED,
    OBSERVATION_EXPIRED,
    ACCESS_DENIED,
    FILE_UNAVAILABLE,
    CONNECTION_LIMIT,
    TIMEOUT,
    NOT_SUPPORTED,
}

/** Immutable encoded artwork bytes returned by the authenticated session. */
public class ArtworkContent internal constructor(
    private val bytes: ByteArray,
) {
    /** Encoded byte count. */
    public val sizeBytes: Int
        get() = bytes.size

    /** Opens an independent stream over the encoded bytes. */
    public fun openStream(): InputStream = ByteArrayInputStream(bytes)

    override fun toString(): String = "ArtworkContent(<redacted>)"

    public companion object {
        /** Creates immutable encoded content by copying [bytes]. */
        public fun create(bytes: ByteArray): ArtworkContent = ArtworkContent(bytes.copyOf())
    }
}

/** Typed result of loading encoded artwork through the active session. */
public sealed interface ArtworkLoadResult {
    /** Encoded artwork is available for decoding. */
    public class Available(public val content: ArtworkContent) : ArtworkLoadResult {
        override fun toString(): String = "ArtworkLoadResult.Available(<redacted>)"
    }

    /** Artwork could not be loaded for a safe, classified reason. */
    public class Unavailable(public val failure: ArtworkFailure) : ArtworkLoadResult {
        override fun toString(): String = "ArtworkLoadResult.Unavailable(failure=$failure)"
    }
}

/**
 * Loads encoded image-cache artwork on the currently bound connection generation.
 *
 * TVHeadend requires recorder access for authenticated image-cache files and reports
 * [ArtworkFailure.ACCESS_DENIED] when that permission is unavailable.
 */
public interface ArtworkLoader {
    /** Loads [artworkId] without exposing an endpoint, credential, or protocol file selector. */
    public suspend fun loadArtwork(
        currentSession: CurrentSessionObservation,
        artworkId: ArtworkId,
    ): ArtworkLoadResult
}
