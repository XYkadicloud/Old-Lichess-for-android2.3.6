#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
一次性迁移脚本：把 5 个对局子类的 buildButtons(LinearLayout) 改成 buildActions()。

背景：界面重做后，底部按钮由基类统一排版（一行 + 「≡」溢出菜单），
子类不该再自己拼 LinearLayout 行。这个脚本把
    Button x = Ui.smallButton(this, "标签");
    x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { ... } });
统一换成
    action("标签", new Runnable() { public void run() { ... } });
并丢掉 container.addView(Ui.buttonRow(...)) 和子类自己的「返回」（基类自动补）。

只跑一次，跑完就可以删。
"""

import re
import sys
import os

UI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src",
                  "org", "lichessold", "ui")
FILES = ["TvActivity.java", "LocalGameActivity.java", "AiGameActivity.java",
         "GameActivity.java", "PuzzleActivity.java"]

SIG_OLD = "    protected void buildButtons(LinearLayout container) {"
SIG_NEW = "    protected void buildActions() {"

BTN_RE = re.compile(
    r'\s*Button (\w+) = (?:Ui|Theme)\.small(?:Button|Primary)\(this, "([^"]+)"\);')
LISTEN_RE = re.compile(r'\s*(\w+)\.setOnClickListener\(new View\.OnClickListener\(\) \{$')


def transform(path):
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")

    start = None
    for i, l in enumerate(lines):
        if l == SIG_OLD:
            start = i
            break
    if start is None:
        return "跳过（找不到 buildButtons 签名）"

    end = None
    for j in range(start + 1, len(lines)):
        if lines[j] == "    }":
            end = j
            break
    if end is None:
        return "错误：找不到方法结尾"

    body = lines[start + 1:end]
    out = []
    i = 0
    label = None
    n_actions = 0
    while i < len(body):
        line = body[i]

        m = BTN_RE.match(line)
        if m:
            label = m.group(2)
            i += 1
            continue

        m = LISTEN_RE.match(line)
        if m and label is not None:
            j = i + 1
            if "public void onClick(View v) {" not in body[j]:
                return "错误：第 %d 行不是 onClick 开头" % (start + 1 + j)
            j += 1
            inner = []
            while j < len(body) and body[j] != "            }":
                inner.append(body[j])
                j += 1
            if j >= len(body) or body[j] != "            }":
                return "错误：onClick 花括号没对上"
            j += 1
            if j >= len(body) or body[j].strip() != "});":
                return "错误：onClick 结尾不是 });"
            if label != "返回":
                out.append('        action("%s", new Runnable() {' % label)
                out.append("            public void run() {")
                out.extend(inner)
                out.append("            }")
                out.append("        });")
                n_actions += 1
            label = None
            i = j + 1
            continue

        if line.strip().startswith("container.addView("):
            while i < len(body) and not body[i].rstrip().endswith(");"):
                i += 1
            i += 1
            continue

        if line.strip() == "" and (not out or out[-1].strip() == ""):
            i += 1
            continue

        out.append(line)
        i += 1

    while out and out[-1].strip() == "":
        out.pop()

    new = lines[:start] + [SIG_NEW] + out + lines[end:]
    # 这个盘的文件系统不支持"就地改写"（工具层的备份步骤会失败），
    # 所以统一写进暂存目录，再由调用方 mv 覆盖。
    stage = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "ui-new")
    if not os.path.isdir(stage):
        os.makedirs(stage)
    with open(os.path.join(stage, os.path.basename(path)), "w",
              encoding="utf-8", newline="\n") as f:
        f.write("\n".join(new))
    return "改造 %d 个动作 -> build/ui-new/" % n_actions


def bump_inline(path):
    with open(path, encoding="utf-8") as f:
        src = f.read()
    old = "private static final int MAX_INLINE_BUTTONS = 4;"
    new = "private static final int MAX_INLINE_BUTTONS = 5;"
    if old not in src:
        return "（常量已是 5 或未找到）"
    stage = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "build", "ui-new")
    with open(os.path.join(stage, os.path.basename(path)), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(src.replace(old, new))
    return "MAX_INLINE_BUTTONS -> 5 -> build/ui-new/"


def main():
    for name in FILES:
        p = os.path.join(UI, name)
        print("%-26s %s" % (name, transform(p)))
    print("%-26s %s" % ("BoardGameActivity.java",
                        bump_inline(os.path.join(UI, "BoardGameActivity.java"))))


if __name__ == "__main__":
    main()
