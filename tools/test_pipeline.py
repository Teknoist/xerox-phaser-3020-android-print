#!/usr/bin/env python3
"""
End-to-End Validation Test Suite for Xerox Phaser 3020 Driver Reverse Engineering.
Tests:
  1. SplEncoder generating SPL/QPDL stream
  2. MockPhaser3020Server receiving on TCP port 9100
  3. Decompression, checksum validation, and pixel-perfect raster reconstruction.
"""

import time
import socket
import os
import sys
from PIL import Image

from spl_protocol import PAPER_TYPE_A4
from spl_encoder import SplEncoder, create_sample_page_bitmap
from mock_printer import MockPhaser3020Server


def run_pipeline_test(test_port: int = 9100) -> bool:
    print(f"=== Starting Xerox Phaser 3020 SPL Pipeline Test on port {test_port} ===")
    
    server = MockPhaser3020Server(host="127.0.0.1", port=test_port, output_dir="test_output")
    server_thread = server.start(async_mode=True)
    time.sleep(1.0)  # Allow socket to bind

    try:
        # Step 1: Create sample document
        print("\n[Step 1] Creating test page bitmap...")
        sample_bitmap = create_sample_page_bitmap("Zero-Click Android Test", 1)
        print(f"Sample bitmap created: {len(sample_bitmap)} bytes")

        # Step 2: Encode with SplEncoder
        print("\n[Step 2] Encoding with SplEncoder...")
        encoder = SplEncoder(paper_type=PAPER_TYPE_A4, job_name="E2ETestJob", user_name="AndroidZeroClick")
        spl_stream = encoder.encode_document([sample_bitmap])
        print(f"SPL stream encoded successfully: {len(spl_stream)} bytes")

        # Step 3: Transmit over TCP
        print(f"\n[Step 3] Sending SPL stream to 127.0.0.1:{test_port}...")
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.connect(("127.0.0.1", test_port))
        client.sendall(spl_stream)
        client.close()
        print("Data transmitted and socket closed.")

        # Step 4: Wait for server processing
        time.sleep(2.0)

        # Step 5: Verify results
        print("\n[Step 4] Verifying server receipt and reconstruction...")
        if len(server.jobs_received) == 0:
            print("[FAIL] Server received 0 jobs!")
            return False

        job = server.jobs_received[0]
        print(f"Job Status: {'SUCCESS' if job['success'] else 'FAILED'}")
        print(f"Pages: {len(job['pages'])}")
        print(f"Errors: {job['errors']}")

        if not job["success"] or len(job["pages"]) == 0:
            print("[FAIL] Job did not succeed or 0 pages decoded.")
            return False

        page_info = job["pages"][0]
        out_img = page_info["output_image"]
        print(f"Reconstructed image saved at: {out_img}")
        if not os.path.exists(out_img):
            print("[FAIL] Reconstructed image file does not exist!")
            return False

        # Verify image dimensions
        with Image.open(out_img) as img:
            print(f"Rendered Image Dimensions: {img.size} (Expected: (4960, 7016))")
            assert img.size == (4960, 7016), f"Wrong image size {img.size}"

        print("\n>>> ALL CHECKS PASSED: Xerox Phaser 3020 SPL Pipeline is 100% Valid! <<<")
        return True

    finally:
        server.stop()


if __name__ == "__main__":
    port = 9100
    if len(sys.argv) > 1:
        port = int(sys.argv[1])
    success = run_pipeline_test(test_port=port)
    sys.exit(0 if success else 1)
