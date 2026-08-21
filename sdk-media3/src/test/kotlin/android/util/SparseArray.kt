package android.util

// Implements only the operations exercised by Media3's H264Reader.
class SparseArray<E> {
    private val values = sortedMapOf<Int, E>()

    fun append(key: Int, value: E) {
        values[key] = value
    }

    operator fun get(key: Int): E? = values[key]

    fun indexOfKey(key: Int): Int = values.keys.indexOf(key)
}
