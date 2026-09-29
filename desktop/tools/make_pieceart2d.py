#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Android 版的 app/src/org/lichessold/ui/PieceArt.java 转成桌面版 PieceArt2D.java。

为什么用转换而不是重写：
  PieceArt.java 是 scripts/make_pieces.py 从 Cburnett SVG 生成的**数据文件**
  （12 枚棋子 / 70 个子路径 / 2058 个浮点数），造型数据本身与平台无关，
  只有 android.graphics.Path 这一层是 Android 专属。
  重新解析 SVG 会引入新的出错机会，直接搬数据最稳。

转换内容：
  - 原样搬运 COUNT / FIRST / OFFSET / OPS 四张表
  - 把 STYLE 的字符串（"0xFFFFFFFF,0xFF000000,0.0365f,E,R,M"）在生成期
    就展开成 6 张数值表，桌面版运行时不用再做字符串解析
  - android.graphics.Path -> java.awt.geom.Path2D.Float
  - android.graphics.Paint.Cap/Join -> 数值（0/1/2，与 BasicStroke 一致）

用法：
  python3 desktop/tools/make_pieceart2d.py
  产物：desktop/src/org/lichessold/desktop/PieceArt2D.java
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DESKTOP = os.path.dirname(HERE)
ROOT = os.path.dirname(DESKTOP)
SRC = os.path.join(ROOT, "app", "src", "org", "lichessold", "ui", "PieceArt.java")
OUT = os.path.join(DESKTOP, "src", "org", "lichessold", "desktop", "PieceArt2D.java")


def read_source():
    if not os.path.isfile(SRC):
        sys.exit("找不到源文件: %s" % SRC)
    with open(SRC, "r", encoding="utf-8") as f:
        return f.read()


def grab_ints(text, name):
    """抓 `private static final int[] NAME = { ... };` 里的整数。"""
    m = re.search(r"int\[\]\s+%s\s*=\s*\{(.*?)\};" % re.escape(name), text, re.S)
    if not m:
        sys.exit("抓不到 int[] %s" % name)
    return [int(x) for x in re.findall(r"-?\d+", m.group(1))]


def grab_strings(text, name):
    """抓 `private static final String[] NAME = { "a", "b", ... };`。"""
    m = re.search(r"String\[\]\s+%s\s*=\s*\{(.*?)\};" % re.escape(name), text, re.S)
    if not m:
        sys.exit("抓不到 String[] %s" % name)
    return re.findall(r'"([^"]*)"', m.group(1))


def grab_floats(text, method):
    """抓 `private static float[] METHOD() { return new float[] { ... }; }`。"""
    m = re.search(r"float\[\]\s+%s\s*\(\s*\)\s*\{\s*return new float\[\]\s*\{(.*?)\};" % re.escape(method),
                  text, re.S)
    if not m:
        sys.exit("抓不到 float[] %s()" % method)
    body = m.group(1)
    out = []
    for tok in body.replace("\n", " ").split(","):
        tok = tok.strip()
        if not tok:
            continue
        if not tok.endswith("f") and not tok.endswith("F"):
            tok += "f"
        out.append(tok)
    return out


def parse_style(style_list):
    """STYLE 的每一项 "填充,描边,线宽,填充规则,线帽,线接" -> 6 张表。"""
    fill, stroke, width, evenodd, cap, join = [], [], [], [], [], []
    for s in style_list:
        f = s.split(",")
        if len(f) != 6:
            sys.exit("STYLE 项格式不对: %r" % s)
        fill.append(0 if f[0] == "0" else int(f[0][2:], 16))
        stroke.append(0 if f[1] == "0" else int(f[1][2:], 16))
        # 保留 f 后缀：Java 里 "0.0365" 是 double，赋给 float 会报精度损失
        width.append(f[2].rstrip("fF") + "f")
        evenodd.append("true" if f[3][0] == "E" else "false")
        # 与 android.graphics.Paint.Cap / Join 的 ordinal 对齐，
        # 也就是 java.awt.BasicStroke 的 CAP_*/JOIN_* 常量值
        cap.append({"B": "0", "R": "1", "S": "2"}[f[4][0]])
        join.append({"M": "0", "R": "1", "B": "2"}[f[5][0]])
    return fill, stroke, width, evenodd, cap, join


