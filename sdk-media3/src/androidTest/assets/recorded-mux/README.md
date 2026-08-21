# Recorded mux fixtures

This directory contains a redacted subset of a live HTSP capture used only by
Android instrumentation tests. Channel and stream identifiers were replaced by
local ordinals; endpoints, credentials, subscription identifiers, programme
metadata, and raw server messages are not present.

The fixture matrix covers H.264, MPEG-2 video, MPEG-2 audio, and AC-3 streams
available on the designated server. Each manifest packet retains its original
payload boundary, PTS, DTS, duration, frame type, size, and SHA-256 digest.
