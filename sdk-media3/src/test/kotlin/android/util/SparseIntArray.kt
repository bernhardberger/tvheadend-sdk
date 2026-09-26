package android.util

// Implements only the operations exercised by Media3's TsExtractor.
class SparseIntArray {
    private val values = sortedMapOf<Int, Int>()

    fun put(key: Int, value: Int) {
        values[key] = value
    }

    operator fun get(key: Int): Int = get(key, 0)

    fun get(key: Int, valueIfKeyNotFound: Int): Int = values[key] ?: valueIfKeyNotFound

    fun keyAt(index: Int): Int = values.keys.elementAt(index)

    fun valueAt(index: Int): Int = values.values.elementAt(index)

    fun size(): Int = values.size

    fun clear() {
        values.clear()
    }
}
