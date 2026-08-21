package android.util

// Functional test replacement for the throwing Android local-unit-test stub.
class Pair<F, S>(
    @JvmField val first: F,
    @JvmField val second: S,
) {
    companion object {
        @JvmStatic
        fun <F, S> create(first: F, second: S): Pair<F, S> = Pair(first, second)
    }
}
