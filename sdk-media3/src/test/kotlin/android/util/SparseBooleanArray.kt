package android.util

// Implements only the operations exercised by Media3's TsExtractor.
class SparseBooleanArray {
    private val values = sortedMapOf<Int, Boolean>()

    fun put(key: Int, value: Boolean) {
        values[key] = value
    }

    operator fun get(key: Int): Boolean = get(key, false)

    fun get(key: Int, valueIfKeyNotFound: Boolean): Boolean = values[key] ?: valueIfKeyNotFound

    fun clear() {
        values.clear()
    }
}
