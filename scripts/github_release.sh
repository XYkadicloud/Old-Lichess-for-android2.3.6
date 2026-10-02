#!/usr/bin/env bash
# =============================================================================
# 推送代码到 GitHub + 创建 Release 并上传 APK / JAR
#
# 前置条件
# --------
# 1. GitHub 上已经有一个空仓库（本脚本不会自动建仓 —— 细粒度 PAT 通常没这个权限）
# 2. 环境变量 GH_TOKEN 里放一个对该仓库有 Contents: write 权限的令牌
#
# 用法
# ----
#   export GH_TOKEN='ghp_xxx'
#   bash scripts/github_release.sh XYkadicloud/lichess-old
#   bash scripts/github_release.sh XYkadicloud/lichess-old --push-only   # 只推代码
#   bash scripts/github_release.sh XYkadicloud/lichess-old --assets-only  # 只传附件
#   bash scripts/github_release.sh XYkadicloud/lichess-old --force        # 强推（远端已有 README/LICENSE 时）
#
# ⚠️ 令牌只从环境变量读，**不要写进仓库里的任何文件**。
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/build.config.sh"

# build.config.sh 里没有 DESKTOP（桌面版是后来加的），自己补上
DESKTOP="${DESKTOP:-$ROOT/desktop}"

if [ -z "${GH_TOKEN:-}" ]; then
    echo "!! 请先 export GH_TOKEN=<你的 GitHub 令牌>" >&2
    exit 1
fi

REPO="${1:-}"
MODE="${2:-all}"
FORCE=0
if [ "$MODE" = "--force" ]; then
    MODE="all"
    FORCE=1
fi
if [ -z "$REPO" ]; then
    echo "用法: bash scripts/github_release.sh <owner/repo> [--push-only|--assets-only|--force]" >&2
    exit 2
fi

