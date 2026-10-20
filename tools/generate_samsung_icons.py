#!/usr/bin/env python3
"""
Generates Samsung One UI style app icons for Xerox Phaser 3020 / Samsung M2020 Print Service.
Outputs:
- res/drawable/ic_launcher_background.xml
- res/drawable/ic_launcher_foreground.xml
- res/mipmap-anydpi-v26/ic_launcher.xml
- res/mipmap-anydpi-v26/ic_launcher_round.xml
- res/mipmap-*/ic_launcher.png (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
- res/mipmap-*/ic_launcher_round.png
"""

import os
import math
from PIL import Image, ImageDraw, ImageFilter


def draw_samsung_oneui_icon(size: int, is_round: bool = False) -> Image.Image:
    """
    Renders a high-resolution Samsung One UI styled icon:
    - Squircle/round background with Samsung Blue gradient
    - Laser printer with paper feed, document lines, Wi-Fi arcs, cyan status LED
    - Soft depth and drop shadows
    """
    scale = size / 512.0
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    # Master high-res rendering at 1024x1024 for supersampling
    master_size = 1024
    master = Image.new("RGBA", (master_size, master_size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(master)

    # 1. Background Gradient Mask
    bg_mask = Image.new("L", (master_size, master_size), 0)
    bg_mask_draw = ImageDraw.Draw(bg_mask)

    padding = 64
    r = master_size - padding * 2
    if is_round:
        # Circular icon
        bg_mask_draw.ellipse([padding, padding, master_size - padding, master_size - padding], fill=255)
    else:
        # Samsung One UI Squircle (Superellipse with continuous curvature)
        # Using rounded rectangle with 220px radius at 1024x1024
        corner_radius = 210
        bg_mask_draw.rounded_rectangle(
            [padding, padding, master_size - padding, master_size - padding],
            radius=corner_radius,
            fill=255
        )

    # Samsung One UI Blue Gradient (#1677FF at top-left to #0047C4 at bottom-right)
    bg_grad = Image.new("RGBA", (master_size, master_size), (0, 0, 0, 0))
    for y in range(master_size):
        ratio = y / master_size
        # Interpolate between (26, 128, 255) and (0, 71, 204)
        r_c = int(26 + (0 - 26) * ratio)
        g_c = int(128 + (71 - 128) * ratio)
        b_c = int(255 + (204 - 255) * ratio)
        for x in range(master_size):
            bg_grad.putpixel((x, y), (r_c, g_c, b_c, 255))

    # Add soft radial highlight near top-center for Samsung glossy depth
    highlight = Image.new("RGBA", (master_size, master_size), (0, 0, 0, 0))
    hl_draw = ImageDraw.Draw(highlight)
    hl_center = (master_size // 2, padding + 150)
    hl_radius = 350
    for rad in range(hl_radius, 0, -1):
        alpha = int((1.0 - (rad / hl_radius)) * 40)
        hl_draw.ellipse(
            [hl_center[0] - rad, hl_center[1] - rad, hl_center[0] + rad, hl_center[1] + rad],
            fill=(255, 255, 255, alpha)
        )
    bg_grad.alpha_composite(highlight)

    # Apply background mask
    bg_layer = Image.new("RGBA", (master_size, master_size), (0, 0, 0, 0))
    bg_layer.paste(bg_grad, (0, 0), bg_mask)

    # 2. Drop Shadow for Printer
    shadow_layer = Image.new("RGBA", (master_size, master_size), (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow_layer)
    shadow_draw.rounded_rectangle([200, 680, 824, 750], radius=40, fill=(0, 20, 70, 110))
    shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(30))

    # 3. Printer Artwork
    printer_layer = Image.new("RGBA", (master_size, master_size), (0, 0, 0, 0))
    p_draw = ImageDraw.Draw(printer_layer)

    # A. Paper Sheet Feeding from Top
    paper_left = 310
    paper_top = 220
    paper_right = 714
    paper_bottom = 440
    # Paper body
    p_draw.rounded_rectangle(
        [paper_left, paper_top, paper_right, paper_bottom],
        radius=24,
        fill=(255, 255, 255, 250)
    )
    # Paper lines (text representation)
    line_color = (180, 210, 245, 220)
    p_draw.rounded_rectangle([360, 275, 664, 290], radius=7, fill=line_color)
    p_draw.rounded_rectangle([360, 315, 664, 330], radius=7, fill=line_color)
    p_draw.rounded_rectangle([360, 355, 550, 370], radius=7, fill=line_color)

    # B. Printer Main Body (Sleek Ice-White / Metallic Finish)
    body_left = 220
    body_top = 400
    body_right = 804
    body_bottom = 710
    # Base body
    p_draw.rounded_rectangle(
        [body_left, body_top, body_right, body_bottom],
        radius=64,
        fill=(248, 250, 254, 255)
    )

    # Body top bevel / highlight line
    p_draw.rounded_rectangle(
        [body_left + 4, body_top + 4, body_right - 4, body_top + 70],
        radius=58,
        fill=(255, 255, 255, 180)
    )

    # C. Laser Output Slot / Recessed Tray
    slot_left = 280
    slot_top = 480
    slot_right = 744
    slot_bottom = 570
    p_draw.rounded_rectangle(
        [slot_left, slot_top, slot_right, slot_bottom],
        radius=28,
        fill=(218, 228, 242, 255)
    )
    # Inner dark shadow in slot
    p_draw.rounded_rectangle(
        [slot_left + 4, slot_top + 4, slot_right - 4, slot_top + 32],
        radius=20,
        fill=(180, 198, 222, 255)
    )

    # Output Paper Lip (sliding out)
    p_draw.rounded_rectangle(
        [320, 520, 704, 610],
        radius=20,
        fill=(255, 255, 255, 255)
    )
    p_draw.rounded_rectangle([370, 550, 654, 562], radius=6, fill=(180, 210, 245, 200))
    p_draw.rounded_rectangle([370, 575, 530, 587], radius=6, fill=(180, 210, 245, 200))

    # D. Cyan / Teal Status LED (Samsung High-Tech Indicator)
    led_x = 300
    led_y = 650
    led_r = 14
    # LED soft glow
    for g_r in range(35, 10, -5):
        alpha = int((35 - g_r) * 6)
        p_draw.ellipse(
            [led_x - g_r, led_y - g_r, led_x + g_r, led_y + g_r],
            fill=(0, 230, 255, alpha)
        )
    # Core LED
    p_draw.ellipse(
        [led_x - led_r, led_y - led_r, led_x + led_r, led_y + led_r],
        fill=(0, 240, 255, 255)
    )

    # E. Sleek Wi-Fi Signal Arcs at Top Right
    wifi_center = (740, 250)
    # Outer arc
    p_draw.arc([wifi_center[0] - 110, wifi_center[1] - 110, wifi_center[0] + 110, wifi_center[1] + 110], start=210, end=270, fill=(255, 255, 255, 230), width=16)
    # Middle arc
    p_draw.arc([wifi_center[0] - 70, wifi_center[1] - 70, wifi_center[0] + 70, wifi_center[1] + 70], start=210, end=270, fill=(255, 255, 255, 240), width=16)
    # Inner dot / arc
    p_draw.arc([wifi_center[0] - 30, wifi_center[1] - 30, wifi_center[0] + 30, wifi_center[1] + 30], start=210, end=270, fill=(0, 235, 255, 255), width=16)
    p_draw.ellipse([wifi_center[0] - 12, wifi_center[1] - 12, wifi_center[0] + 12, wifi_center[1] + 12], fill=(0, 235, 255, 255))

    # Composite all master layers
    master.alpha_composite(bg_layer)
    master.alpha_composite(shadow_layer)
    master.alpha_composite(printer_layer)

    # Downsample with high-quality Lanczos resampling to requested size
    final = master.resize((size, size), Image.Resampling.LANCZOS)
    return final


def generate_all_icons(res_dir: str):
    # Density definitions for standard Android mipmaps
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    for folder, px in densities.items():
        out_folder = os.path.join(res_dir, folder)
        os.makedirs(out_folder, exist_ok=True)

        # Standard icon
        icon_img = draw_samsung_oneui_icon(px, is_round=False)
        icon_img.save(os.path.join(out_folder, "ic_launcher.png"), "PNG")

        # Round icon (One UI circular / squircle launcher mask)
        round_img = draw_samsung_oneui_icon(px, is_round=True)
        round_img.save(os.path.join(out_folder, "ic_launcher_round.png"), "PNG")
        print(f"Generated {folder}: {px}x{px} (standard & round)")

    # Also save a 512x512 Play Store / Release icon in release folder
    os.makedirs("release", exist_ok=True)
    hi_res = draw_samsung_oneui_icon(512, is_round=False)
    hi_res.save("release/icon_samsung_oneui_512.png", "PNG")
    print("Saved 512x512 hi-res icon to release/icon_samsung_oneui_512.png")


if __name__ == "__main__":
    res_path = "android/app/src/main/res"
    generate_all_icons(res_path)
