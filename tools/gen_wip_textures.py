#!/usr/bin/env python3
"""生成 2.0 WIP 缺失贴图（16×16 PNG，无第三方依赖）。

产出：
  textures/block/infinite_drive.png   驱动器（无限级）方块面
  textures/block/qianji.png           千机·阿比舒 方块面
  textures/item/catalyst_{basic,advanced,ultimate}.png  催化剂图标
"""
import os
import struct
import zlib

W = H = 16


def write_png(path, pixels):
    raw = b""
    for row in pixels:
        raw += b"\x00" + bytes(c for px in row for c in px)

    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def canvas(rgba=(0, 0, 0, 0)):
    return [[rgba for _ in range(W)] for _ in range(H)]


def px(c, x, y, color):
    if 0 <= x < W and 0 <= y < H:
        c[y][x] = color


def rect(c, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(c, x, y, color)


def ring(c, cx, cy, r, color, thick=1.0):
    for y in range(H):
        for x in range(W):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if r - thick <= d <= r:
                px(c, x, y, color)


def border(c, color):
    for i in range(W):
        px(c, i, 0, color)
        px(c, i, H - 1, color)
        px(c, 0, i, color)
        px(c, W - 1, i, color)


# ── 驱动器（无限级）：深色机壳 + 青色 ∞ ──
def infinite_drive():
    c = canvas()
    rect(c, 0, 0, 15, 15, (46, 48, 58, 255))          # 机壳
    rect(c, 1, 1, 14, 14, (32, 33, 41, 255))          # 内凹
    rect(c, 2, 2, 13, 13, (70, 72, 86, 255))          # 面板
    rect(c, 3, 3, 12, 12, (18, 19, 24, 255))          # 屏
    border(c, (17, 17, 21, 255))
    # ∞：两个相交的环
    ring(c, 5.8, 8.0, 2.6, (53, 214, 234, 255))
    ring(c, 10.2, 8.0, 2.6, (53, 214, 234, 255))
    px(c, 5, 5, (170, 245, 255, 255))                 # 高光
    px(c, 11, 10, (20, 130, 150, 255))                # 暗部
    return c


# ── 千机·阿比舒：黑金外壳 + 紫罗兰核心 ──
def qianji():
    c = canvas()
    rect(c, 0, 0, 15, 15, (20, 20, 27, 255))
    rect(c, 1, 1, 14, 14, (30, 29, 38, 255))
    border(c, (10, 10, 14, 255))
    # 金色角标
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px(c, x, y, (216, 176, 64, 255))
    # 紫罗兰核心（菱形）
    spans = {4: (7, 8), 5: (6, 9), 6: (5, 10), 7: (4, 11),
             8: (5, 10), 9: (6, 9), 10: (7, 8)}
    for y, (x0, x1) in spans.items():
        for x in range(x0, x1 + 1):
            c[y][x] = (110, 63, 208, 255)
    rect(c, 6, 6, 9, 9, (208, 160, 255, 255))
    px(c, 7, 7, (245, 230, 255, 255))
    px(c, 8, 8, (245, 230, 255, 255))
    # 黑核
    rect(c, 7, 7, 8, 8, (24, 16, 44, 255))
    px(c, 7, 7, (232, 220, 255, 255))
    return c


# ── 催化剂：晶体图标（三档配色）──
def catalyst(base, light, dark):
    c = canvas()
    outline = (12, 12, 18, 255)
    # 菱形晶体本体：行 2..13
    spans = {2: (7, 8), 3: (6, 9), 4: (6, 9), 5: (5, 10), 6: (5, 10),
             7: (4, 11), 8: (4, 11), 9: (5, 10), 10: (5, 10),
             11: (6, 9), 12: (6, 9), 13: (7, 8)}
    for y, (x0, x1) in spans.items():
        for x in range(x0, x1 + 1):
            c[y][x] = base
    # 描边
    for y, (x0, x1) in spans.items():
        px(c, x0 - 1, y, outline)
        px(c, x1 + 1, y, outline)
    for x in range(6, 10):
        px(c, x, 1, outline)
        px(c, x, 14, outline)
    # 高光 / 暗部
    for (x, y) in ((6, 5), (6, 6), (5, 6), (7, 4), (7, 5), (8, 4)):
        px(c, x, y, light)
    for (x, y) in ((9, 10), (10, 10), (9, 9), (8, 12), (9, 12)):
        px(c, x, y, dark)
    # 内核
    rect(c, 7, 7, 8, 8, light)
    return c


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src/main/resources/assets/ae2addon/textures")

write_png(os.path.join(RES, "block/infinite_drive.png"), infinite_drive())
write_png(os.path.join(RES, "block/qianji.png"), qianji())
write_png(os.path.join(RES, "item/catalyst_basic.png"),
          catalyst((94, 224, 122, 255), (196, 255, 208, 255), (28, 122, 52, 255)))
write_png(os.path.join(RES, "item/catalyst_advanced.png"),
          catalyst((240, 160, 48, 255), (255, 226, 168, 255), (150, 82, 12, 255)))
write_png(os.path.join(RES, "item/catalyst_ultimate.png"),
          catalyst((192, 96, 240, 255), (238, 200, 255, 255), (98, 32, 150, 255)))
print("WIP 贴图已生成 ->", RES)
