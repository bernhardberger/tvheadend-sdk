package android.util

// Implements only the operations exercised by Media3's H264Reader and TsExtractor.
class SparseArray<E> {
    private val values = sortedMapOf<Int, E>()

    fun append(key: Int, value: E) {
        values[key] = value
    }

    fun put(key: Int, value: E) {
        values[key] = value
    }

    operator fun get(key: Int): E? = values[key]

    fun indexOfKey(key: Int): Int = values.keys.indexOf(key)

    fun keyAt(index: Int): Int = values.keys.elementAt(index)

    fun valueAt(index: Int): E = values.values.elementAt(index)

    fun size(): Int = values.size

    fun clear() {
        values.clear()
    }
}
