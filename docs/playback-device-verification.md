# Playback device verification

Phase 4 playback acceptance was rerun on 2026-08-24 against TVHeadend
`4.3-2735~gfcd987f0b` using a TCL Smart TV Pro on Android API 31, firmware
increment `AS50`.

The exact Android instrumentation class
`PlaybackDeviceVerificationInstrumentationTest` completed one test with zero
failures and zero skips. The observed live stream contained H.264 video,
MPEG-2 audio, and teletext. Before each seek, SDK packet drops and server video
frame drops were zero, while the reported server queue depth was one packet.

The test accepted a five-second rewind and the bounded near-live return path.
Each seek produced a new timestamp anchor; the first post-seek audio and video
packet timestamps were within the two-second acceptance bound, and video
rendering continued. At the end of the timeshift phase, decoder counters
reported rendered audio output. The same run opened a completed recording
through the production Media3 source, applied its bounded resume position, and
observed resumed video and decoded audio.

The real-server profile was provisioned as a mode-0600 app-private one-use file.
The test consumed it before connecting, and its deletion was verified after the
run. No endpoint, credentials, recording path, channel identifier, or
subscription identifier is retained in this evidence.
