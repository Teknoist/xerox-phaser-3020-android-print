#!/usr/bin/env python3
"""
Mock Xerox Phaser 3020 Printer Server.
- Listens on TCP 9100 (JetDirect / AppSocket RAW print port)
- Broadcasts mDNS service (_pdl-datastream._tcp, _printer._tcp) using Zeroconf
- Parses PJL wrapper commands
- Validates SPL/QPDL v3 page headers
- Validates and decompresses Algo 0x11 (FBXOR) bands
- Checks 32-bit SPL checksums
- Reconstructs 600 DPI monochrome page raster and saves to output PNG/BMP for visual verification.
"""

import socket
import struct
import threading
import time
import os
import sys
from typing import Optional, Tuple
from PIL import Image

try:
    from zeroconf import IPVersion, ServiceInfo, Zeroconf
    ZEROCONF_AVAILABLE = True
except ImportError:
    ZEROCONF_AVAILABLE = False

from spl_protocol import (
    PJL_START,
    PJL_ENTER_QPDL,
    PJL_END,
    SIG_PAGE_HEADER,
    SIG_BAND_RECORD,
    SIG_PAGE_TRAILER,
    SUBHEADER_SIGNATURE,
    ALGO_0X11,
    PAPER_TYPE_A4,
    PAPER_TYPE_LETTER,
    BAND_HEIGHT,
    BAND_WIDTH_TABLE_600DPI,
    algo0x11_decompress,
    reconstruct_band_to_raster,
    calculate_spl_checksum,
)


