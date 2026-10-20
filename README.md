# Zero-Click Android Print Service & Reverse Engineered Driver for Xerox Phaser 3020 / Samsung M2020

An autonomous, frictionless Android print architecture and reverse-engineered driver for the **Xerox Phaser 3020** (and OEM twin **Samsung Xpress M2020 / M2020w**).

This project eliminates the need for obsolete, vendor-locked proprietary mobile print apps by providing:
1. **Driver Protocol Specification & Reconstructed Pipeline**: Reverse-engineered PJL wrapper, QPDL Version 3 header, column-major transposition, and Algo 0x11 (FBXOR) compression.
2. **Python Mock Printer Server**: Emulates a physical Xerox Phaser 3020 on TCP port 9100 with mDNS broadcasting, validates stream packets, decompresses bands, checks checksums, and renders page rasters to disk.
3. **Frictionless Android Application (Kotlin)**:
   - Native Android `PrintService` appearing directly in the Android System Print Dialog.
   - Transparent zero-click Activity (`Theme.NoDisplay`) for `Intent.ACTION_SEND` and `Intent.ACTION_VIEW` (`application/pdf`, `image/*`) printing with **0 taps**.
   - 3-Tier Auto-Discovery: mDNS (`_pdl-datastream._tcp`), parallel `/24` Port 9100 subnet probing, and Wi-Fi Direct (`DIRECT-xx-Phaser 3020`) fallback.
   - Hardware-accelerated 600 DPI `PdfRenderer` + Floyd-Steinberg dithering to 1-bit monochrome raster.
   - Status updates purely via Foreground Notifications with an actionable **"Retry"** button on failure.

---

## 1. Project Directory Structure

```
printer/
├── docs/
│   └── PHASER_3020_PROTOCOL_SPEC.md     # In-depth protocol & packet specification
├── tools/
│   ├── spl_protocol.py                  # Core protocol, Algo 0x11 encoder & decompressor
│   ├── spl_encoder.py                   # Standalone SPL stream generator (CLI & library)
│   ├── mock_printer.py                  # Mock Port 9100 TCP & mDNS printer server
│   └── test_pipeline.py                 # E2E validation test suite
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml      # PrintService, NoDisplay Activity, permissions
│   │   │   ├── res/                     # XML printservice config, notification drawables
│   │   │   └── java/com/teknoist/phaserprint/
│   │   │       ├── discovery/           # mDNS, Subnet Prober, Wi-Fi Direct fallback
│   │   │       ├── model/               # DiscoveredPrinter, PrintJobConfig
│   │   │       ├── network/             # Raw Port 9100 TCP streaming client
│   │   │       ├── protocol/            # SplConstants, Algo0x11Encoder, SplDocumentBuilder
│   │   │       ├── raster/              # DitherEngine (Floyd-Steinberg), PdfRasterizer, ImageRasterizer
│   │   │       ├── service/             # PhaserPrintService, ZeroClickPrintJobService
│   │   │       └── ui/                  # QuickPrintActivity (Zero-Click Share Sheet entry)
│   │   └── src/test/java/com/teknoist/phaserprint/
│   │       └── SplProtocolUnitTest.kt   # Unit tests for SPL headers, compression, empty band skipping
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── local.properties
└── README.md
```

---

## 2. Reverse Engineering Findings (Phase 1)

### Packet Anatomy:
```
[Universal Exit: \x1b%-12345X@PJL]
@PJL DEFAULT SERVICEDATE=YYYYMMDD
@PJL SET USERNAME="AndroidUser"
@PJL SET JOBNAME="Document"
@PJL SET PAPERTYPE=NORMAL
@PJL SET ALTITUDE=LOW
@PJL SET DENSITY=3
@PJL SET RET=NORMAL
@PJL SET DUPLEX=OFF
@PJL ENTER LANGUAGE = QPDL
--------------------------------------------------------------------------------
[Page Header: 17 Bytes]
  0x00 | Y_Res/100 | Copies(2B) | PaperType | Width300(2B) | Height300(2B) |
  Source(1) | 0x00 | Duplex(1) | 0x00 | 0x00 | QPDL_v(3) | Planes(1) | X_Res/100
--------------------------------------------------------------------------------
[Band Records: 128 scanlines each]
  - Band Header (6B): 0x0C | Band# | Width(2B) | Height(2B)
  - Plane Header (5B): Algo(0x11) | DataSize(4B)
  - Sub-Header (4B): 0x09 0xAB 0xCD 0xEF
  - Payload: FBXOR / Algo 0x11 compressed column-major inverted bytes
  - Checksum (4B): 32-bit arithmetic sum of (Subheader + Payload)
  *(Empty / all-white bands are completely omitted from the stream)*
--------------------------------------------------------------------------------
[Page Trailer: 3 Bytes]
  0x01 | Copies_Hi | Copies_Lo
--------------------------------------------------------------------------------
[Universal Exit: \x1b%-12345X]
```

