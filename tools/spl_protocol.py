"""
Xerox Phaser 3020 / Samsung M2020 SPL (Samsung Printer Language) / QPDL Protocol Definitions.
Based on reverse engineering of official ULD driver (rastertospl) and OpenPrinting splix driver.
"""

import struct
from typing import List, Tuple, Dict, Any, Optional

# --- PJL Protocol Constants ---
PJL_UNIVERSAL_EXIT = b"\x1b%-12345X"
PJL_START = b"\x1b%-12345X@PJL\r\n"
PJL_ENTER_QPDL = b"@PJL ENTER LANGUAGE = QPDL\r\n"
PJL_END = b"\x1b%-12345X"

# Paper Type Codes in SPL
PAPER_TYPE_LETTER = 0
PAPER_TYPE_LEGAL = 1
PAPER_TYPE_A4 = 2
PAPER_TYPE_EXECUTIVE = 3
PAPER_TYPE_LEDGER = 4
PAPER_TYPE_A3 = 5
PAPER_TYPE_ENV10 = 6
PAPER_TYPE_MONARCH = 7
PAPER_TYPE_C5 = 8
PAPER_TYPE_DL = 9
PAPER_TYPE_B4 = 10
PAPER_TYPE_B5 = 11
PAPER_TYPE_A5 = 16
PAPER_TYPE_A6 = 17

# Band widths in bytes for Samsung M2020 / Xerox Phaser 3020 series (from ULD lookup table)
# At 600 DPI (Standard) and 1200 DPI (High Resolution)
BAND_WIDTH_TABLE_600DPI = {
    PAPER_TYPE_LETTER: 640,  # 5120 pixels
    PAPER_TYPE_LEGAL: 640,   # 5120 pixels
    PAPER_TYPE_A4: 608,      # 4864 pixels
    PAPER_TYPE_EXECUTIVE: 544,
    PAPER_TYPE_A5: 416,
    PAPER_TYPE_A6: 288,
    PAPER_TYPE_B5: 544,
    PAPER_TYPE_DL: 320,
    PAPER_TYPE_C5: 480,
    PAPER_TYPE_ENV10: 288,
}

BAND_HEIGHT = 128  # Lines per band
QPDL_VERSION_3 = 3  # Phaser 3020 uses QPDL v3
SUBHEADER_SIGNATURE = 0x09ABCDEF
ALGO_0X11 = 0x11    # FBXOR compression algorithm

# SPL Header Signatures
SIG_PAGE_HEADER = 0x00
SIG_BAND_RECORD = 0x0C
SIG_PAGE_TRAILER = 0x01


def calculate_spl_checksum(data: bytes) -> int:
    """
    SPL 32-bit checksum: unsigned sum of all bytes in the subheader and compressed plane payload.
    """
    return sum(data) & 0xFFFFFFFF


def algo0x11_compress(data: bytes) -> bytes:
    """
    Compresses monochrome raster data using Samsung/Xerox Algo 0x11 (FBXOR).
    Format:
      - 4-byte uint32 little-endian: uncompressed prefix size (up to 128 bytes)
      - 64 uint16 little-endian dictionary offset pointers (128 bytes)
      - [uncompressed prefix bytes]
      - Token stream:
          - Match: 2 bytes
              Byte 0: 0x80 | (length - 3) & 0x7F
              Byte 1: (((length - 3) >> 1) & 0xC0) | (dict_idx & 0x3F)
          - Literal:
              Byte: (count - 1) & 0x7F
              Followed by [count] raw bytes
    """
    size = len(data)
    if size == 0:
        return b""

    # 1. Lookup most frequent repeat offsets (dictionary)
    sample_rate = 0x800
    occurs: Dict[int, int] = {}
    for i in range(sample_rate, size, sample_rate):
        b = data[i]
        for j in range(1, min(sample_rate, i + 1)):
            if data[i - j] == b:
                occurs[j] = occurs.get(j, 0) + 1

    sorted_offsets = sorted(occurs.items(), key=lambda x: (-x[1], x[0]))
    ptr_array = [off for off, _ in sorted_offsets[:64]]
    while len(ptr_array) < 64:
        ptr_array.append(len(ptr_array) + 1)
    if 1 not in ptr_array:
        ptr_array[63] = 1

    uncomp_size = min(0x80, max(ptr_array))
    if uncomp_size > size:
        uncomp_size = size

    out = bytearray()
    out.extend(struct.pack("<I", uncomp_size))
    for ptr in ptr_array:
        out.extend(struct.pack("<H", ptr))
    out.extend(data[:uncomp_size])

    r = uncomp_size
    raw_data = bytearray()

    def flush_raw():
        nonlocal raw_data
        while len(raw_data) > 0:
            chunk = raw_data[:128]
            out.append(len(chunk) - 1)
            out.extend(chunk)
            raw_data = raw_data[128:]

    while r < size:
        max_comp = min(0x202, size - r)
        best_len = 0
        best_ptr = 0
        if max_comp >= 3:
            for idx, ptr in enumerate(ptr_array):
                if ptr > r:
                    continue
                rtmp = r - ptr
                l = 0
                while l < max_comp and data[r + l] == data[rtmp + l]:
                    l += 1
                if l > best_len:
                    best_len = l
                    best_ptr = idx
                    if best_len == max_comp:
                        break

        if best_len >= 3:
            flush_raw()
            count = best_len - 3
            b0 = 0x80 | (count & 0x7F)
            b1 = ((count >> 1) & 0xC0) | (best_ptr & 0x3F)
            out.append(b0)
            out.append(b1)
            r += best_len
        else:
            raw_data.append(data[r])
            r += 1
            if len(raw_data) == 128:
                flush_raw()

    flush_raw()
    return bytes(out)