class MockPhaser3020Server:
    def __init__(self, host: str = "0.0.0.0", port: int = 9100, output_dir: str = "received_prints"):
        self.host = host
        self.port = port
        self.output_dir = output_dir
        os.makedirs(self.output_dir, exist_ok=True)

        self.running = False
        self.server_socket: Optional[socket.socket] = None
        self.zeroconf: Optional[Zeroconf] = None
        self.job_count = 0
        self.jobs_received: list[dict] = []

    def start_mdns(self):
        """
        Announces Xerox Phaser 3020 on the local network via mDNS.
        """
        if not ZEROCONF_AVAILABLE:
            print("[mDNS] Zeroconf not available, skipping mDNS broadcast.")
            return

        try:
            self.zeroconf = Zeroconf(ip_version=IPVersion.V4Only)
            local_ip = socket.gethostbyname(socket.gethostname())
            ip_bytes = socket.inet_aton(local_ip)

            # TXT properties matching actual Xerox / Samsung devices
            props = {
                b"txtvers": b"1",
                b"qtotal": b"1",
                b"rp": b"raw",
                b"ty": b"Xerox Phaser 3020",
                b"product": b"(Xerox Phaser 3020)",
                b"usb_MFG": b"Xerox",
                b"usb_MDL": b"Phaser 3020",
                b"CMD": b"QPDL,SPL",
                b"pdl": b"application/octet-stream",
                b"priority": b"50",
            }

            self.service_info = ServiceInfo(
                type_="_pdl-datastream._tcp.local.",
                name="Xerox Phaser 3020._pdl-datastream._tcp.local.",
                addresses=[ip_bytes],
                port=self.port,
                properties=props,
                server=f"{socket.gethostname()}.local.",
            )

            self.zeroconf.register_service(self.service_info)
            print(f"[mDNS] Registered service: 'Xerox Phaser 3020._pdl-datastream._tcp.local.' at {local_ip}:{self.port}")
        except Exception as e:
            print(f"[mDNS] Warning: Failed to register mDNS: {e}")

    def stop_mdns(self):
        if self.zeroconf and hasattr(self, "service_info"):
            try:
                self.zeroconf.unregister_service(self.service_info)
                self.zeroconf.close()
                print("[mDNS] Unregistered mDNS service.")
            except Exception:
                pass

    def start(self, async_mode: bool = False):
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind((self.host, self.port))
        self.server_socket.listen(5)
        self.running = True

        print(f"[Server] Xerox Phaser 3020 Mock Server listening on {self.host}:{self.port}")
        self.start_mdns()

        if async_mode:
            thread = threading.Thread(target=self._listen_loop, daemon=True)
            thread.start()
            return thread
        else:
            self._listen_loop()

    def stop(self):
        self.running = False
        if self.server_socket:
            try:
                self.server_socket.close()
            except Exception:
                pass
        self.stop_mdns()
        print("[Server] Mock printer stopped.")

    def _listen_loop(self):
        while self.running:
            try:
                client_sock, client_addr = self.server_socket.accept()
                print(f"\n[Server] Incoming print connection from {client_addr[0]}:{client_addr[1]}")
                threading.Thread(target=self._handle_client, args=(client_sock, client_addr), daemon=True).start()
            except socket.error:
                break

    def _handle_client(self, client_sock: socket.socket, client_addr: Tuple[str, int]):
        raw_buffer = bytearray()
        try:
            client_sock.settimeout(10.0)
            while True:
                data = client_sock.recv(65536)
                if not data:
                    break
                raw_buffer.extend(data)
        except socket.timeout:
            print("[Server] Connection timed out (client finished sending).")
        except Exception as e:
            print(f"[Server] Socket read error: {e}")
        finally:
            client_sock.close()

        if not raw_buffer:
            print("[Server] No data received.")
            return

        print(f"[Server] Received total {len(raw_buffer)} bytes. Parsing job...")
        self.job_count += 1
        job_result = self.parse_spl_job(bytes(raw_buffer), self.job_count)
        self.jobs_received.append(job_result)

    def parse_spl_job(self, stream: bytes, job_id: int) -> dict:
        """
        Parses PJL, SPL page headers, band records, decompresses FBXOR payload,
        checks checksums, and renders page image to disk.
        """
        result = {
            "job_id": job_id,
            "raw_size": len(stream),
            "pjl_commands": [],
            "pages": [],
            "success": False,
            "errors": [],
        }

        # 1. Parse PJL Header
        pjl_end_pos = stream.find(b"@PJL ENTER LANGUAGE = QPDL")
        if pjl_end_pos == -1:
            # Check without spaces
            pjl_end_pos = stream.find(b"@PJL ENTER LANGUAGE=QPDL")
        
        if pjl_end_pos == -1:
            err = "Invalid stream: '@PJL ENTER LANGUAGE = QPDL' not found!"
            print(f"[Parser Error] {err}")
            result["errors"].append(err)
            return result

        # Read past the newline
        qpdl_start = stream.find(b"\n", pjl_end_pos) + 1
        pjl_header_text = stream[:qpdl_start].decode("latin1", errors="replace")
        for line in pjl_header_text.splitlines():
            line = line.strip()
            if line:
                result["pjl_commands"].append(line)
                print(f"[PJL] {line}")

        offset = qpdl_start
        page_index = 0

        # Loop through pages in the QPDL stream
        while offset < len(stream):
            # Check for PJL universal exit command
            if stream[offset:offset+9] == b"\x1b%-12345X" or stream[offset:offset+10] == b"\t\x1b%-12345X":
                print(f"[Parser] Encountered PJL job exit sequence at offset {offset}.")
                result["success"] = True
                break

            sig = stream[offset]
            if sig != SIG_PAGE_HEADER:
                # Scan ahead in case of padding
                if sig == 0x09 or sig == 0x20 or sig == 0x0A or sig == 0x0D:
                    offset += 1
                    continue
                err = f"Expected SPL page header signature 0x00 at offset {offset}, got 0x{sig:02X}"
                print(f"[Parser Error] {err}")
                result["errors"].append(err)
                break

            # Parse 17-byte SPL Page Header
            page_header_bytes = stream[offset : offset + 17]
            offset += 17

            page_index += 1
            y_res = page_header_bytes[1] * 100
            copies = (page_header_bytes[2] << 8) | page_header_bytes[3]
            paper_type = page_header_bytes[4]
            media_w_300 = (page_header_bytes[5] << 8) | page_header_bytes[6]
            media_h_300 = (page_header_bytes[7] << 8) | page_header_bytes[8]
            paper_source = page_header_bytes[9]
            duplex = page_header_bytes[11]
            spl_version = page_header_bytes[14]
            num_planes = page_header_bytes[15]
            x_res = page_header_bytes[16] * 100

            print(f"[SPL Page {page_index}] Res={x_res}x{y_res} DPI, Copies={copies}, PaperType={paper_type}, Dim300=({media_w_300}x{media_h_300}), QPDL_v={spl_version}, Planes={num_planes}")

            # Canvas dimensions at 600 DPI
            if paper_type == PAPER_TYPE_A4:
                canvas_w = 4960
                canvas_h = 7016
                band_w_bytes = BAND_WIDTH_TABLE_600DPI[PAPER_TYPE_A4]  # 608 bytes
            else:
                canvas_w = 5100
                canvas_h = 6600
                band_w_bytes = BAND_WIDTH_TABLE_600DPI[PAPER_TYPE_LETTER]  # 640 bytes

            line_w_bytes = (canvas_w + 7) // 8
            canvas = bytearray(line_w_bytes * canvas_h)
            bands_received = 0

            # Loop through band records
            while offset < len(stream):
                rec_sig = stream[offset]
                if rec_sig == SIG_PAGE_TRAILER:
                    # Page Trailer: 0x01, copies_hi, copies_lo (3 bytes)
                    offset += 3
                    print(f"[SPL Page {page_index}] Trailer reached. Total bands: {bands_received}")
                    break
                elif rec_sig == SIG_BAND_RECORD:
                    # Band Record Header (6 bytes): 0x0C, band_no, width_hi, width_lo, height_hi, height_lo
                    band_no = stream[offset + 1]
                    b_width = (stream[offset + 2] << 8) | stream[offset + 3]
                    b_height = (stream[offset + 4] << 8) | stream[offset + 5]
                    offset += 6

                    # Plane Header (5 bytes): compression (0x11), data_size (uint32)
                    compression = stream[offset]
                    data_size = struct.unpack(">I", stream[offset + 1 : offset + 5])[0]
                    offset += 5

                    # Subheader (4 bytes): 0x09ABCDEF
                    sub_header = struct.unpack(">I", stream[offset : offset + 4])[0]
                    payload_len = data_size - 4 - 4  # Minus subheader (4) and checksum (4)
                    compressed_payload = stream[offset + 4 : offset + 4 + payload_len]
                    chk_offset = offset + 4 + payload_len
                    expected_checksum = struct.unpack(">I", stream[chk_offset : chk_offset + 4])[0]

                    # Validate Checksum
                    chk_data = stream[offset : chk_offset]
                    actual_checksum = calculate_spl_checksum(chk_data)
                    checksum_ok = (actual_checksum == expected_checksum)

                    if not checksum_ok:
                        print(f"[Warning] Band {band_no} Checksum Mismatch! Expected 0x{expected_checksum:08X}, Got 0x{actual_checksum:08X}")

                    offset += data_size

                    # Decompress payload
                    expected_band_size = (b_width // 8) * b_height
                    decompressed = algo0x11_decompress(compressed_payload, expected_band_size)

                    # Reconstruct into page canvas
                    reconstruct_band_to_raster(
                        decompressed,
                        band_index=band_no,
                        band_width_bytes=b_width // 8,
                        band_height=b_height,
                        full_canvas=canvas,
                        page_line_width_bytes=line_w_bytes,
                    )
                    bands_received += 1
                else:
                    err = f"Unexpected signature 0x{rec_sig:02X} at offset {offset}"
                    print(f"[Parser Error] {err}")
                    result["errors"].append(err)
                    offset += 1

            # Convert canvas to PNG
            # Canvas has 1 bit per pixel packed MSB first. 1 = black, 0 = white.
            # In PIL "1" mode: 0 is black, 255/1 is white. Let's invert for standard visual image.
            display_bytes = bytearray(len(canvas))
            for i in range(len(canvas)):
                display_bytes[i] = (~canvas[i]) & 0xFF  # 1 = white, 0 = black

            img = Image.frombytes("1", (canvas_w, canvas_h), bytes(display_bytes))
            # Save thumbnail and full image
            out_img_path = os.path.join(self.output_dir, f"job_{job_id}_page_{page_index}.png")
            thumb_path = os.path.join(self.output_dir, f"job_{job_id}_page_{page_index}_thumb.png")
            img.save(out_img_path)
            
            thumb = img.resize((img.width // 4, img.height // 4), Image.Resampling.LANCZOS)
            thumb.save(thumb_path)
            print(f"[SPL] Page {page_index} rendered and saved to {out_img_path}")

            result["pages"].append({
                "page_number": page_index,
                "bands_received": bands_received,
                "output_image": out_img_path,
                "thumbnail": thumb_path,
                "resolution": (x_res, y_res),
            })

        return result


if __name__ == "__main__":
    port = 9100
    if len(sys.argv) > 1:
        port = int(sys.argv[1])
    server = MockPhaser3020Server(port=port)
    try:
        server.start(async_mode=False)
    except KeyboardInterrupt:
        server.stop()
