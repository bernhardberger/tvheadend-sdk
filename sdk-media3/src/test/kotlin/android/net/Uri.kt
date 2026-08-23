package android.net

// Implements only the operations exercised by Media3's DataSpec and MediaItem.
class Uri private constructor(private val value: String) {
    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is Uri && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        @JvmField
        val EMPTY: Uri = Uri("")

        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)
    }
}