def validate(count, first, offset, ops):
    """模拟 Java 侧的 Path 构建，确认每条子路径都能被正确解析。

    Java 的 Path2D 在"没 moveTo 就 lineTo"时会抛 IllegalPathStateException，
    所以这里必须确认：每条子路径以 MOVE 开头、命令流不越界、没有未知命令。
    """
    def val(tok):
        return float(tok.rstrip("fF"))

    n_ops = len(ops)
    for s in range(len(offset)):
        start = offset[s]
        end = offset[s + 1] if s + 1 < len(offset) else n_ops
        if start >= end:
            sys.exit("子路径 %d 的区间非法: [%d, %d)" % (s, start, end))
        if int(val(ops[start])) != 0:
            sys.exit("子路径 %d 没有以 MOVE 开头（第一个命令是 %s）"
                     % (s, ops[start]))
        i = start
        while i < end:
            cmd = int(val(ops[i]))
            i += 1
            if cmd == 0 or cmd == 1:
                i += 2
            elif cmd == 2:
                i += 6
            elif cmd == 3:
                i += 4
            elif cmd == 4:
                pass
            else:
                sys.exit("子路径 %d 出现未知命令 %s（下标 %d）" % (s, cmd, i - 1))
            if i > end:
                sys.exit("子路径 %d 的命令流越过了区间末尾（%d > %d）" % (s, i, end))

    # 棋子覆盖的子路径必须完整落在 OFFSET 范围内
    for p in range(len(count)):
        if first[p] + count[p] > len(offset):
            sys.exit("棋子 %d 引用了不存在的子路径" % p)


def as_int_table(name, values, per_line=20, indent="    "):
    lines = []
    for i in range(0, len(values), per_line):
        chunk = values[i:i + per_line]
        lines.append(indent + "        " + ", ".join(chunk) + ",")
    return "%sprivate static final int[] %s = {\n%s\n%s};\n" % (
        indent, name, "\n".join(lines), indent)


def as_bool_table(name, values, per_line=20, indent="    "):
    lines = []
    for i in range(0, len(values), per_line):
        chunk = values[i:i + per_line]
        lines.append(indent + "        " + ", ".join(chunk) + ",")
    return "%sprivate static final boolean[] %s = {\n%s\n%s};\n" % (
        indent, name, "\n".join(lines), indent)


def as_float_table(name, values, per_line=10, indent="    "):
    lines = []
    for i in range(0, len(values), per_line):
        chunk = values[i:i + per_line]
        lines.append(indent + "        " + ", ".join(chunk) + ",")
    return "%sprivate static final float[] %s = {\n%s\n%s};\n" % (
        indent, name, "\n".join(lines), indent)


