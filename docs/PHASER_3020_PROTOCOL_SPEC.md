# Xerox Phaser 3020 / Samsung M2020 Driver Reverse Engineering Specification

## 1. Overview & Architecture
The **Xerox Phaser 3020** is an OEM-rebadged printer based on the **Samsung Xpress SL-M2020 / M2020w** hardware engine. It utilizes **Samsung Printer Language (SPL)**, specifically **QPDL Version 3** (Quadral Printer Description Language). It does not natively use PostScript or standard PCL; instead, it is a host-based raster printer that accepts compressed 1-bit monochrome banded bitmap streams over **Raw TCP Port 9100** (HP JetDirect / AppSocket protocol).

---

## 2. Packet Flow Overview

```
[TCP Connect to Port 9100]
   │
   ├── 1. PJL Job Header
   │      - Universal Exit Code (\x1b%-12345X@PJL)
   │      - Service Date, Username, Job Name
   │      - Density, Paper Type, Duplex setting
   │      - @PJL ENTER LANGUAGE = QPDL
   │
   ├── 2. Page Loop (For each page)
   │      ├── 2.1 SPL Page Header (17 bytes, Signature 0x00)
   │      │
   │      ├── 2.2 Band Records (Signature 0x0C, 128 scanlines each)
   │      │      ├── Band Header (6 bytes: band number, width, height)
   │      │      ├── Plane Header (5 bytes: algo 0x11, data size)
   │      │      ├── Sub-header (4 bytes: 0x09ABCDEF)
   │      │      ├── Compressed Payload (Algo 0x11 FBXOR)
   │      │      └── 32-bit Checksum (Sum of subheader + payload)
   │      │      *(Note: Completely empty/white bands are skipped)*
   │      │
   │      └── 2.3 SPL Page Trailer (3 bytes, Signature 0x01)
   │
   └── 3. PJL Job Footer
          - Universal Exit Code (\x1b%-12345X)
[TCP Close]
```

---

## 3. PJL Wrapper Commands

The print stream begins with the HP PJL (Printer Job Language) universal exit command and configuration directives:

```pjl
\x1b%-12345X@PJL\r\n
@PJL DEFAULT SERVICEDATE=YYYYMMDD\r\n
@PJL SET USERNAME="AndroidUser"\r\n
@PJL SET JOBNAME="Document"\r\n
@PJL SET PAPERTYPE=NORMAL\r\n
@PJL SET ALTITUDE=LOW\r\n
@PJL SET DENSITY=3\r\n
@PJL SET RET=NORMAL\r\n
@PJL SET DUPLEX=OFF\r\n
@PJL ENTER LANGUAGE = QPDL\r\n
```

### Closing PJL Sequence:
```pjl
\x1b%-12345X
```

---

## 4. SPL Page Header (17 Bytes)

Immediately after `@PJL ENTER LANGUAGE = QPDL\r\n`, each page begins with a 17-byte binary header:

| Offset | Field | Type | Description / Value |
|---|---|---|---|
| `0x00` | Signature | uint8 | `0x00` (SPL Page Header Magic) |
| `0x01` | Y Resolution Unit | uint8 | `yResolution / 100` (e.g. `6` for 600 DPI, `12` for 1200 DPI) |
| `0x02` | Copies High | uint8 | `copies >> 8` (typically `0x00`) |
| `0x03` | Copies Low | uint8 | `copies & 0xFF` (typically `0x01`) |
| `0x04` | Paper Type | uint8 | `0` = Letter, `1` = Legal, `2` = A4, `3` = Executive, `16` = A5 |
| `0x05` | Printable Width High | uint8 | Printable width in 300 DPI units (High Byte) |
| `0x06` | Printable Width Low | uint8 | Printable width in 300 DPI units (Low Byte) (e.g. `2480` for A4) |
| `0x07` | Printable Height High | uint8 | Printable height in 300 DPI units (High Byte) |
| `0x08` | Printable Height Low | uint8 | Printable height in 300 DPI units (Low Byte) (e.g. `3508` for A4) |
| `0x09` | Paper Source | uint8 | `1` = Auto, `2` = Manual Feed |
| `0x0A` | Reserved | uint8 | `0x00` |
| `0x0B` | Duplex | uint8 | `1` = Simplex (for QPDL v3 mono), `0` = Duplex |
| `0x0C` | Tumble | uint8 | `0` |
| `0x0D` | Reserved | uint8 | `0x00` |
| `0x0E` | QPDL Version | uint8 | `0x03` (Version 3 for Xerox Phaser 3020 / Samsung M2020) |
| `0x0F` | Color Planes | uint8 | `0x01` (1 Plane for Monochrome) |
| `0x10` | X Resolution Unit | uint8 | `xResolution / 100` (e.g. `6` for 600 DPI, `12` for 1200 DPI) |

