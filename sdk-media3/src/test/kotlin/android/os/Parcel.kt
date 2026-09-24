package android.os

// Implements only the operations exercised by Media3's CueEncoder. The stub Bundle retains no
// values, so every marshalled bundle is the same non-empty placeholder.
class Parcel private constructor() {
    fun writeBundle(bundle: Bundle?): Unit = Unit

    fun marshall(): ByteArray = byteArrayOf(0x42)

    fun recycle(): Unit = Unit

    companion object {
        @JvmStatic
        fun obtain(): Parcel = Parcel()
    }
}
