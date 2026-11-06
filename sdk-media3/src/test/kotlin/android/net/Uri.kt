package android.net

// Implements only the operations exercised by Media3's DataSpec, MediaItem and extractor factory.
class Uri private constructor(private val value: String) {
    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is Uri && other.value == value

    override fun hashCode(): Int = value.hashCode()

    // The no-arg extractor factory queries only EMPTY; other URI paths remain unsupported.
    fun getLastPathSegment(): String? {
        check(value.isEmpty())
        return null
    }

    companion object {
        @JvmField
        val EMPTY: Uri = Uri("")

        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)
    }
}
