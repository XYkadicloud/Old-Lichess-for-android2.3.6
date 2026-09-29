#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把依赖 jar 的条目**流式**合并进目标 jar，不经过磁盘。

为什么要这么写
--------------
常规做法是 `jar xf dep.jar` 把依赖解压到一个临时目录，再 `jar uf` 加回去。
本项目在 **exFAT** 盘上开发，exFAT 的簇很大（307GB 卷默认 128KB），
每个文件不管多小都吃掉一整个簇 —— 解压 sc-core 会产生 1460 个文件、
占掉约 187 MB，而且每次构建都要重来一遍，在 USB 2.0 机械盘上非常慢。

这个脚本直接从源 zip 读条目、写进目标 zip，中间不落盘：
只多写一个 3.5MB 的 jar，省掉 1460 个文件。

用法：
    python3 merge_jar.py <目标.jar> <来源.jar> [<来源.jar> ...]

行为：
    - 保留目标 jar 的全部条目（含 MANIFEST.MF，且它仍排在第一）
    - 只追加来源里目标没有的条目（同名以目标为准）
    - 跳过目录条目和签名文件（META-INF/*.SF|RSA|DSA，避免破坏签名）
"""

import os
import sys
import zipfile

SKIP_PREFIXES = ("META-INF/",)
SKIP_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def should_skip(name):
    upper = name.upper()
    if any(upper.endswith(s) for s in SKIP_SUFFIXES):
        return True
    if upper.startswith("META-INF/") and "/" not in name[len("META-INF/"):]:
        # META-INF 根下的东西（MANIFEST.MF、签名）由目标 jar 自己决定
        return True
    return False


def merge(target, sources):
    if not os.path.isfile(target):
        sys.exit("目标 jar 不存在: %s" % target)

    # 1) 读出目标 jar 的全部条目（保持顺序，MANIFEST.MF 在第一位）
    with zipfile.ZipFile(target, "r") as z:
        entries = []
        for info in z.infolist():
            if info.filename.endswith("/"):
                continue
            entries.append((info.filename, info.compress_type, z.read(info.filename)))

    have = set(name for name, _, _ in entries)
    added = 0
    skipped = 0

    # 2) 从每个来源 jar 追加缺少的条目
    for src in sources:
        if not os.path.isfile(src):
            sys.exit("来源 jar 不存在: %s" % src)
        with zipfile.ZipFile(src, "r") as s:
            for info in s.infolist():
                name = info.filename
                if name.endswith("/"):
                    continue
                if should_skip(name):
                    skipped += 1
                    continue
                if name in have:
                    skipped += 1
                    continue
                entries.append((name, info.compress_type, s.read(name)))
                have.add(name)
                added += 1

    # 3) 整体重写目标 jar
    tmp = target + ".tmp"
    try:
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as out:
            for name, ctype, data in entries:
                out.writestr(name, data, compress_type=ctype)
        os.replace(tmp, target)
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass

    total = len(entries)
    size = os.path.getsize(target)
    print("  合并完成：新增 %d 个条目，跳过 %d 个，共 %d 个条目，jar %.1f MB"
          % (added, skipped, total, size / 1048576.0))


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    merge(sys.argv[1], sys.argv[2:])


if __name__ == "__main__":
    main()