def main():
    text = read_source()
    count = grab_ints(text, "COUNT")
    first = grab_ints(text, "FIRST")
    offset = grab_ints(text, "OFFSET")
    style = grab_strings(text, "STYLE")
    ops = grab_floats(text, "opsBlock0") + grab_floats(text, "opsBlock1")

    s_fill, s_stroke, s_width, s_evenodd, s_cap, s_join = parse_style(style)

    if len(s_fill) != len(offset):
        sys.exit("子路径数不一致: STYLE=%d OFFSET=%d" % (len(s_fill), len(offset)))
    if len(first) != len(count):
        sys.exit("棋子数不一致: FIRST=%d COUNT=%d" % (len(first), len(count)))

    # 生成前先自己解析一遍命令流，确认数据本身是自洽的。
    # 这类错误在 Java 侧只会表现为 "missing initial moveto" 之类的怪报错，
    # 在生成期拦下来便宜得多。
    validate(count, first, offset, ops)

    pieces = len(count)
    subs = len(offset)

    buf = []
    w = buf.append
    w("package org.lichessold.desktop;\n\n")
    w("import java.awt.geom.Path2D;\n\n")
    w("/**\n")
    w(" * 棋子矢量造型数据（桌面版）—— **本文件由 desktop/tools/make_pieceart2d.py 自动生成，请勿手改**。\n")
    w(" *\n")
    w(" * 数据来自 app/src/org/lichessold/ui/PieceArt.java（Android 版），造型本身\n")
    w(" * 取自 Cburnett 的国际象棋棋子（Wikipedia / lichess 默认使用的一套），CC BY-SA 3.0。\n")
    w(" * 只把 android.graphics.Path 换成 java.awt.geom.Path2D，坐标仍是归一化的 0..1，\n")
    w(" * 描边宽度也按 0..1 计，绘制时整体 scale 即可。\n")
    w(" *\n")
    w(" * 数据布局（与 Android 版一致，全部扁平，避免大量小对象）：\n")
    w(" *   OPS     所有子路径的命令流首尾相接\n")
    w(" *   OFFSET  第 i 个子路径在 OPS 里的起点；第 i+1 项就是它的终点\n")
    w(" *   FIRST / COUNT   第 p 枚棋子的子路径区间\n")
    w(" *\n")
    w(" * 规模：%d 枚棋子 / %d 个子路径 / %d 个浮点数\n" % (pieces, subs, len(ops)))
    w(" */\n")
    w("public final class PieceArt2D {\n\n")

    w("    /** 命令编码，与 make_pieces.py 的 Cursor 一致。 */\n")
    w("    public static final int MOVE = 0;\n")
    w("    public static final int LINE = 1;\n")
    w("    public static final int CUBIC = 2;\n")
    w("    public static final int QUAD = 3;\n")
    w("    public static final int CLOSE = 4;\n\n")

    w("    /** 每种棋子的子路径数量，索引 = (类型-1)*2 + (黑?1:0)。 */\n")
    w(as_int_table("COUNT", [str(v) for v in count]))
    w("\n    /** 每种棋子的起始子路径下标。 */\n")
    w(as_int_table("FIRST", [str(v) for v in first]))
    w("\n    /** 每个子路径的命令流在 OPS 里的起点（浮点下标）。 */\n")
    w(as_int_table("OFFSET", [str(v) for v in offset], per_line=20))
    w("\n    /** 每个子路径的填充色（0 = 不填充）。 */\n")
    w(as_int_table("S_FILL", ["0x%08X" % v for v in s_fill]))
    w("\n    /** 每个子路径的描边色（0 = 不描边）。 */\n")
    w(as_int_table("S_STROKE", ["0x%08X" % v for v in s_stroke]))
    w("\n    /** 每个子路径的描边宽度（归一化坐标）。 */\n")
    w(as_float_table("S_WIDTH", s_width))
    w("\n    /** 每个子路径是否用奇偶填充规则。 */\n")
    w(as_bool_table("S_EVENODD", s_evenodd))
    w("\n    /** 线帽：0=BUTT 1=ROUND 2=SQUARE（与 BasicStroke.CAP_* 一致）。 */\n")
    w(as_int_table("S_CAP", s_cap))
    w("\n    /** 线接：0=MITER 1=ROUND 2=BEVEL（与 BasicStroke.JOIN_* 一致）。 */\n")
    w(as_int_table("S_JOIN", s_join))

    w("\n    /** 全部命令流。分块构建，避免单个方法字节码超限。 */\n")
    w("    private static final float[] OPS = concat(new float[][] { opsBlock0(), opsBlock1() });\n\n")

    # 注意：必须先把两块切好再分组，不能直接按 8 个一组从全长切 ——
    # 那样最后一组会跨过中点，把元素重复写进两块里（第一版就是这么错的，
    # 结果 OPS 错位，Path 报 "missing initial moveto"）。
    half = len(ops) // 2
    blocks = [ops[:half], ops[half:]]

    for bi in range(2):
        w("    private static float[] opsBlock%d() {\n" % bi)
        w("        return new float[] {\n")
        block = blocks[bi]
        for i in range(0, len(block), 8):
            chunk = block[i:i + 8]
            w("            " + ", ".join(chunk) + ",\n")
        w("        };\n")
        w("    }\n\n")

    w("    private static float[] concat(float[][] parts) {\n")
    w("        int n = 0;\n")
    w("        for (int i = 0; i < parts.length; i++) {\n")
    w("            n += parts[i].length;\n")
    w("        }\n")
    w("        float[] all = new float[n];\n")
    w("        int at = 0;\n")
    w("        for (int i = 0; i < parts.length; i++) {\n")
    w("            System.arraycopy(parts[i], 0, all, at, parts[i].length);\n")
    w("            at += parts[i].length;\n")
    w("        }\n")
    w("        return all;\n")
    w("    }\n\n")

    w("    private static final Path2D.Float[][] PATHS = new Path2D.Float[COUNT.length][];\n")
    w("    private static boolean prepared;\n\n")

    w("    private PieceArt2D() {\n")
    w("    }\n\n")

    w("    private static synchronized void prepare() {\n")
    w("        if (prepared) {\n")
    w("            return;\n")
    w("        }\n")
    w("        for (int p = 0; p < COUNT.length; p++) {\n")
    w("            Path2D.Float[] list = new Path2D.Float[COUNT[p]];\n")
    w("            for (int s = 0; s < COUNT[p]; s++) {\n")
    w("                list[s] = build(FIRST[p] + s);\n")
    w("            }\n")
    w("            PATHS[p] = list;\n")
    w("        }\n")
    w("        prepared = true;\n")
    w("    }\n\n")

    w("    private static Path2D.Float build(int sub) {\n")
    w("        Path2D.Float p = new Path2D.Float(S_EVENODD[sub]\n")
    w("                ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);\n")
    w("        int i = OFFSET[sub];\n")
    w("        int end = (sub + 1 < OFFSET.length) ? OFFSET[sub + 1] : OPS.length;\n")
    w("        while (i < end) {\n")
    w("            int cmd = (int) OPS[i++];\n")
    w("            if (cmd == MOVE) {\n")
    w("                p.moveTo(OPS[i], OPS[i + 1]);\n")
    w("                i += 2;\n")
    w("            } else if (cmd == LINE) {\n")
    w("                p.lineTo(OPS[i], OPS[i + 1]);\n")
    w("                i += 2;\n")
    w("            } else if (cmd == CUBIC) {\n")
    w("                p.curveTo(OPS[i], OPS[i + 1], OPS[i + 2], OPS[i + 3],\n")
    w("                        OPS[i + 4], OPS[i + 5]);\n")
    w("                i += 6;\n")
    w("            } else if (cmd == QUAD) {\n")
    w("                p.quadTo(OPS[i], OPS[i + 1], OPS[i + 2], OPS[i + 3]);\n")
    w("                i += 4;\n")
    w("            } else if (cmd == CLOSE) {\n")
    w("                p.closePath();\n")
    w("            } else {\n")
    w("                break;\n")
    w("            }\n")
    w("        }\n")
    w("        return p;\n")
    w("    }\n\n")

    w("    // ------------------------------------------------------------ 访问器\n\n")
    w("    public static int pieceCount() {\n")
    w("        return COUNT.length;\n")
    w("    }\n\n")
    w("    /** 第 p 枚棋子的子路径数量。 */\n")
    w("    public static int count(int p) {\n")
    w("        return COUNT[p];\n")
    w("    }\n\n")
    w("    public static Path2D.Float path(int p, int sub) {\n")
    w("        prepare();\n")
    w("        return PATHS[p][sub];\n")
    w("    }\n\n")
    w("    public static int fillColor(int p, int sub) {\n")
    w("        return S_FILL[FIRST[p] + sub];\n")
    w("    }\n\n")
    w("    public static int strokeColor(int p, int sub) {\n")
    w("        return S_STROKE[FIRST[p] + sub];\n")
    w("    }\n\n")
    w("    /** 线宽，单位与归一化坐标一致（乘格子边长即像素）。 */\n")
    w("    public static float strokeWidth(int p, int sub) {\n")
    w("        return S_WIDTH[FIRST[p] + sub];\n")
    w("    }\n\n")
    w("    public static int cap(int p, int sub) {\n")
    w("        return S_CAP[FIRST[p] + sub];\n")
    w("    }\n\n")
    w("    public static int join(int p, int sub) {\n")
    w("        return S_JOIN[FIRST[p] + sub];\n")
    w("    }\n\n")
    w("    /** 提前构建 Path 缓存，避免第一帧卡顿。 */\n")
    w("    public static void warmUp() {\n")
    w("        prepare();\n")
    w("    }\n")
    w("}\n")

    out = "".join(buf)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)

    print("已生成 %s" % OUT)
    print("  棋子 %d 枚 / 子路径 %d 个 / 浮点数 %d 个" % (pieces, subs, len(ops)))


if __name__ == "__main__":
    main()
