#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 Cburnett 棋子 SVG 转成 Android 的 Java 源码。

为什么要有这个脚本
------------------
棋子造型用业界通用的 Cburnett 一套（Wikipedia / lichess 默认用的就是它）。
但 Android 2.3 上不能用 SVG，也不能靠系统字体的 Unicode 棋子字符
（Droid Sans 不覆盖 U+2654~U+265F，真机会显示成方框）。

所以做法是：**构建期**把 SVG 解析干净，生成一份只含绝对坐标的 Java 源码，
运行时用 android.graphics.Path 重放。这样：
  - 不带任何图片资源，也不带 .so（ARMv6 友好）
  - 矢量，任何格子尺寸都清晰
  - 运行时零解析开销，也不需要在 Java 里实现 SVG 语义

脚本处理了 SVG 里真实存在的这些花样（Cburnett 的 12 个文件写法并不统一）：
  - 样式来自组继承 / 表现属性 / style 属性，三者优先级不同
  - transform：translate / matrix / scale / rotate
  - 路径命令：M m L l H h V v C c S s Q q T t A a Z z（含 S/T 的隐含控制点反射）
  - 圆弧 A 转三次贝塞尔（SVG 规范 F.6.5）
  - <circle> 元素（皇后冠上的珠子）
  - fill-rule（nonzero / evenodd）、stroke-linecap、stroke-linejoin

用法
----
    python scripts/make_pieces.py <svg 目录> <输出 Java> [预览 PNG]