### Key Technical Details:
- **Bandwidth Alignment**: For A4 at 600 DPI, bandwidth must be exactly `608` bytes (`4,864` pixels). For Letter, `640` bytes (`5,120` pixels).
- **Column-Major Transposition**: Bands are stored in column order: `band[x * bandHeight + y]` where `bandHeight = 128`.
- **Byte Inversion**: Bits are inverted (`~b`) before encoding: `0xFF` represents unprinted paper background, `0x00` represents laser exposure.
- **Algo 0x11 (FBXOR)**: A fast dictionary-based LZ77 variant with 64 dynamic offset pointers, 2-byte match tokens (`0x80 | length-3`, `dict_idx`), and uncompressed literal chunks up to 128 bytes. Reduces a 4.35 MB raw page to ~79 KB (55x compression ratio).

---

## 3. Testing with Mock Printer Server

Without physical hardware, validate the pipeline using the included Python mock server:

```bash
# Terminal 1: Launch Mock Printer Server (Port 9100 with mDNS broadcast)
python tools/mock_printer.py

# Terminal 2: Run End-to-End Pipeline Test
python tools/test_pipeline.py
```

The mock printer will:
1. Accept raw connections on TCP port 9100.
2. Broadcast mDNS discovery service `Xerox Phaser 3020._pdl-datastream._tcp.local.`.
3. Parse and validate all PJL and SPL headers.
4. Verify checksums and decompress all bands.
5. Reconstruct the 600 DPI bitmap and save output PNGs in `test_output/` or `received_prints/`.

---

## 4. Frictionless Android Architecture (Phase 2)

### A. Zero-Click Share Sheet Printing (`QuickPrintActivity`)
- Registered with `@android:style/Theme.NoDisplay` for `ACTION_SEND` and `ACTION_VIEW` (`application/pdf`, `image/*`).
- When user shares a PDF or photo from Chrome, WhatsApp, Drive, or Gallery, selecting "Zero-Click Phaser 3020 Print" immediately triggers the background foreground service and finishes in **0 ms**.
- **Zero user dialogs or confirmation taps required.**

### B. System Print Dialog Integration (`PhaserPrintService`)
- Implements Android's native `PrintService` and `PrinterDiscoverySession`.
- Exposes `Xerox Phaser 3020 (Wi-Fi/Direct)` in the standard Android system print dialog with full 600 DPI monochrome capabilities.

### C. 3-Tier Multi-Modal Discovery (`PrinterDiscoveryAggregator`)
1. **mDNS / NSD (`NsdPrinterDiscovery`)**: Discovers `_pdl-datastream._tcp` and `_ipp._tcp`.
2. **Subnet Port 9100 Prober (`SubnetPortProber`)**: If multicast is blocked by the router (AP isolation), probes `/24` subnet in parallel with 250ms socket timeouts.
3. **Wi-Fi Direct Fallback (`WifiDirectFallbackDiscovery`)**: If printer is not on LAN, discovers peer SSID `DIRECT-xx-Phaser 3020` via `WifiP2pManager`, connects, and routes to gateway IP `192.168.49.1` / `192.168.32.1`.

### D. 600 DPI Rasterization & Dithering Pipeline
- `PdfRasterizer`: Hardware-accelerated rendering at 600 DPI ($4960 \times 7016$ for A4) via Android's `PdfRenderer`.
- `DitherEngine`: High-performance Floyd-Steinberg error diffusion dithering converting 32-bit ARGB to 1-bit packed monochrome raster.
- `SplDocumentBuilder`: Slices into 128-line bands, transposes to column-major layout, skips empty bands, and streams Algo 0x11 compressed packets.

### E. Notification-Only UX
- Ongoing foreground notification displays real-time progress:
  - *"Discovering Xerox Phaser 3020 on Wi-Fi..."*
  - *"Rasterizing page 1 of 3 (600 DPI)..."*
  - *"Printing page 1 of 3 (45% sent)..."*
  - *"Print Completed Successfully"* (auto-dismissed).
- On communication failure, displays an actionable notification with a **"Retry"** button.
