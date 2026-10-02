# 第三方材料授权说明

本项目**代码**的许可是 MIT，见 [LICENSE](LICENSE)。

但项目里还包含三份第三方材料，各自授权不同，使用时需要**分开遵守**。
下面列清楚每一份是什么、在哪、什么授权。

---

## 1. 棋子造型（Cburnett 版）

| 项目 | 内容 |
|---|---|
| 来源 | Cburnett 的国际象棋棋子（Wikipedia 条目 / lichess 默认使用的一套） |
| 授权 | **CC BY-SA 3.0**（署名 + 相同方式共享） |
| 位置 | `app/src/org/lichessold/ui/PieceArt.java`（由 `scripts/make_pieces.py` 从 SVG 生成的矢量数据） |
| 桌面版 | `desktop/src/org/lichessold/desktop/PieceArt2D.java` |
| 出处 | https://en.wikipedia.org/wiki/File:Chess_Pieces_Sprite.svg |

⚠️ **注意**：CC BY-SA 3.0 要求「署名」和「相同方式共享」。
如果二次分发成品（APK / JAR），需要保留署名，并以相同协议共享**造型部分**
（代码本身仍按 MIT，不受影响）。

## 2. 密码学库（Spongy Castle）

| 项目 | 内容 |
|---|---|
| 名称 | Spongy Castle `sc-core` 1.58.0.0（Bouncy Castle 的 Android 兼容版） |
| 授权 | Bouncy Castle Licence（BSD 风格，**与 MIT 兼容**） |
| 位置 | `toolchain/libs/sc-core-1.58.0.0.jar` —— **不入库**，由 `scripts/setup.sh` 下载 |
| 出处 | https://www.bouncycastle.org/licence.html |

## 3. 根证书包（Mozilla CA）

| 项目 | 内容 |
|---|---|
| 名称 | Mozilla CA Certificate Bundle（`cacert.pem`） |
| 授权 | **MPL 2.0** |
| 位置 | `app/assets/cacerts.pem` 与 `desktop/assets/cacerts.pem` |
| 出处 | https://curl.se/docs/caextract.html |

---

## 为什么 LICENSE 里不放这些内容

GitHub 用 [licensee](https://github.com/licensee/licensee) 自动识别仓库许可证，
它要求 LICENSE 文件与标准许可证文本**高度匹配**。
原先把这些中文说明追加在 MIT 原文后面，匹配失败，仓库许可证就显示成
`NOASSERTION`（未识别），看起来像没有许可证。

所以：LICENSE 只放纯净的 MIT 原文，第三方说明放这个文件。
信息一条没少，GitHub 也能正确识别了。