预览 PNG 用来人工核对造型有没有解析错。
"""

import math
import os
import re
import sys
import xml.etree.ElementTree as ET

SVG_NS = "{http://www.w3.org/2000/svg}"

# 顺序必须和 Chess.java 的类型常量一致（PAWN=1 KNIGHT=2 BISHOP=3 ROOK=4 QUEEN=5 KING=6），
# 这样 Java 侧直接用 (Chess.type(piece) - 1) * 2 + (黑?1:0) 就能索引。
PIECE_ORDER = ["pawn", "knight", "bishop", "rook", "queen", "king"]
FILE_SUFFIX = {"pawn": "p", "rook": "r", "knight": "n", "bishop": "b",
               "queen": "q", "king": "k"}


# --------------------------------------------------------------------- 颜色

def parse_color(v):
    """SVG 颜色 -> 0xAARRGGBB；'none'/空 -> None（表示不画）。"""
    if v is None:
        return None
    v = v.strip()
    if v == "" or v.lower() in ("none", "transparent"):
        return None
    if v.startswith("#"):
        h = v[1:]
        if len(h) == 3:
            h = h[0] * 2 + h[1] * 2 + h[2] * 2
        if len(h) == 6:
            return 0xFF000000 | int(h, 16)
        if len(h) == 8:
            return (int(h[6:8], 16) << 24) | int(h[0:6], 16)
    m = re.match(r"rgba?\(([^)]*)\)", v)
    if m:
        parts = [p.strip() for p in m.group(1).split(",")]
        if len(parts) >= 3:
            r, g, b = (int(float(p)) & 255 for p in parts[:3])
            a = 255
            if len(parts) == 4:
                a = int(float(parts[3]) * 255) & 255
            return (a << 24) | (r << 16) | (g << 8) | b
    named = {"white": 0xFFFFFFFF, "black": 0xFF000000, "red": 0xFFFF0000,
             "blue": 0xFF0000FF, "green": 0xFF008000}
    return named.get(v.lower())


# --------------------------------------------------------------------- 样式

PRESENTATION = ("fill", "stroke", "stroke-width", "fill-rule", "fill-opacity",
                "stroke-opacity", "stroke-linecap", "stroke-linejoin")

DEFAULT_STYLE = {
    "fill": "black", "stroke": "none", "stroke-width": "1",
    "fill-rule": "nonzero", "fill-opacity": "1", "stroke-opacity": "1",
    "stroke-linecap": "butt", "stroke-linejoin": "miter",
}


def parse_style_attr(s):
    out = {}
    if not s:
        return out
    for decl in s.split(";"):
        if ":" in decl:
            k, v = decl.split(":", 1)
            out[k.strip()] = v.strip()
    return out


def merge_style(parent, elem):
    """父样式 + 表现属性 + style 属性；后者覆盖前者（与 SVG 级联一致）。"""
    st = dict(parent)
    for key in PRESENTATION:
        v = elem.get(key)
        if v is not None:
            st[key] = v.strip()
    st.update(parse_style_attr(elem.get("style")))
    return st


# ------------------------------------------------------------------- 变换

def mat_identity():
    return (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def mat_mul(a, b):
    """a ∘ b：先应用 b，再应用 a。"""
    a0, a1, a2, a3, a4, a5 = a
    b0, b1, b2, b3, b4, b5 = b
    return (a0 * b0 + a2 * b1, a1 * b0 + a3 * b1,
            a0 * b2 + a2 * b3, a1 * b2 + a3 * b3,
            a0 * b4 + a2 * b5 + a4, a1 * b4 + a3 * b5 + a5)


def mat_apply(m, x, y):
    return (m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])


_NUM = r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"


def parse_transform(s):
    m = mat_identity()
    if not s:
        return m
    for name, argstr in re.findall(r"(\w+)\s*\(([^)]*)\)", s):
        args = [float(x) for x in re.findall(_NUM, argstr)]
        if name == "translate":
            tx = args[0] if args else 0.0
            ty = args[1] if len(args) > 1 else 0.0
            m = mat_mul(m, (1, 0, 0, 1, tx, ty))
        elif name == "scale":
            sx = args[0] if args else 1.0
            sy = args[1] if len(args) > 1 else sx
            m = mat_mul(m, (sx, 0, 0, sy, 0, 0))
        elif name == "matrix" and len(args) == 6:
            m = mat_mul(m, tuple(args))
        elif name == "rotate":
            ang = math.radians(args[0]) if args else 0.0
            c, sn = math.cos(ang), math.sin(ang)
            rot = (c, sn, -sn, c, 0, 0)
            if len(args) == 3:
                cx, cy = args[1], args[2]
                m = mat_mul(m, (1, 0, 0, 1, cx, cy))
                m = mat_mul(m, rot)
                m = mat_mul(m, (1, 0, 0, 1, -cx, -cy))
            else:
                m = mat_mul(m, rot)
    return m


# ----------------------------------------------------------------- 路径解析

TOKEN_RE = re.compile(r"([MmLlHhVvCcSsQqTtAaZz])|(" + _NUM + r")")

MOVE, LINE, CUBIC, QUAD, CLOSE = 0, 1, 2, 3, 4


class Cursor(object):
    """把一段 d 字符串解析成**已应用变换的绝对坐标**命令列表。

    命令编码（与 Java 侧 PieceArt.java 一致）：
        0 MOVE(x,y)  1 LINE(x,y)  2 CUBIC(x1,y1,x2,y2,x,y)  3 QUAD(x1,y1,x,y)  4 CLOSE()
    """

    def __init__(self, d, matrix):
        self.d = d
        self.m = matrix
        self.out = []
        self.cur = (0.0, 0.0)
        self.start = (0.0, 0.0)
        self.prev_cubic = None
        self.prev_quad = None

    def _emit(self, cmd, pts):
        mapped = []
        for i in range(0, len(pts), 2):
            x, y = mat_apply(self.m, pts[i], pts[i + 1])
            mapped.append(x)
            mapped.append(y)
        self.out.append(tuple([cmd] + mapped))

    def move(self, x, y):
        self.cur = self.start = (x, y)
        self._emit(MOVE, (x, y))

    def line(self, x, y):
        self.cur = (x, y)
        self._emit(LINE, (x, y))

    def cubic(self, x1, y1, x2, y2, x, y):
        self.cur = (x, y)
        self._emit(CUBIC, (x1, y1, x2, y2, x, y))

    def quad(self, x1, y1, x, y):
        self.cur = (x, y)
        self._emit(QUAD, (x1, y1, x, y))

    def close(self):
        self.cur = self.start
        self.out.append((CLOSE,))

    # --- 圆弧转三次贝塞尔（SVG 规范 F.6.5）

    def arc(self, rx, ry, rot_deg, large, sweep, x, y):
        x1, y1 = self.cur
        x2, y2 = x, y
        if rx == 0 or ry == 0:
            self.line(x2, y2)
            return
        rx, ry = abs(rx), abs(ry)
        phi = math.radians(rot_deg % 360.0)
        cosp, sinp = math.cos(phi), math.sin(phi)
        dx2, dy2 = (x1 - x2) / 2.0, (y1 - y2) / 2.0
        x1p = cosp * dx2 + sinp * dy2
        y1p = -sinp * dx2 + cosp * dy2
        lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if lam > 1:
            s = math.sqrt(lam)
            rx *= s
            ry *= s
        num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        co = math.sqrt(max(0.0, num / den)) if den else 0.0
        if large == sweep:
            co = -co
        cxp = co * rx * y1p / ry
        cyp = -co * ry * x1p / rx
        cx = cosp * cxp - sinp * cyp + (x1 + x2) / 2.0
        cy = sinp * cxp + cosp * cyp + (y1 + y2) / 2.0

        def angle(ux, uy, vx, vy):
            n = math.hypot(ux, uy) * math.hypot(vx, vy)
            if n == 0:
                return 0.0
            a = math.acos(max(-1.0, min(1.0, (ux * vx + uy * vy) / n)))
            return -a if (ux * vy - uy * vx) < 0 else a

        theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        dtheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                       (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if not sweep and dtheta > 0:
            dtheta -= 2 * math.pi
        elif sweep and dtheta < 0:
            dtheta += 2 * math.pi

        nseg = int(math.ceil(abs(dtheta) / (math.pi / 2.0))) or 1
        delta = dtheta / nseg
        t = 4.0 / 3.0 * math.tan(delta / 4.0)
        th = theta1
        px, py = x1, y1
        for _ in range(nseg):
            c1, s1 = math.cos(th), math.sin(th)
            th2 = th + delta
            c2, s2 = math.cos(th2), math.sin(th2)
            e2x = cx + rx * cosp * c2 - ry * sinp * s2
            e2y = cy + rx * sinp * c2 + ry * cosp * s2
            d1x = -rx * cosp * s1 - ry * sinp * c1
            d1y = -rx * sinp * s1 + ry * cosp * c1
            d2x = -rx * cosp * s2 - ry * sinp * c2
            d2y = -rx * sinp * s2 + ry * cosp * c2
            self.cubic(px + t * d1x, py + t * d1y,
                       e2x - t * d2x, e2y - t * d2y, e2x, e2y)
            px, py = e2x, e2y
            th = th2

    # --- 主循环

    def run(self):
        tokens = []
        for m in TOKEN_RE.finditer(self.d):
            tokens.append(m.group(1) if m.group(1) else float(m.group(2)))
        i = 0
        cmd = None
        while i < len(tokens):
            tk = tokens[i]
            if isinstance(tk, str):
                cmd = tk
                i += 1
                if cmd in "Zz":
                    self.close()
                    self.prev_cubic = self.prev_quad = None
                    continue
            elif cmd is None:
                break

            rel = cmd.islower()
            c = cmd.upper()
            ox, oy = self.cur if rel else (0.0, 0.0)

            if c == "M":
                self.move(ox + tokens[i], oy + tokens[i + 1])
                i += 2
                cmd = "l" if rel else "L"
                self.prev_cubic = self.prev_quad = None
            elif c == "L":
                self.line(ox + tokens[i], oy + tokens[i + 1])
                i += 2
                self.prev_cubic = self.prev_quad = None
            elif c == "H":
                self.line(ox + tokens[i], self.cur[1])
                i += 1
                self.prev_cubic = self.prev_quad = None
            elif c == "V":
                self.line(self.cur[0], oy + tokens[i])
                i += 1
                self.prev_cubic = self.prev_quad = None
            elif c == "C":
                a, b, cc, dd, e, f = tokens[i:i + 6]
                i += 6
                self.cubic(ox + a, oy + b, ox + cc, oy + dd, ox + e, oy + f)
                self.prev_cubic = (ox + cc, oy + dd)
                self.prev_quad = None
            elif c == "S":
                a, b, e, f = tokens[i:i + 4]
                i += 4
                if self.prev_cubic:
                    c1x = 2 * self.cur[0] - self.prev_cubic[0]
                    c1y = 2 * self.cur[1] - self.prev_cubic[1]
                else:
                    c1x, c1y = self.cur
                self.cubic(c1x, c1y, ox + a, oy + b, ox + e, oy + f)
                self.prev_cubic = (ox + a, oy + b)
                self.prev_quad = None
            elif c == "Q":
                a, b, e, f = tokens[i:i + 4]
                i += 4
                self.quad(ox + a, oy + b, ox + e, oy + f)
                self.prev_quad = (ox + a, oy + b)
                self.prev_cubic = None
            elif c == "T":
                e, f = tokens[i:i + 2]
                i += 2
                if self.prev_quad:
                    qx = 2 * self.cur[0] - self.prev_quad[0]
                    qy = 2 * self.cur[1] - self.prev_quad[1]
                else:
                    qx, qy = self.cur
                self.quad(qx, qy, ox + e, oy + f)
                self.prev_quad = (qx, qy)
                self.prev_cubic = None
            elif c == "A":
                rx, ry, rot, laf, sf, e, f = tokens[i:i + 7]
                i += 7
                self.arc(rx, ry, rot, int(laf) != 0, int(sf) != 0, ox + e, oy + f)
                self.prev_cubic = self.prev_quad = None
            else:
                i += 1
        return self.out


# ----------------------------------------------------------------- 形状收集

class Shape(object):
    __slots__ = ("ops", "fill", "stroke", "width", "evenodd", "cap", "join")

    def __init__(self, ops, style):
        self.ops = ops
        self.fill = parse_color(style.get("fill"))
        self.stroke = parse_color(style.get("stroke"))
        try:
            self.width = float(style.get("stroke-width", "1"))
        except ValueError:
            self.width = 1.0
        self.evenodd = style.get("fill-rule", "nonzero").lower() == "evenodd"
        self.cap = {"butt": "B", "round": "R", "square": "S"}.get(
            style.get("stroke-linecap", "butt").lower(), "B")
        self.join = {"miter": "M", "round": "R", "bevel": "B"}.get(
            style.get("stroke-linejoin", "miter").lower(), "M")
        if self.fill is not None and (self.fill >> 24) == 0:
            self.fill = None
        if self.stroke is not None and (self.width <= 0 or (self.stroke >> 24) == 0):
            self.stroke = None


def collect(elem, style, matrix, out):
    style = merge_style(style, elem)
    matrix = mat_mul(matrix, parse_transform(elem.get("transform")))
    tag = elem.tag.replace(SVG_NS, "")

    if tag in ("g", "svg", "a"):
        for child in elem:
            collect(child, style, matrix, out)
        return

    if tag == "path":
        d = elem.get("d")
        if d:
            ops = Cursor(d, matrix).run()
            if ops:
                out.append(Shape(ops, style))
    elif tag == "circle":
        cx = float(elem.get("cx", 0))
        cy = float(elem.get("cy", 0))
        r = float(elem.get("r", 0))
        if r > 0:
            k = 0.5522847498 * r
            cur = Cursor("", matrix)
            cur.move(cx + r, cy)
            cur.cubic(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
            cur.cubic(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
            cur.cubic(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
            cur.cubic(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
            cur.close()
            out.append(Shape(cur.out, style))
    elif tag in ("rect", "ellipse", "line", "polygon", "polyline", "text", "use",
                 "defs", "metadata", "title"):
        if tag not in ("defs", "metadata", "title"):
            raise SystemExit("暂不支持的图元 <%s>（Cburnett 里不该出现）" % tag)


def load_piece(path):
    shapes = []
    collect(ET.parse(path).getroot(), dict(DEFAULT_STYLE), mat_identity(), shapes)
    return shapes


def _bezier_extrema(p0, p1, p2, p3):
    """三次贝塞尔在 [0,1] 上的极值点（t 值），用于求**紧致**包围盒。

    直接用控制点当包围盒会明显偏大：王冠那段 C 的控制点到了 41.5，
    实际曲线只到约 39.8，差 6%。"""
    ts = [0.0, 1.0]
    a = -p0 + 3 * p1 - 3 * p2 + p3
    b = 2 * (p0 - 2 * p1 + p2)
    c = p1 - p0
    if abs(a) < 1e-12:
        if abs(b) > 1e-12:
            ts.append(-c / b)
    else:
        disc = b * b - 4 * a * c
        if disc >= 0:
            sq = math.sqrt(disc)
            ts.append((-b + sq) / (2 * a))
            ts.append((-b - sq) / (2 * a))
    return [t for t in ts if 0.0 <= t <= 1.0]


def _bezier_at(p0, p1, p2, p3, t):
    u = 1.0 - t
    return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3


def bounds(shapes):
    """紧致包围盒（含描边外扩），只用于确定裁剪框。"""
    xs, ys = [], []
    for sh in shapes:
        half = (sh.width / 2.0) if (sh.stroke is not None) else 0.0
        cur = None
        for op in sh.ops:
            cmd = op[0]
            if cmd == MOVE:
                cur = (op[1], op[2])
                pts = [cur]
            elif cmd == LINE:
                cur = (op[1], op[2])
                pts = [cur]
            elif cmd == CUBIC:
                p0, p1, p2, p3 = cur, (op[1], op[2]), (op[3], op[4]), (op[5], op[6])
                pts = [p3]
                for t in _bezier_extrema(p0[0], p1[0], p2[0], p3[0]):
                    pts.append((_bezier_at(p0[0], p1[0], p2[0], p3[0], t),
                                _bezier_at(p0[1], p1[1], p2[1], p3[1], t)))
                for t in _bezier_extrema(p0[1], p1[1], p2[1], p3[1]):
                    pts.append((_bezier_at(p0[0], p1[0], p2[0], p3[0], t),
                                _bezier_at(p0[1], p1[1], p2[1], p3[1], t)))
                cur = p3
            elif cmd == QUAD:
                p0, p1, p2 = cur, (op[1], op[2]), (op[3], op[4])
                pts = [p2]
                for t in _bezier_extrema(p0[0], p1[0], p1[0], p2[0]):
                    pts.append((_bezier_at(p0[0], p1[0], p1[0], p2[0], t),
                                _bezier_at(p0[1], p1[1], p1[1], p2[1], t)))
                for t in _bezier_extrema(p0[1], p1[1], p1[1], p2[1]):
                    pts.append((_bezier_at(p0[0], p1[0], p1[0], p2[0], t),
                                _bezier_at(p0[1], p1[1], p1[1], p2[1], t)))
                cur = p2
            else:
                continue
            for x, y in pts:
                xs.append(x - half)
                xs.append(x + half)
                ys.append(y - half)
                ys.append(y + half)
    return min(xs), min(ys), max(xs), max(ys)


# --------------------------------------------------------------- Java 生成

def _f(v):
    """紧凑浮点字面量：4 位小数足够（30px 格子上误差 < 0.003px）。"""
    s = ("%.4f" % v).rstrip("0").rstrip(".")
    if s in ("", "-0"):
        s = "0"
    if "." not in s:
        s += ".0"
    return s + "f"


def emit_java(pieces, crop, out_path, ops_per_block=1200):
    offsets, styles, ops_blocks = [], [], []
    cur_block, cur_len = [], 0
    first, count = [], []
    idx = 0        # 子路径序号
    float_at = 0   # 该子路径在 OPS 里的起点（**浮点下标**，不是子路径序号）

    for _, shapes in pieces:
        first.append(idx)
        for sh in shapes:
            offsets.append(float_at)
            flat = []
            for op in sh.ops:
                flat.extend(op)
            text = ", ".join(_f(v) for v in flat)
            if cur_len + len(flat) > ops_per_block and cur_block:
                ops_blocks.append(",\n        ".join(cur_block))
                cur_block, cur_len = [], 0
            cur_block.append(text)
            cur_len += len(flat)
            float_at += len(flat)
            fill = "0" if sh.fill is None else "0x%08X" % (sh.fill & 0xFFFFFFFF)
            stroke = "0" if sh.stroke is None else "0x%08X" % (sh.stroke & 0xFFFFFFFF)
            styles.append('"%s,%s,%s,%s,%s,%s"' % (
                fill, stroke, _f(sh.width), "E" if sh.evenodd else "W",
                sh.cap, sh.join))
            idx += 1
        count.append(idx - first[-1])
    if cur_block:
        ops_blocks.append(",\n        ".join(cur_block))
    assert len(offsets) == idx, "OFFSET 项数应等于子路径数"
    assert float_at == sum(len(flat) for _, shs in pieces for sh in shs
                           for flat in [[v for op in sh.ops for v in op]]), \
        "OPS 长度与 OFFSET 不一致"

    b = []
    b.append("    /** 每种棋子的子路径数量，索引 = 类型*2 + (黑?1:0)。 */")
    b.append("    private static final int[] COUNT = { %s };"
             % ", ".join(str(c) for c in count))
    b.append("    /** 每种棋子的起始子路径下标。 */")
    b.append("    private static final int[] FIRST = { %s };"
             % ", ".join(str(f) for f in first))
    b.append("    /** 每个子路径的命令流在 OPS 里的起点（浮点下标）。最后一项用 OPS.length 兜底。 */")
    b.append("    private static final int[] OFFSET = {")
    for i in range(0, len(offsets), 20):
        b.append("        " + ", ".join(str(v) for v in offsets[i:i + 20]) + ",")
    b.append("    };")
    b.append("    /** 每个子路径的样式：填充,描边,线宽,填充规则,线帽,线接。 */")
    b.append("    private static final String[] STYLE = {")
    for i in range(0, len(styles), 2):
        b.append("        " + ", ".join(styles[i:i + 2]) + ",")
    b.append("    };")
    for i, blk in enumerate(ops_blocks):
        b.append("")
        b.append("    private static float[] opsBlock%d() {" % i)
        b.append("        return new float[] {")
        b.append("        " + blk)
        b.append("        };")
        b.append("    }")
    b.append("")
    b.append("    /** 全部子路径的几何首尾相接：[命令, 参数...]。 */")
    b.append("    private static final float[] OPS = concat(")
    b.append("            new float[][] { %s });"
             % ", ".join("opsBlock%d()" % i for i in range(len(ops_blocks))))

    total = float_at

    src = TEMPLATE
    src = src.replace("@@BODY@@", "\n".join(b))
    src = src.replace("@@CROP@@", crop)
    src = src.replace("@@NPIECES@@", str(len(pieces)))
    src = src.replace("@@NSHAPES@@", str(len(styles)))
    src = src.replace("@@NFLOATS@@", str(total))
    src = src.replace("@@NBLOCKS@@", str(len(ops_blocks)))
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(src)
    return len(styles), total


TEMPLATE = u'''package org.lichessold.ui;

import android.graphics.Paint;
import android.graphics.Path;

/**
 * 棋子矢量造型数据 —— **本文件由 scripts/make_pieces.py 自动生成，请勿手改**。
 *
 * 造型取自 Cburnett 的国际象棋棋子（Wikipedia / lichess 默认使用的一套），
 * 授权 CC BY-SA 3.0；这里只做了坐标归一化和 SVG 语义展开。
 *
 * 为什么生成代码而不是直接放 SVG / PNG：
 *   - Android 2.3 跑不动 SVG，系统字体也没有 U+2654~U+265F 的棋子字形
 *   - 不引入图片资源，APK 不增体积，也不用按密度切图
 *   - 矢量，格子多大都清晰；纯 Java 运算，不带 .so（ARMv6 友好）
 *
 * 数据布局（全部扁平，避免生成大量小对象）：
 *   OPS     所有子路径的命令流首尾相接
 *   OFFSET  第 i 个子路径在 OPS 里的起点（浮点下标；第 i+1 项就是它的终点）
 *   STYLE   第 i 个子路径的样式
 *   FIRST / COUNT   第 p 枚棋子的子路径区间
 *
 * 规模：@@NPIECES@@ 枚棋子 / @@NSHAPES@@ 个子路径 / @@NFLOATS@@ 个浮点数
 *      / OPS 分 @@NBLOCKS@@ 块（单方法字节码不超限）
 * 坐标已归一化到 0..1；裁剪框（SVG 原始坐标）@@CROP@@。
 */
public final class PieceArt {

    /** 命令编码，与 make_pieces.py 的 Cursor 一致。 */
    public static final int MOVE = 0;
    public static final int LINE = 1;
    public static final int CUBIC = 2;
    public static final int QUAD = 3;
    public static final int CLOSE = 4;

@@BODY@@

    /** 样式缓存（解析一次，之后复用）。 */
    private static final int[] S_FILL = new int[STYLE.length];
    private static final int[] S_STROKE = new int[STYLE.length];
    private static final float[] S_WIDTH = new float[STYLE.length];
    private static final boolean[] S_EVENODD = new boolean[STYLE.length];
    private static final int[] S_CAP = new int[STYLE.length];
    private static final int[] S_JOIN = new int[STYLE.length];

    /** 每种棋子的 Path 缓存。索引 = 类型*2 + (黑?1:0)。 */
    private static final Path[][] PATHS = new Path[COUNT.length][];

    private static boolean prepared;

    private PieceArt() {
    }

    private static float[] concat(float[][] parts) {
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            n += parts[i].length;
        }
        float[] all = new float[n];
        int at = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, all, at, parts[i].length);
            at += parts[i].length;
        }
        return all;
    }

    private static void prepare() {
        if (prepared) {
            return;
        }
        for (int i = 0; i < STYLE.length; i++) {
            String[] f = STYLE[i].split(",");
            S_FILL[i] = f[0].equals("0")
                    ? 0 : (int) Long.parseLong(f[0].substring(2), 16);
            S_STROKE[i] = f[1].equals("0")
                    ? 0 : (int) Long.parseLong(f[1].substring(2), 16);
            S_WIDTH[i] = Float.parseFloat(f[2]);
            S_EVENODD[i] = f[3].charAt(0) == 'E';
            S_CAP[i] = capOf(f[4].charAt(0));
            S_JOIN[i] = joinOf(f[5].charAt(0));
        }
        for (int p = 0; p < COUNT.length; p++) {
            Path[] list = new Path[COUNT[p]];
            for (int s = 0; s < COUNT[p]; s++) {
                list[s] = build(FIRST[p] + s);
            }
            PATHS[p] = list;
        }
        prepared = true;
    }

    private static int capOf(char c) {
        if (c == 'R') {
            return Paint.Cap.ROUND.ordinal();
        }
        if (c == 'S') {
            return Paint.Cap.SQUARE.ordinal();
        }
        return Paint.Cap.BUTT.ordinal();
    }

    private static int joinOf(char c) {
        if (c == 'R') {
            return Paint.Join.ROUND.ordinal();
        }
        if (c == 'B') {
            return Paint.Join.BEVEL.ordinal();
        }
        return Paint.Join.MITER.ordinal();
    }

    private static Path build(int sub) {
        Path p = new Path();
        p.setFillType(S_EVENODD[sub] ? Path.FillType.EVEN_ODD : Path.FillType.WINDING);
        int i = OFFSET[sub];
        int end = (sub + 1 < OFFSET.length) ? OFFSET[sub + 1] : OPS.length;
        while (i < end) {
            int cmd = (int) OPS[i++];
            if (cmd == MOVE) {
                p.moveTo(OPS[i], OPS[i + 1]);
                i += 2;
            } else if (cmd == LINE) {
                p.lineTo(OPS[i], OPS[i + 1]);
                i += 2;
            } else if (cmd == CUBIC) {
                p.cubicTo(OPS[i], OPS[i + 1], OPS[i + 2], OPS[i + 3],
                        OPS[i + 4], OPS[i + 5]);
                i += 6;
            } else if (cmd == QUAD) {
                p.quadTo(OPS[i], OPS[i + 1], OPS[i + 2], OPS[i + 3]);
                i += 4;
            } else if (cmd == CLOSE) {
                p.close();
            } else {
                break;
            }
        }
        return p;
    }

    /** 第 p 枚棋子的子路径数量。 */
    public static int count(int p) {
        return COUNT[p];
    }

    public static Path path(int p, int sub) {
        prepare();
        return PATHS[p][sub];
    }

    public static int fillColor(int p, int sub) {
        prepare();
        return S_FILL[FIRST[p] + sub];
    }

    public static int strokeColor(int p, int sub) {
        prepare();
        return S_STROKE[FIRST[p] + sub];
    }

    /** 线宽，单位与归一化坐标一致（乘格子边长即像素）。 */
    public static float strokeWidth(int p, int sub) {
        prepare();
        return S_WIDTH[FIRST[p] + sub];
    }

    public static int cap(int p, int sub) {
        prepare();
        return S_CAP[FIRST[p] + sub];
    }

    public static int join(int p, int sub) {
        prepare();
        return S_JOIN[FIRST[p] + sub];
    }
}
'''


# ------------------------------------------------------------------- 预览

def render_preview(pieces, out_png, cell=52, ss=4):
    """用 Pillow 画出解析结果，纯人工核对造型有没有解析错。"""
    from PIL import Image, ImageDraw
    cols, rows = 6, 2
    W, H = cols * cell, rows * cell
    img = Image.new("RGB", (W * ss, H * ss), (240, 217, 181))
    d = ImageDraw.Draw(img)
    for i in range(cols):
        for j in range(rows):
            if (i + j) & 1:
                d.rectangle([i * cell * ss, j * cell * ss,
                             (i + 1) * cell * ss, (j + 1) * cell * ss],
                            fill=(181, 136, 99))
    for name, shapes in pieces:
        ptype = name.rsplit("_", 1)[0]
        ox = PIECE_ORDER.index(ptype) * cell * ss
        oy = (0 if name.endswith("_w") else 1) * cell * ss
        for sh in shapes:
            _draw_shape(d, sh.ops, sh.fill, sh.stroke, sh.width, ox, oy, cell * ss)
    img.resize((W, H), Image.LANCZOS).save(out_png)
    print("预览图（来自内存模型）: %s" % out_png)


def _draw_shape(d, ops, fill, stroke, width, ox, oy, scale):
    """把一条命令流画出来。ops 里坐标是 0..1，乘 scale 换算成像素。"""
    contours, cur = [], None
    for op in ops:
        pts = [(ox + op[k] * scale, oy + op[k + 1] * scale)
               for k in range(1, len(op), 2)]
        if op[0] == MOVE:
            cur = list(pts)
            contours.append([cur, False])
        elif op[0] == CLOSE:
            if cur is not None:
                contours[-1][1] = True
        elif cur is not None:
            cur.extend(pts)
    for pts, _ in contours:
        if fill is not None and len(pts) >= 3:
            d.polygon(pts, fill=_rgb(fill))
    for pts, closed in contours:
        if stroke is not None and len(pts) >= 2:
            line = pts + [pts[0]] if closed else pts
            d.line(line, fill=_rgb(stroke),
                   width=max(1, int(round(width * scale))), joint="curve")


def _grab_array(src, name):
    """从生成的 Java 里抠出某个数组的字面量内容。"""
    i = src.index(name)
    i = src.index("{", i)
    j = src.index("}", i)
    return src[i + 1:j]


def render_from_java(java_path, out_png, cell=52, ss=4):
    """**从生成的 Java 源码里**把数据读回来重画一遍。

    这是整条链路最后一环的验证：前面的预览图来自 Python 的内存模型，
    真正上手机的是 Java 里的字面量。如果代码生成写歪了（偏移错位、
    命令流拼接错、样式串解析错），只有这一步能发现。
    """
    with open(java_path, encoding="utf-8") as f:
        src = f.read()

    def ints(name):
        return [int(x) for x in re.findall(r"-?\d+", _grab_array(src, name))]

    def floats(name):
        # opsBlockN() 的返回数组；把 N 块拼起来
        out = []
        for blk in re.findall(r"private static float\[\] opsBlock\d+\(\) \{(.*?)\n    \}",
                              src, re.S):
            out.extend(float(x[:-1]) for x in
                       re.findall(_NUM + r"f", blk))
        return out

    count = ints("int[] COUNT")
    first = ints("int[] FIRST")
    offset = ints("int[] OFFSET")
    style = re.findall(r'"([^"]+)"', _grab_array(src, "String[] STYLE"))
    ops = floats("opsBlock")

    total = len(style)
    offset = offset + [len(ops)]

    from PIL import Image, ImageDraw
    cols, rows = 6, 2
    W, H = cols * cell, rows * cell
    img = Image.new("RGB", (W * ss, H * ss), (240, 217, 181))
    d = ImageDraw.Draw(img)
    for i in range(cols):
        for j in range(rows):
            if (i + j) & 1:
                d.rectangle([i * cell * ss, j * cell * ss,
                             (i + 1) * cell * ss, (j + 1) * cell * ss],
                            fill=(181, 136, 99))

    for p in range(len(count)):
        ox = (p // 2) * cell * ss
        oy = (p % 2) * cell * ss
        for s in range(count[p]):
            sub = first[p] + s
            fill_s, stroke_s, width_s = style[sub].split(",")[:3]
            fill = None if fill_s == "0" else int(fill_s, 16)
            stroke = None if stroke_s == "0" else int(stroke_s, 16)
            width = float(width_s[:-1]) if width_s.endswith("f") else float(width_s)
            # 命令流还原
            cmds = []
            i = offset[sub]
            end = offset[sub + 1]
            while i < end:
                c = int(ops[i])
                i += 1
                n = {0: 2, 1: 2, 2: 6, 3: 4, 4: 0}[c]
                cmds.append(tuple([c] + ops[i:i + n]))
                i += n
            _draw_shape(d, cmds, fill, stroke, width, ox, oy, cell * ss)

    img.resize((W, H), Image.LANCZOS).save(out_png)
    print("预览图（从生成的 Java 读回，%d 个子路径）: %s" % (total, out_png))


def _rgb(argb):
    return ((argb >> 16) & 255, (argb >> 8) & 255, argb & 255)


# --------------------------------------------------------------------- 主体

def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src_dir, out_java = sys.argv[1], sys.argv[2]
    preview = sys.argv[3] if len(sys.argv) > 3 else None

    raw = []
    for name in PIECE_ORDER:
        for color in ("l", "d"):
            f = os.path.join(src_dir, "Chess_%s%st45.svg" % (FILE_SUFFIX[name], color))
            raw.append((name, color, load_piece(f)))

    allsh = [sh for (_, _, shs) in raw for sh in shs]
    x0, y0, x1, y1 = bounds(allsh)
    # 裁剪框留一点边：棋子最宽处约占格子的 93%，和 lichess 的观感一致
    margin = 1.3
    side = max(x1 - x0, y1 - y0) + margin * 2
    ccx, ccy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    cx0, cy0 = ccx - side / 2.0, ccy - side / 2.0
    print("紧致包围盒: x %.2f..%.2f (%.2f)   y %.2f..%.2f (%.2f)"
          % (x0, x1, x1 - x0, y0, y1, y1 - y0))
    print("裁剪框: %.2f,%.2f 边长 %.2f -> 最宽的棋子占格子 %.1f%%"
          % (cx0, cy0, side, 100.0 * max(x1 - x0, y1 - y0) / side))

    for _, _, shs in raw:
        for sh in shs:
            for k, op in enumerate(sh.ops):
                pts = list(op[1:])
                for i in range(0, len(pts), 2):
                    pts[i] = (pts[i] - cx0) / side
                    pts[i + 1] = (pts[i + 1] - cy0) / side
                sh.ops[k] = tuple([op[0]] + pts)
            sh.width = sh.width / side

    pieces = [(n + ("_w" if c == "l" else "_b"), shs) for n, c, shs in raw]
    nshapes, nfloats = emit_java(pieces, "%.2f,%.2f,%.2f" % (cx0, cy0, side),
                                 out_java)
    print("已生成 %s：%d 枚棋子 / %d 个子路径 / %d 个浮点数"
          % (out_java, len(pieces), nshapes, nfloats))
    if preview:
        render_preview(pieces, preview)
        # 再从生成的 Java 里读回来画一遍 —— 上手机的是那份字面量，
        # 这一步才能证明代码生成没写歪
        render_from_java(out_java, preview.replace(".png", "-fromjava.png"))


if __name__ == "__main__":
    main()
