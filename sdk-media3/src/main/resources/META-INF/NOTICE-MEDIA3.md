# Media3 reader adaptation

`androidx.media3.extractor.ts.PrefixPreservingH264Reader` is adapted from
AndroidX Media3 1.11.0 `H264Reader`, copyright 2016 The Android Open Source Project,
under the Apache License, Version 2.0. The license is included as
`LICENSE-MEDIA3-APACHE-2.0`.

The adaptation retains the upstream slice parser and changes access-unit prefix
assignment, fragmented start-code bookkeeping, and end-of-input/reset handling.
It reuses Media3's package-private NAL buffering helpers. The SDK as a combined
work remains GPLv3.

Upstream source:
https://github.com/androidx/media/blob/1.11.0/libraries/extractor/src/main/java/androidx/media3/extractor/ts/H264Reader.java
