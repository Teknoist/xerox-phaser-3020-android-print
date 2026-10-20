#!/usr/bin/env python3
"""
SPL / QPDL Document Encoder for Xerox Phaser 3020 / Samsung M2020.
Encodes 1-bit monochrome raster pages into valid Port 9100 raw print streams.
"""

import sys
import os
import struct
import socket
from datetime import datetime
from PIL import Image, ImageDraw, ImageFont

from spl_protocol import (
    PJL_START,
    PJL_ENTER_QPDL,
    PJL_END,
    PAPER_TYPE_A4,
    PAPER_TYPE_LETTER,
    BAND_WIDTH_TABLE_600DPI,
    BAND_HEIGHT,
    QPDL_VERSION_3,
    SUBHEADER_SIGNATURE,
    ALGO_0X11,
    SIG_PAGE_HEADER,
    SIG_BAND_RECORD,
    SIG_PAGE_TRAILER,
    algo0x11_compress,
    convert_raster_to_band,
    calculate_spl_checksum,
)


class SplEncoder:
    def __init__(
        self,
        paper_type: int = PAPER_TYPE_A4,
        resolution: int = 600,
        copies: int = 1,
        job_name: str = "AndroidPrintJob",
        user_name: str = "AndroidUser",
    ):
        self.paper_type = paper_type
        self.resolution = resolution
        self.copies = copies
        self.job_name = job_name
        self.user_name = user_name

        # Paper dimensions in 300 DPI units (standard QPDL header requirement)
        # and full-resolution pixel dimensions at 600 DPI
        if paper_type == PAPER_TYPE_A4:
            # A4: 210mm x 297mm (8.27 x 11.69 inches)
            # In 300 DPI: width=2480, height=3508
            # In 600 DPI: width=4960, height=7016
            self.media_width_300 = 2480
            self.media_height_300 = 3508
            self.pixel_width = 4960
            self.pixel_height = 7016
            self.band_width_bytes = BAND_WIDTH_TABLE_600DPI[PAPER_TYPE_A4]  # 608 bytes (4864 px)
        else:
            # Letter: 8.5 x 11 inches
            # In 300 DPI: width=2550, height=3300
            # In 600 DPI: width=5100, height=6600
            self.media_width_300 = 2550
            self.media_height_300 = 3300
            self.pixel_width = 5100
            self.pixel_height = 6600
            self.band_width_bytes = BAND_WIDTH_TABLE_600DPI[PAPER_TYPE_LETTER]  # 640 bytes (5120 px)

        self.line_width_bytes = (self.pixel_width + 7) // 8
        self.band_height = BAND_HEIGHT  # 128 scanlines
        self.hard_margin_x_bytes = 13   # 12.5 pt margins = ~104 pixels = 13 bytes
        self.hard_margin_y_lines = 104  # 12.5 pt margins = ~104 scanlines

    def build_pjl_header(self) -> bytes:
        """
        Builds the standard PJL wrapper header for Xerox Phaser 3020.
        """
        now = datetime.now()
        date_str = now.strftime("%Y%m%d")

        pjl = bytearray()
        pjl.extend(PJL_START)
        pjl.extend(f'@PJL DEFAULT SERVICEDATE={date_str}\r\n'.encode('latin1'))
        pjl.extend(f'@PJL SET USERNAME="{self.user_name}"\r\n'.encode('latin1'))
        pjl.extend(f'@PJL SET JOBNAME="{self.job_name}"\r\n'.encode('latin1'))
        pjl.extend(b'@PJL SET PAPERTYPE=NORMAL\r\n')
        pjl.extend(b'@PJL SET ALTITUDE=LOW\r\n')
        pjl.extend(b'@PJL SET DENSITY=3\r\n')
        pjl.extend(b'@PJL SET RET=NORMAL\r\n')
        pjl.extend(b'@PJL SET DUPLEX=OFF\r\n')
        pjl.extend(PJL_ENTER_QPDL)
        return bytes(pjl)

    def build_pjl_footer(self) -> bytes:
        """
        Builds the closing PJL command.
        """
        return PJL_END

    def build_page_header(self) -> bytes:
        """
        Builds the 17-byte SPL Page Header (Signature 0x00).
        """
        res_unit = self.resolution // 100  # 6 for 600 DPI
        copies_hi = (self.copies >> 8) & 0xFF
        copies_lo = self.copies & 0xFF
        w_hi = (self.media_width_300 >> 8) & 0xFF
        w_lo = self.media_width_300 & 0xFF
        h_hi = (self.media_height_300 >> 8) & 0xFF
        h_lo = self.media_height_300 & 0xFF
        paper_source = 1  # Auto
        duplex = 1        # Simplex in QPDL v3 mono
        tumble = 0
        spl_ver = QPDL_VERSION_3  # 3
        num_planes = 1    # Monochrome
        
        header = bytes([
            SIG_PAGE_HEADER,   # 0x00: Page header signature
            res_unit,          # 0x01: Y-resolution / 100
            copies_hi,         # 0x02: Copies high
            copies_lo,         # 0x03: Copies low
            self.paper_type,   # 0x04: Paper type
            w_hi,              # 0x05: Media width high (300dpi)
            w_lo,              # 0x06: Media width low
            h_hi,              # 0x07: Media height high (300dpi)
            h_lo,              # 0x08: Media height low
            paper_source,      # 0x09: Paper source
            0x00,              # 0x0A: Reserved / unknownByte1
            duplex,            # 0x0B: Duplex
            tumble,            # 0x0C: Tumble
            0x00,              # 0x0D: Reserved / unknownByte2
            spl_ver,           # 0x0E: QPDL version
            num_planes,        # 0x0F: Number of color planes
            res_unit,          # 0x10: X-resolution / 100
        ])
        assert len(header) == 17
        return header

    def build_page_trailer(self) -> bytes:
        """
        Builds the 3-byte SPL Page Trailer (Signature 0x01).
        """
        return bytes([
            SIG_PAGE_TRAILER,
            (self.copies >> 8) & 0xFF,
            self.copies & 0xFF,
        ])

    def encode_band(self, band_index: int, raw_band: bytes) -> bytes:
        """
        Encodes a single band into an SPL Band Record (Signature 0x0C).
        Band record format:
          0x0C, band_number, width_hi, width_lo, height_hi, height_lo (6 bytes)
          compression_algo (0x11), data_size_32 (5 bytes)
          subheader: 0x09, 0xAB, 0xCD, 0xEF (4 bytes)
          compressed_data
          checksum_32 (4 bytes)
        """
        band_pixel_width = self.band_width_bytes * 8
        compressed_payload = algo0x11_compress(raw_band)

        # Subheader signature (4 bytes)
        subheader = struct.pack(">I", SUBHEADER_SIGNATURE)

        # The checksum covers the subheader (4 bytes) + compressed_payload
        chk_data = subheader + compressed_payload
        checksum = calculate_spl_checksum(chk_data)

        # data_size is length of (subheader + compressed_payload + 4 bytes checksum)
        data_size = len(subheader) + len(compressed_payload) + 4

        record = bytearray()
        # 1. Band Record Header (6 bytes)
        record.append(SIG_BAND_RECORD)
        record.append(band_index & 0xFF)
        record.extend(struct.pack(">H", band_pixel_width))
        record.extend(struct.pack(">H", self.band_height))

        # 2. Plane Header (5 bytes)
        record.append(ALGO_0X11)
        record.extend(struct.pack(">I", data_size))

        # 3. Subheader (4 bytes)
        record.extend(subheader)

        # 4. Compressed Payload
        record.extend(compressed_payload)

        # 5. Checksum (4 bytes)
        record.extend(struct.pack(">I", checksum))

        return bytes(record)

    def encode_page(self, page_bitmap: bytes) -> bytes:
        """
        Encodes a 1-bit monochrome page bitmap (0=white, 1=black, row-major) into SPL page stream.
        """
        stream = bytearray()
        stream.extend(self.build_page_header())

        # Calculate number of bands
        total_page_lines = len(page_bitmap) // self.line_width_bytes
        printable_lines = total_page_lines - self.hard_margin_y_lines
        num_bands = (printable_lines + self.band_height - 1) // self.band_height

        bands_emitted = 0
        for band_idx in range(num_bands):
            band_data = convert_raster_to_band(
                page_bitmap,
                band_index=band_idx,
                band_width_bytes=self.band_width_bytes,
                band_height=self.band_height,
                page_line_width_bytes=self.line_width_bytes,
                hard_margin_x_bytes=self.hard_margin_x_bytes,
                hard_margin_y_lines=self.hard_margin_y_lines,
            )
            if band_data is not None:
                stream.extend(self.encode_band(band_idx, band_data))
                bands_emitted += 1

        stream.extend(self.build_page_trailer())
        return bytes(stream)

    def encode_document(self, pages_bitmaps: List[bytes]) -> bytes:
        """
        Encodes multiple pages into a complete SPL print job.
        """
        doc = bytearray()
        doc.extend(self.build_pjl_header())
        for page_bitmap in pages_bitmaps:
            doc.extend(self.encode_page(page_bitmap))
        doc.extend(self.build_pjl_footer())
        return bytes(doc)


