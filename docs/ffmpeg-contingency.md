# FFmpeg decoder contingency

`sdk-media3` keeps Media3's platform renderers first. Its renderer factory uses
`DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON`, so the bundled FFmpeg
audio renderer is considered only when the platform renderer cannot handle a
format.

The contingency was triggered by a repeatable AC-3 fixture that rendered on a
Sony Bravia 8 but reported `FORMAT_UNSUPPORTED_SUBTYPE` on the tested Nvidia
Shield route. It is built independently from the predecessor project.

## Exact sources and toolchain

- AndroidX Media3 tag: `1.11.0`
- AndroidX Media3 tag commit: `2bc207851df311340767e913931ca7b28cab1794`
- FFmpeg branch family: `release/6.0`, recommended by the Media3 1.11.0 module
- FFmpeg commit: `3f92512fd1fd6f5e6d6eb45a156c352835314d69`
- Android NDK: `27.0.12077973`
- Host: `linux-x86_64`
- Native API: `24`
- Enabled decoders: `ac3 eac3 mp3`
- ABIs: `armeabi-v7a arm64-v8a x86 x86_64`

The unmodified Media3 build script was run from
`libraries/decoder_ffmpeg/src/main/jni`:

```sh
./build_ffmpeg.sh \
  "/path/to/media-1.11.0/libraries/decoder_ffmpeg/src/main" \
  "/opt/android-sdk/ndk/27.0.12077973" \
  "linux-x86_64" \
  "24" \
  ac3 eac3 mp3
```

The extension was then assembled with:

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew :lib-decoder-ffmpeg:assembleRelease
```

## Shipped binary checksums

The checksums below are SHA-256 values. They are verified again by the SDK
build.

| Binary | SHA-256 |
|---|---|
| `media3-decoder-ffmpeg-1.11.0.jar` | `7288000961aee5aa9c9e72f895701d71b11b002d554a231d2031f7af52865ed6` |
| `arm64-v8a/libffmpegJNI.so` | `d46c1e296e5f897518e0d3f01e45dbf04158dc332b16fd616ffe4287ab4ba6d9` |
| `armeabi-v7a/libffmpegJNI.so` | `34db1ebf539808a81e0fb77d62b84d092588aefae816193175a75ebf4d89ae89` |
| `x86/libffmpegJNI.so` | `cc20fbab7596be4cc24874f76c9052560972537820878365d95fb7f0445dbbc8` |
| `x86_64/libffmpegJNI.so` | `1f25550a22a1de880de8260b6d5f1c881a062a21244e199e93c286a77062a845` |

The corresponding-source archive SHA-256 is
`9eeca8490f794574185986c0df7800d65ccca2980f57dc26b630a398581d7929`.

## Licensing and source

The Media3 wrapper is Apache-2.0. FFmpeg is configured without GPL or nonfree
components and reports LGPL-2.1-or-later. License texts are packaged in the
`sdk-media3` AAR. The Maven publication also includes the
`ffmpeg-sources.tar.xz` classifier containing the exact FFmpeg and Media3
wrapper source used for the binary, including the official build scripts.

FFmpeg is statically linked into `libffmpegJNI.so`. Distributors must preserve
the notices and corresponding source, permit reverse engineering for debugging
modifications, and provide the materials needed to rebuild and relink a
modified version. Codec patent obligations are separate from copyright
licensing and require product-specific review.
