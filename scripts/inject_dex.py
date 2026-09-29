#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 classes.dex 注入到 aapt 生成的未签名 APK 里，写到**另一个文件**。

为什么不用 `aapt add`：aapt add 会把 APK 里所有条目重新压缩一遍，
历史上出现过把 resources.arsc 压坏的情况。用 zipfile 只新增一个条目，最稳。

为什么输出到新文件而不是原地覆盖：原地覆盖要先 os.remove(输入文件)，
而沙箱会把"构建过程中删文件"当成批量删除拦下来
（SAFE_DELETE_BULK_CONFIRM_REQUIRED，阈值 50 个/轮，构建一轮要写 200+ 个文件）。
build.sh 现在整个流程只增不删，所以这里也不许删。

用法： inject_dex.py <in.apk> <out.apk> <classes.dex> [more.dex ...]
"""
import os
import sys
import zipfile


def main():
    if len(sys.argv) < 4:
        print("usage: inject_dex.py <in.apk> <out.apk> <classes.dex> [more.dex ...]")
        return 2

    apk = sys.argv[1]
    out = sys.argv[2]
    dexes = sys.argv[3:]

    if not os.path.isfile(apk):
        print("ERROR: apk not found: %s" % apk)
        return 1
    for d in dexes:
        if not os.path.isfile(d):
            print("ERROR: dex not found: %s" % d)
            return 1

    if os.path.abspath(apk) == os.path.abspath(out):
        print("ERROR: in.apk and out.apk must differ (no in-place overwrite)")
        return 1

    with zipfile.ZipFile(apk, "r") as zin:
        existing = set(zin.namelist())
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                zout.writestr(item, data)
            for d in dexes:
                name = os.path.basename(d)
                if name in existing:
                    print("  replace existing %s" % name)
                with open(d, "rb") as f:
                    data = f.read()
                zout.writestr(name, data)
                print("  added %s (%d bytes)" % (name, len(data)))

    print("injected into %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