def create_sample_page_bitmap(text: str = "Xerox Phaser 3020 Test Page", page_num: int = 1) -> bytes:
    """
    Creates a sample 600 DPI test page bitmap with graphics, borders, and text.
    Returns 1-bit monochrome bytes (0=white, 1=black).
    """
    width = 4960
    height = 7016
    img = Image.new("1", (width, height), 0)  # 0 = white in our convention
    draw = ImageDraw.Draw(img)

    # Outer decorative frame
    draw.rectangle([100, 100, width - 100, height - 100], outline=1, width=8)
    draw.rectangle([120, 120, width - 120, height - 120], outline=1, width=3)

    # Title Banner
    draw.rectangle([200, 200, width - 200, 500], fill=1)
    # Banner text (white on black)
    # Using default font scaled
    draw.text((300, 300), "XEROX PHASER 3020 / SAMSUNG M2020", fill=0)

    # Main text details
    y = 700
    lines = [
        f"Document: Zero-Click Android Print Service Test Document",
        f"Page: {page_num}",
        f"Resolution: 600 x 600 DPI Monochrome Laser",
        f"Protocol: SPL / QPDL Version 3 (Raw Port 9100)",
        f"Compression: Algo 0x11 (FBXOR) Banded Compression",
        f"Generated: {datetime.now().isoformat()}",
        "",
        "Floyd-Steinberg Dithering and 1-Bit Raster Test Patterns Below:",
    ]
    for line in lines:
        draw.text((300, y), line, fill=1)
        y += 80

    # Draw test geometric patterns
    draw.ellipse([300, 1600, 1000, 2300], outline=1, width=10)
    draw.rectangle([1200, 1600, 1900, 2300], outline=1, width=10)

    # Dithered gradient simulation
    for x in range(300, 4000):
        density = (x - 300) / 3700.0
        for line_y in range(2500, 2800):
            if ((x * 17 + line_y * 31) % 100) < (density * 100):
                img.putpixel((x, line_y), 1)

    # Convert PIL 1-bit image to packed row-major bytearray
    # PIL "1" mode pack is MSB first: 1 bit per pixel
    return img.tobytes()


if __name__ == "__main__":
    encoder = SplEncoder(paper_type=PAPER_TYPE_A4)
    print("Generating sample 600 DPI page...")
    bitmap = create_sample_page_bitmap()
    print("Encoding SPL document...")
    spl_data = encoder.encode_document([bitmap])
    print(f"Generated SPL job: {len(spl_data)} bytes (uncompressed bitmap was {len(bitmap)} bytes)")

    out_path = "sample_print.spl"
    with open(out_path, "wb") as f:
        f.write(spl_data)
    print(f"Saved to {out_path}")