API="https://api.github.com/repos/$REPO"
UPLOAD="https://uploads.github.com/repos/$REPO/releases"
AUTH=(-H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json")

APK="${APK:-$DIST/LichessOld-1.0.apk}"
JAR="$DESKTOP/dist/LichessOldDesktop-1.0.0.jar"
TAG="${TAG:-v1.0}"
TITLE="Android 1.0 · 桌面版 1.0.0"

# ---------------------------------------------------------------------------
# 1. 推送代码
# ---------------------------------------------------------------------------
if [ "$MODE" = "all" ] || [ "$MODE" = "--push-only" ]; then
    echo "=== 推送代码 → $REPO ==="
    if ! git rev-parse --git-dir >/dev/null 2>&1; then
        echo "!! 当前目录不是 git 仓库" >&2
        exit 1
    fi
    BRANCH="$(git branch --show-current)"
    echo "分支: $BRANCH"

    if git remote get-url origin >/dev/null 2>&1; then
        git remote set-url origin "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"
    else
        git remote add origin "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"
    fi

    # --force：远端已有内容且历史与本地不相关时用（比如建仓时勾了 README/LICENSE）。
    # 用 --force-with-lease 而不是裸 --force：如果远端在本次操作期间又被别人推了新提交，
    # 它会拒绝，避免误覆盖。
    PUSH_FLAGS=(-u)
    if [ "$FORCE" = "1" ]; then
        PUSH_FLAGS=(-u --force-with-lease)
        echo "-- 强推模式（--force-with-lease）"
    fi

    if git push "${PUSH_FLAGS[@]}" origin "$BRANCH"; then
        echo "-- 推送完成"
    else
        echo "!! 推送失败。常见原因：" >&2
        echo "   1) 令牌没有这个仓库的写权限（Contents: write）—— 换一个有 repo 权限的令牌" >&2
        echo "   2) 远端已有内容、历史与本地不相关 —— 加 --force 参数" >&2
        echo "   3) 仓库不存在 —— 去 https://github.com/new 建一个" >&2
        exit 1
    fi
fi

if [ "$MODE" = "--push-only" ]; then
    echo "完成（只推代码）"
    exit 0
fi

# ---------------------------------------------------------------------------
# 2. 检查附件
# ---------------------------------------------------------------------------
echo ""
echo "=== 检查交付物 ==="
MISSING=0
for f in "$APK" "$JAR"; do
    if [ -f "$f" ]; then
        echo "  [有] $(basename "$f")  ($(stat -c%s "$f") 字节)"
    else
        echo "  [缺] $f"
        MISSING=1
    fi
done
if [ "$MISSING" = "1" ]; then
    echo "!! 有交付物缺失，先构建：" >&2
    echo "     bash scripts/build.sh 1.0" >&2
    echo "     bash desktop/build.sh" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 3. 组装 Release 说明
#
# 这里曾经踩过一个坑：模板用了**不带引号**的 heredoc（<<EOF），
# 于是正文里的反引号被 shell 当成命令替换执行掉了 ——
# 结果 "可存到存储卡 `LichessOld/games/`" 里的路径、"tournament:write"
# 这些词全被吃光，release 页面显示出来的说明是残缺的，而且脚本还报
# 一堆 "No such file or directory"，但退出码依然是 0，非常隐蔽。
#
# 现在改成：模板用带引号的 heredoc 原样落盘，动态值用 @XXX@ 占位，
# 最后用 Python 做替换。shell 从此不碰正文里的任何字符。
# ---------------------------------------------------------------------------
TEMPLATE_FILE="$(mktemp)"
cat > "$TEMPLATE_FILE" <<'TPLEOF'
## 下载

| 文件 | 说明 | 校验 |
|---|---|---|
| @APKNAME@ | Android 安装包，@APKKB@ KB，拷到手机点击安装 | `@APKSHA16@…` |
| @JARNAME@ | 桌面版，@JARKB@ KB，双击 `run.bat` 或 `java -jar` 运行 | `@JARSHA16@…` |

> ⚠️ APK 是自签名证书，不是从应用商店装的，首次安装需要在系统设置里允许「未知来源」。

---

## Android 版 1.0

为 **Samsung GT-S5360（Galaxy Y，Android 2.3.6 / API 10 / ARMv6）** 从零写的
原生 Lichess 客户端。纯 Java、无 native 库、自带 TLS 1.2 + SNI（Android 2.3
系统栈不支持）。

- 在线对局（走子 / 时钟 / 认输 / 和棋 / 聊天 / 断线重连）
- 挑战电脑（等级 1~8）、挑战玩家、找真人对手、接受 / 拒绝挑战
- 个人主页与排位分
- 离线人机对战、离线双人对战
- 谜题训练（19 种主题）、观战、网络诊断、运行日志

### 1.0 新增

- **锦标赛**：列表（正在进行 / 即将开始 / 刚结束）、排行榜、焦点对局一键观战
- **最近战绩**：近 7 天每天胜负与等级分涨跌
- **棋谱**：查看最近一局的完整 PGN，可存到存储卡 `LichessOld/games/`
- **修掉两个已经失效的棋谱接口**：原先用的 `/api/games/user/{name}`
  与 `/game/export/{id}.pgn` 实测都已 404，改用依然有效的
  `GET /api/user/{name}/current-game`
- 观战页支持指定对局（从锦标赛焦点对局进来不会被别的棋局抢走）

> 锦标赛**只能看，不能报名参赛**：报名接口需要 OAuth 的 `tournament:write`
> 权限，本应用用的是手填个人令牌，拿不到那个 scope。

## 桌面版 1.0.0

同一个棋规内核 + 同一套 TLS 栈，配上 Swing 界面，编译成**单文件可执行 JAR**，
Windows 上双击 `run.bat` 即运行。桌面构建时直接引用 `app/src` 下的源码，
不复制不修改 —— 所以两个版本跑的是同一个引擎。

比手机版多出：走子记录栏、状态栏提示、更强的引擎（桌面 CPU 快两个数量级）。

---

## 完整校验值

```
@APKNAME@             SHA-256: @APKSHA@
@JARNAME@    SHA-256: @JARSHA@
```

## 说明文档

- [README](../../blob/main/README.md) —— 总览与快速开始
- [desktop/README.md](../../blob/main/desktop/README.md) —— 桌面版细节
- [docs/05-USER-GUIDE.md](../../blob/main/docs/05-USER-GUIDE.md) —— 零基础用户手册
- [docs/08-MACHINE-PROFILES.md](../../blob/main/docs/08-MACHINE-PROFILES.md) —— 构建前先读这个
- [docs/07-DELIVERY-1.0.0.md](../../blob/main/docs/07-DELIVERY-1.0.0.md) —— 本版交付说明与测试清单

## 许可

代码 **MIT**。项目里另含三份第三方材料（棋子造型 CC BY-SA 3.0、
Spongy Castle、Mozilla 根证书包），授权各不相同，
详见 [THIRD-PARTY-NOTICES.md](../../blob/main/THIRD-PARTY-NOTICES.md)。
TPLEOF

NOTES_FILE="$(mktemp)"
python - "$TEMPLATE_FILE" "$NOTES_FILE" "$APK" "$JAR" <<'PYEND'
import sys, os, hashlib

tpl_path, out_path, apk, jar = sys.argv[1:5]

def sha256(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(65536), b''):
            h.update(chunk)
    return h.hexdigest()

def kb(p):
    return os.path.getsize(p) // 1024

apksha = sha256(apk)
jarsha = sha256(jar)

with open(tpl_path, encoding='utf-8') as f:
    tpl = f.read()

rep = {
    '@APKNAME@':  os.path.basename(apk),
    '@JARNAME@':  os.path.basename(jar),
    '@APKKB@':    str(kb(apk)),
    '@JARKB@':    str(kb(jar)),
    '@APKSHA16@': apksha[:16],
    '@JARSHA16@': jarsha[:16],
    '@APKSHA@':   apksha,
    '@JARSHA@':   jarsha,
}
for k, v in rep.items():
    tpl = tpl.replace(k, v)

leftover = [k for k in rep if k in tpl]
if leftover:
    sys.stderr.write('!! 占位符没替换干净: %s\n' % ', '.join(leftover))
    sys.exit(1)

with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(tpl)
PYEND
if [ $? -ne 0 ]; then
    echo "!! 生成 Release 说明失败" >&2
    rm -f "$TEMPLATE_FILE" "$NOTES_FILE"
    exit 1
fi
rm -f "$TEMPLATE_FILE"

# 4. 创建 Release
# ---------------------------------------------------------------------------
echo ""
echo "=== 创建 Release $TAG ==="
EXISTING=$(curl -s "${AUTH[@]}" "$API/releases/tags/$TAG" | python -c "
import sys,json
d=json.load(sys.stdin)
print(d.get('id',''))" 2>/dev/null)

if [ -n "${EXISTING:-}" ]; then
    echo "-- Release $TAG 已存在（id=$EXISTING），复用它并刷新正文"
    REL_ID="$EXISTING"
    # 复用时也要把正文更新一遍。否则重跑脚本只换了附件，说明还是旧的，
    # 页面上的文字和实际产物对不上。
    UPD=$(python -c "
import json,sys
notes=open(sys.argv[1],encoding='utf-8').read()
print(json.dumps({'body':notes},ensure_ascii=False))
" "$NOTES_FILE")
    curl -s -X PATCH "${AUTH[@]}" "$API/releases/$REL_ID" -d "$UPD" >/dev/null
else
    PAYLOAD=$(python -c "
import json,sys
notes=open(sys.argv[1],encoding='utf-8').read()
print(json.dumps({'tag_name':'$TAG','name':'$TITLE','body':notes,'draft':False,'prerelease':False},ensure_ascii=False))
" "$NOTES_FILE")
    # 上面这个 python 只是把正文转成 JSON 字符串，不涉及 shell 解释，
    # 所以正文里的反引号是安全的。
    REL_JSON=$(curl -s -X POST "${AUTH[@]}" "$API/releases" -d "$PAYLOAD")
    REL_ID=$(echo "$REL_JSON" | python -c "
import sys,json
d=json.load(sys.stdin)
print(d.get('id','') or 'ERR: '+str(d.get('message','')))")
    if [ -z "$REL_ID" ] || [[ "$REL_ID" == ERR:* ]]; then
        echo "!! 创建 Release 失败: $REL_ID" >&2
        rm -f "$NOTES_FILE"
        exit 1
    fi
    echo "-- Release 已创建（id=$REL_ID）"
fi
rm -f "$NOTES_FILE"

# ---------------------------------------------------------------------------
# 5. 上传附件
# ---------------------------------------------------------------------------
echo ""
echo "=== 上传附件 ==="
upload_one() {
    local file="$1"
    local name
    name="$(basename "$file")"
    # 同名附件先删掉，避免重复
    local old
    old=$(curl -s "${AUTH[@]}" "$API/releases/$REL_ID/assets" \
        | python -c "
import sys,json
d=json.load(sys.stdin)
for a in (d if isinstance(d,list) else []):
    if a.get('name')=='$name': print(a['id'])
" 2>/dev/null)
    if [ -n "${old:-}" ]; then
        curl -s -X DELETE "${AUTH[@]}" "$API/releases/assets/$old" >/dev/null
        echo "   （删掉同名旧附件）"
    fi
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer $GH_TOKEN" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@$file" \
        "$UPLOAD/$REL_ID/assets?name=$name")
    if [ "$code" = "201" ]; then
        echo "   [OK] $name"
        return 0
    fi
    echo "   [失败] $name  (HTTP $code)"
    return 1
}

FAIL=0
upload_one "$APK" || FAIL=1
upload_one "$JAR" || FAIL=1

echo ""
if [ "$FAIL" = "0" ]; then
    echo "########## 完成 ##########"
    echo "  仓库:   https://github.com/$REPO"
    echo "  Release: https://github.com/$REPO/releases/tag/$TAG"
else
    echo "!! 有附件上传失败" >&2
    exit 1
fi