Total length: **17 bytes** (`0x11`).

---

## 5. Band Dimensions and Column-Major Layout

The Phaser 3020 laser engine processes bands of height **128 scanlines**.
Crucially, the bandwidth in bytes must match the printer's internal ASIC buffer widths:

### Bandwidth Lookup Table (600 DPI):
- **A4 (`PaperType = 2`)**: `608` bytes per row (`4864` pixels).
- **Letter (`PaperType = 0`)**: `640` bytes per row (`5120` pixels).
- **Legal (`PaperType = 1`)**: `640` bytes per row (`5120` pixels).

### Column-Major Transposition:
Before compression, pixels are transposed from standard row-major order to column-major order:
$$\text{band}[x \times \text{bandHeight} + y]$$
where $x$ is the column index ($0 \le x < \text{bandWidthInBytes}$) and $y$ is the row within the band ($0 \le y < 128$).

### Byte Inversion:
In SPL raster buffers, `0xFF` represents unprinted paper background (no toner) and `0x00` represents laser discharge / toner deposit. The raster is bitwise inverted (`~byte`) before transmission.

### Blank Band Optimization:
If all bytes in a band contain zero black pixels, **the band is completely omitted** from the stream.

---

## 6. Band Record Structure (Signature 0x0C)

Each non-empty band is formatted as follows:

```
[0x0C] [Band#] [Width 16-bit BE] [Height 16-bit BE]  <-- Band Header (6 bytes)
[Algo 0x11] [DataSize 32-bit BE]                     <-- Plane Header (5 bytes)
[0x09 0xAB 0xCD 0xEF]                                <-- Sub-Header (4 bytes)
[Compressed Payload (FBXOR/Algo 0x11)]               <-- Payload
[Checksum 32-bit BE]                                 <-- 32-bit Sum Checksum (4 bytes)
```

1. **Band Header (6 bytes)**:
   - `0x0C`: Signature
   - `band_number`: 0-indexed byte
   - `width`: 16-bit big-endian pixel width (`bandWidthBytes * 8`, e.g. `4864`)
   - `height`: 16-bit big-endian scanlines (`128`)

2. **Plane Header (5 bytes)**:
   - `compression`: `0x11` (Algo 0x11 / FBXOR)
   - `data_size`: 32-bit big-endian length of `(4 bytes subheader + compressed payload + 4 bytes checksum)`.

3. **Sub-Header (4 bytes)**:
   - Magic constant: `0x09, 0xAB, 0xCD, 0xEF` (`0x09ABCDEF`).

4. **Checksum (4 bytes)**:
   - Simple 32-bit arithmetic sum:
     $$\text{Checksum} = \left(\sum_{b \in (\text{subheader} + \text{payload})} b\right) \pmod{2^{32}}$$
   - Written in 32-bit big-endian format.

---

## 7. Compression: Algo 0x11 (FBXOR)

Algo 0x11 is a fast dictionary-based LZ77 variant designed for monochrome printer engines.

### Bitstream Structure:
1. **Uncompressed Prefix Size**: 32-bit little-endian integer (typically up to `128` bytes).
2. **Dictionary Table**: 64 uint16 little-endian offset pointers (128 bytes total).
3. **Prefix Raw Data**: Uncompressed raw bytes corresponding to prefix size.
4. **Token Stream**:
   - **Match Token (2 bytes)**:
     - Byte 0: `0x80 | (length - 3) & 0x7F` (Bit 7 is `1`)
     - Byte 1: `(((length - 3) >> 1) & 0xC0) | (dict_idx & 0x3F)`
     - Maximum match length: 514 bytes.
   - **Literal Token (1 byte + N bytes)**:
     - Byte 0: `(count - 1)` (Bit 7 is `0`, count is $1 \le \text{count} \le 128$)
     - Followed immediately by `count` uncompressed raw bytes.

---

## 8. SPL Page Trailer (3 Bytes)

Each page is terminated by a 3-byte trailer:
- Offset `0x00`: `0x01` (Trailer signature)
- Offset `0x01`: `copies >> 8` (High byte)
- Offset `0x02`: `copies & 0xFF` (Low byte)

---

## 9. Network Port & Discovery
- **Port**: TCP `9100` (Raw AppSocket / JetDirect stream).
- **mDNS Service Types**: `_pdl-datastream._tcp.local.`, `_printer._tcp.local.`.
- **Wi-Fi Direct Default Gateway IPs**: `192.168.32.1` or `192.168.49.1`.
