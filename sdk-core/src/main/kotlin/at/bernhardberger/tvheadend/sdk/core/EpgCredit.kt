package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi

/**
 * Open set of programme credit roles. Compare known values and retain a fallback using [raw]
 * for roles introduced by other grabbers or future servers; [of] preserves unknown values exactly.
 */
@ExperimentalTvheadendApi
public class EpgCreditRole private constructor(public val raw: String) {
    override fun equals(other: Any?): Boolean = other is EpgCreditRole && raw == other.raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = raw

    /** Shared XMLTV roles and lossless construction from raw role values. */
    public companion object {
        /** Cast member. */
        @JvmField
        public val ACTOR: EpgCreditRole = EpgCreditRole("actor")
        /** Director. */
        @JvmField
        public val DIRECTOR: EpgCreditRole = EpgCreditRole("director")
        /** Guest. */
        @JvmField
        public val GUEST: EpgCreditRole = EpgCreditRole("guest")
        /** Presenter. */
        @JvmField
        public val PRESENTER: EpgCreditRole = EpgCreditRole("presenter")
        /** Writer. */
        @JvmField
        public val WRITER: EpgCreditRole = EpgCreditRole("writer")

        /** Returns a shared known role or a value retaining [raw] without normalization. */
        @JvmStatic
        public fun of(raw: String): EpgCreditRole = when (raw) {
            "actor" -> ACTOR
            "director" -> DIRECTOR
            "guest" -> GUEST
            "presenter" -> PRESENTER
            "writer" -> WRITER
            else -> EpgCreditRole(raw)
        }
    }
}

/** One immutable cast or crew credit; rendering redacts the person's name. */
@ExperimentalTvheadendApi
public data class EpgCredit(public val name: String, public val role: EpgCreditRole) {
    override fun toString(): String = "EpgCredit(<redacted>)"
}
