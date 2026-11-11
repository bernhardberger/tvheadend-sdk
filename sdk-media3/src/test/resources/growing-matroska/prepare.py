"""Turn a generated FFmpeg Matroska into a growing-shaped final fixture, without remuxing."""
from pathlib import Path
import sys

data = bytearray(Path(sys.argv[1]).read_bytes())


def vint(offset, mask):
    length = next(n for n in range(1, 9) if data[offset] & (1 << (8 - n)))
    value = int.from_bytes(data[offset:offset + length], "big")
    if mask:
        value &= (1 << (7 * length)) - 1
    return value, length


def element(offset):
    identity, id_length = vint(offset, False)
    size, size_length = vint(offset + id_length, True)
    start = offset + id_length + size_length
    return identity, start, start + size


def void(start, end):
    data[start:end] = b"\xec" + ((1 << 56) | (end - start - 9)).to_bytes(8, "big") + bytes(end - start - 9)


_, _, ebml_end = element(0)
_, segment_start, _ = element(ebml_end)
# FFmpeg reserves eight bytes for Segment size. Preserve all absolute offsets (including Cues).
assert segment_start - ebml_end == 12
data[ebml_end + 4:segment_start] = bytes.fromhex("01ffffffffffffff")
offset = segment_start
while offset < len(data):
    identity, start, end = element(offset)
    if identity == 0x114D9B74:  # SeekHead is populated only at close by Tvheadend.
        void(offset, end)
    elif identity == 0x1549A966:  # SegmentInfo: remove finalized Duration, preserve space.
        child = start
        while child < end:
            child_id, _, child_end = element(child)
            if child_id == 0x4489:
                void(child, child_end)
            child = child_end
    offset = end
Path(sys.argv[2]).write_bytes(data)
