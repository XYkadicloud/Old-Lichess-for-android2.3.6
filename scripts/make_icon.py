#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 app/res/drawable/ic_launcher.png（48x48）。

不用 PIL：手写 PNG（IHDR + IDAT + IEND，truecolor 8bit，zlib 压缩）。
图案：深色底 + 中间 4x4 棋盘格（lichess 绿 / 米白），一眼能认出是棋盘。

幂等：生成结果和磁盘上已有的字节完全一致时**不写文件**。
这样重复构建不会无谓地改动这个受版本控制的资源，
在"不允许就地改写已有文件"的环境（沙箱、只读挂载）里也不会把构建卡住。
"""
import os
import struct
import sys
import zlib

SIZE = 48
BG = (0x26, 0x24, 0x21)
GREEN = (0x9C, 0xCB, 0x3B)
CREAM = (0xF0, 0xF0, 0xF0)

BOARD_CELLS = 4
BOARD_PX = 32
CELL = BOARD_PX // BOARD_CELLS
ORIGIN = (SIZE - BOARD_PX) // 2


def build_pixels():
    rows = []
    for y in range(SIZE):
        row = bytearray()
        row.append(0)  # filter type 0 (None)
        for x in range(SIZE):
            bx = x - ORIGIN
            by = y - ORIGIN
            if 0 <= bx < BOARD_PX and 0 <= by < BOARD_PX:
                cx = bx // CELL
                cy = by // CELL
                px = CREAM if (cx + cy) % 2 == 0 else GREEN
            else:
                px = BG
            row.extend(px)
        rows.append(bytes(row))
    return b"".join(rows)


def chunk(tag, data):
    out = struct.pack(">I", len(data)) + tag + data
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    return out + struct.pack(">I", crc)


def build_png():
    ihdr = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)
    idat = zlib.compress(build_pixels(), 9)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", ihdr)
    png += chunk(b"IDAT", idat)
    png += chunk(b"IEND", b"")
    return png


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "ic_launcher.png"
    png = build_png()

    try:
        with open(out_path, "rb") as f:
            if f.read() == png:
                print("icon unchanged: %s (%d bytes)" % (out_path, len(png)))
                return
    except OSError:
        pass

    d = os.path.dirname(os.path.abspath(out_path))
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with open(out_path, "wb") as f:
        f.write(png)
    print("icon written: %s (%d bytes)" % (out_path, len(png)))


if __name__ == "__main__":
    main()
