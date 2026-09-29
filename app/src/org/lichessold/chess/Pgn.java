package org.lichessold.chess;

import java.util.ArrayList;
import java.util.List;

/**
 * PGN 走子段解析。
 *
 * 主要用途：Lichess 的 /api/puzzle/next 只返回 game.pgn 和 puzzle.initialPly，
 * 没有 fen。必须自己把 PGN 里的 SAN 一步步走出来，重建出题局面。
 *
 * 只做走子段解析，不处理 PGN 的标签区（那是另一层的事）。
 */
public final class Pgn {

    private Pgn() {
    }

    /**
     * 从 PGN 文本里抽出所有 SAN 走子。
     * 会跳过：标签行 [..]、注释 {..}、变例 (..)、NAG $n、回合编号 1. / 1...、
     * 结果标记 1-0 / 0-1 / 1/2-1/2 / *。
     */
    public static List<String> moveTokens(String pgn) {
        List<String> out = new ArrayList<String>(64);
        if (pgn == null) {
            return out;
        }
        int i = 0;
        int n = pgn.length();
        while (i < n) {
            char c = pgn.charAt(i);

            if (c == '[') {                     // 标签
                int e = pgn.indexOf(']', i);
                i = e < 0 ? n : e + 1;
                continue;
            }
            if (c == '{') {                     // 注释
                int e = pgn.indexOf('}', i);
                i = e < 0 ? n : e + 1;
                continue;
            }
            if (c == ';') {                     // 行注释
                int e = pgn.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
                continue;
            }
            if (c == '(') {                     // 变例，整段跳过（含嵌套）
                int depth = 1;
                i++;
                while (i < n && depth > 0) {
                    char d = pgn.charAt(i);
                    if (d == '(') {
                        depth++;
                    } else if (d == ')') {
                        depth--;
                    }
                    i++;
                }
                continue;
            }
            if (c == '$') {                     // NAG
                i++;
                while (i < n && Character.isDigit(pgn.charAt(i))) {
                    i++;
                }
                continue;
            }
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                i++;
                continue;
            }

            // 读一个 token
            int start = i;
            while (i < n) {
                char d = pgn.charAt(i);
                if (d == ' ' || d == '\n' || d == '\r' || d == '\t'
                        || d == '{' || d == '(' || d == '[') {
                    break;
                }
                i++;
            }
            String tok = pgn.substring(start, i);
            if (isMoveToken(tok)) {
                out.add(tok);
            }
        }
        return out;
    }

    /** 判断一个 token 是不是走子（而不是回合编号或结果）。 */
    private static boolean isMoveToken(String tok) {
        if (tok.length() == 0) {
            return false;
        }
        if (tok.equals("1-0") || tok.equals("0-1") || tok.equals("1/2-1/2")
                || tok.equals("*") || tok.equals("...")) {
            return false;
        }
        // 回合编号：1. / 1... / 12.
        boolean allDigitsDots = true;
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                allDigitsDots = false;
                break;
            }
        }
        if (allDigitsDots) {
            return false;
        }
        // "1.e4" 这种紧贴写法，去掉开头的编号
        return true;
    }

    /** 去掉 token 开头的回合编号，例如 "12.e4" -> "e4"。 */
    public static String stripMoveNumber(String tok) {
        int i = 0;
        while (i < tok.length()) {
            char c = tok.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.') {
                i++;
            } else {
                break;
            }
        }
        return tok.substring(i);
    }

    /**
     * 把 PGN 走子段重放 ply 步，棋盘就地更新。
     *
     * @return 实际走成功的步数（可能少于 ply，遇到解析不了的就停）
     */
    public static int replay(Board b, String pgn, int ply) {
        List<String> tokens = moveTokens(pgn);
        int applied = 0;
        for (int i = 0; i < tokens.size() && applied < ply; i++) {
            String san = stripMoveNumber(tokens.get(i));
            int mv = San.parse(b, san);
            if (mv == Move.NONE) {
                break;
            }
            b.make(mv);
            applied++;
        }
        return applied;
    }

    /** 只解析不落地：返回 UCI 走子列表。 */
    public static String[] toUciList(String pgn) {
        Board b = new Board();
        List<String> tokens = moveTokens(pgn);
        List<String> out = new ArrayList<String>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            int mv = San.parse(b, stripMoveNumber(tokens.get(i)));
            if (mv == Move.NONE) {
                break;
            }
            out.add(Move.toUci(mv));
            b.make(mv);
        }
        return out.toArray(new String[out.size()]);
    }
}