def algo0x11_decompress(comp: bytes, expected_size: int) -> bytes:
    """
    Decompresses an Algo 0x11 (FBXOR) stream back to raw column-major band data.
    """
    if len(comp) < 4 + 128:
        return b""
    uncomp_size = struct.unpack("<I", comp[:4])[0]
    ptr_array = [struct.unpack("<H", comp[4 + i * 2 : 4 + (i + 1) * 2])[0] for i in range(64)]
    r = 4 + 128
    out = bytearray(comp[r : r + uncomp_size])
    r += uncomp_size

    while r < len(comp) and len(out) < expected_size:
        b0 = comp[r]
        r += 1
        if b0 & 0x80:
            if r >= len(comp):
                break
            b1 = comp[r]
            r += 1
            count = (b0 & 0x7F) | ((b1 & 0xC0) << 1)
            best_len = count + 3
            best_ptr = b1 & 0x3F
            offset = ptr_array[best_ptr]
            start_pos = len(out) - offset
            for k in range(best_len):
                if start_pos + k < len(out):
                    out.append(out[start_pos + k])
                else:
                    out.append(0)
        else:
            lit_count = b0 + 1
            out.extend(comp[r : r + lit_count])
            r += lit_count

    return bytes(out[:expected_size])


def convert_raster_to_band(
    page_bitmap: bytes,
    band_index: int,
    band_width_bytes: int,
    band_height: int,
    page_line_width_bytes: int,
    hard_margin_x_bytes: int = 13,
    hard_margin_y_lines: int = 104,
) -> Optional[bytes]:
    """
    Extracts a band from a full-page row-major 1-bit bitmap,
    transposes to column-major format (band[x * bandHeight + y]),
    and checks if the band is completely blank (all white).
    Returns inverted column-major byte buffer, or None if band is empty.
    Note: In input bitmap: 0 = white, 1 = black.
    """
    band_size = band_width_bytes * band_height
    band = bytearray(band_size)

    start_y = hard_margin_y_lines + band_index * band_height
    total_page_lines = len(page_bitmap) // page_line_width_bytes

    has_black_pixels = False

    for y in range(band_height):
        page_y = start_y + y
        if page_y >= total_page_lines:
            break
        row_offset = page_y * page_line_width_bytes
        for x in range(band_width_bytes):
            page_x = hard_margin_x_bytes + x
            if page_x < page_line_width_bytes:
                val = page_bitmap[row_offset + page_x]
            else:
                val = 0
            if val != 0:
                has_black_pixels = True
            band[x * band_height + y] = val

    if not has_black_pixels:
        return None  # Empty band can be skipped entirely

    # Invert bytes: in SPL printer engine, 0xFF represents paper background
    inverted = bytearray(band_size)
    for i in range(band_size):
        inverted[i] = (~band[i]) & 0xFF

    return bytes(inverted)


def reconstruct_band_to_raster(
    band_data: bytes,
    band_index: int,
    band_width_bytes: int,
    band_height: int,
    full_canvas: bytearray,
    page_line_width_bytes: int,
    hard_margin_x_bytes: int = 13,
    hard_margin_y_lines: int = 104,
):
    """
    Reconstructs an SPL band (inverted column-major) back into a standard row-major 1-bit bitmap canvas.
    Canvas: 0 = white, 1 = black (255 when converted to display).
    """
    # First un-invert bytes
    uninverted = bytearray(len(band_data))
    for i in range(len(band_data)):
        uninverted[i] = (~band_data[i]) & 0xFF

    start_y = hard_margin_y_lines + band_index * band_height
    total_canvas_lines = len(full_canvas) // page_line_width_bytes

    for y in range(band_height):
        page_y = start_y + y
        if page_y >= total_canvas_lines:
            break
        row_offset = page_y * page_line_width_bytes
        for x in range(band_width_bytes):
            page_x = hard_margin_x_bytes + x
            if page_x < page_line_width_bytes:
                full_canvas[row_offset + page_x] = uninverted[x * band_height + y]
