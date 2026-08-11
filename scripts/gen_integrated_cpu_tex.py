#!/usr/bin/env python3
"""生成 integrated_cpu.png —— 紫晶核心风格，与 AE2 结构色（紫/品红混凝土）呼应。
纯标准库：zlib + struct 手写 PNG（无 PIL）。"""
import struct, zlib, math

W = H = 128
# 生成 RGB 像素：紫色核心 + 品红能量纹路 + 深色边缘
px = bytearray()

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

# 主色调
DARK = (18, 10, 32)        # 深紫黑
BASE = (46, 24, 84)        # 紫
CORE = (128, 64, 200)      # 亮紫
HOT = (230, 120, 255)      # 品红高光
EDGE = (10, 6, 20)         # 边缘暗色

cx = cy = W / 2.0
for y in range(H):
    for x in range(W):
        dx = (x - cx) / (W / 2.0)
        dy = (y - cy) / (H / 2.0)
        r = math.sqrt(dx * dx + dy * dy)

        # 基础：径向渐变 深紫→紫→亮紫
        if r < 0.55:
            t = r / 0.55
            color = lerp(HOT, CORE, t)
        elif r < 0.85:
            t = (r - 0.55) / 0.30
            color = lerp(CORE, BASE, t)
        else:
            t = min(1.0, (r - 0.85) / 0.30)
            color = lerp(BASE, EDGE, t)

        # 品红能量纹路：四条斜向光带（象征多线程/量子分裂）
        stripe = abs(dx + dy) % 0.5
        if stripe < 0.09 and r < 0.9:
            color = lerp(color, HOT, 0.55 * (1 - stripe / 0.09))

        # 核心高亮点
        glow = math.exp(-((dx * dx + dy * dy) * 3.2))
        color = lerp(color, (255, 180, 255), glow * 0.35)

        # 边缘一圈深色框（方块感）
        edge_dist = min(x, y, W - 1 - x, H - 1 - y)
        if edge_dist < 3:
            color = lerp(color, (0, 0, 0), 0.7)

        px.extend(color)

# 写 PNG（RGB, 8bit）
def chunk(tag, data):
    c = struct.pack('>I', len(data)) + tag + data
    c += struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    return c

raw = b''
stride = W * 3
for y in range(H):
    raw += b'\x00' + bytes(px[y*stride:(y+1)*stride])

png = b'\x89PNG\r\n\x1a\n'
png += chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(raw, 9))
png += chunk(b'IEND', b'')

with open('src/main/resources/assets/ae2addon/textures/block/integrated_cpu.png', 'wb') as f:
    f.write(png)
print('已生成 integrated_cpu.png (128x128)')
