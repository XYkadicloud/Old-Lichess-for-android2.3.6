package org.lichessold.chess;

/**
 * SAN（标准代数记谱）的生成与解析。
 *
 * 两处都要用：
 *  - 显示：界面上展示走子历史
 *  - 解析：/api/puzzle/next 只给 PGN，必须靠 SAN 重建出题局面
 */
public final class San {

    private San() {
    }

    // -------------------------------------------------------------- 生成

    /** 把走子转成 SAN，例如 Nf3 / exd5 / O-O / e8=Q+ / Qxf7#。 */
    public static String toSan(Board b, int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        int piece = b.sq[from];
        int type = Chess.type(piece);
        boolean capture = b.sq[to] != 0 || Move.has(move, Move.FLAG_EP);

        StringBuilder sb = new StringBuilder(8);

        if (Move.has(move, Move.FLAG_CASTLE_K)) {
            sb.append("O-O");
        } else if (Move.has(move, Move.FLAG_CASTLE_Q)) {
            sb.append("O-O-O");
        } else if (type == Chess.PAWN) {
            if (capture) {
                sb.append((char) ('a' + Chess.file(from))).append('x');
            }
            sb.append(Chess.squareName(to));
            int promo = Move.promo(move);
            if (promo != 0) {
                sb.append('=').append(Chess.sanLetter(promo));
            }
        } else {
            sb.append(Chess.sanLetter(type));
            sb.append(disambiguation(b, from, to, type));
            if (capture) {
                sb.append('x');
            }
            sb.append(Chess.squareName(to));
        }

        // 将军 / 将死
        b.make(move);
        boolean opponentInCheck = b.inCheck(b.side);
        boolean opponentHasMove = MoveGen.SHARED.hasLegalMove(b);
        b.unmake();
        if (opponentInCheck) {
            sb.append(opponentHasMove ? '+' : '#');
        }
        return sb.toString();
    }

    /** 需要消歧时返回文件名/横线/两者，否则返回空串。 */
    private static String disambiguation(Board b, int from, int to, int type) {
        int us = b.side;
        int[] moves = MoveGen.SHARED.bufferAt(b.ply() + 2);
        int n = MoveGen.SHARED.legal(b, moves);
        boolean sameFile = false;
        boolean sameRank = false;
        boolean ambiguous = false;
        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (Move.to(m) != to || Move.from(m) == from) {
                continue;
            }
            if (Chess.type(b.sq[Move.from(m)]) != type) {
                continue;
            }
            ambiguous = true;
            if (Chess.file(Move.from(m)) == Chess.file(from)) {
                sameFile = true;
            }
            if (Chess.rank(Move.from(m)) == Chess.rank(from)) {
                sameRank = true;
            }
        }
        if (!ambiguous) {
            return "";
        }
        if (!sameFile) {
            return String.valueOf((char) ('a' + Chess.file(from)));
        }
        if (!sameRank) {
            return String.valueOf((char) ('1' + Chess.rank(from)));
        }
        return Chess.squareName(from);
    }

    // -------------------------------------------------------------- 解析

    /**
     * 把 SAN 落到棋盘上，返回对应的走子编码；解析不出来返回 Move.NONE。
     * 容错处理：忽略 + # ! ? 注释、0-0 与 O-O 混用、=Q 与 Q 混用。
     */
    public static int parse(Board b, String sanRaw) {
        if (sanRaw == null) {
            return Move.NONE;
        }
        String san = clean(sanRaw);
        if (san.length() == 0) {
            return Move.NONE;
        }

        int[] moves = MoveGen.SHARED.bufferAt(b.ply() + 2);
        int n = MoveGen.SHARED.legal(b, moves);

        // 易位
        if (san.equals("O-O") || san.equals("0-0")) {
            return findCastle(moves, n, true);
        }
        if (san.equals("O-O-O") || san.equals("0-0-0")) {
            return findCastle(moves, n, false);
        }

        boolean pawnMove = san.charAt(0) >= 'a' && san.charAt(0) <= 'h';
        int promo = 0;
        int eq = san.indexOf('=');
        if (eq >= 0 && eq + 1 < san.length()) {
            promo = letterToType(san.charAt(eq + 1));
            san = san.substring(0, eq);
        } else if (san.length() >= 2 && pawnMove) {
            // e8Q 这种写法
            char last = san.charAt(san.length() - 1);
            if (last == 'Q' || last == 'R' || last == 'B' || last == 'N') {
                promo = letterToType(last);
                san = san.substring(0, san.length() - 1);
            }
        }

        int sanLen = san.length();
        if (sanLen < 2) {
            return Move.NONE;
        }
        int to = Chess.parseSquare(san.substring(sanLen - 2));
        if (to < 0) {
            return Move.NONE;
        }

        int pieceType;
        int disambigStart;
        if (pawnMove) {
            pieceType = Chess.PAWN;
            disambigStart = 0;
        } else {
            pieceType = letterToType(san.charAt(0));
            if (pieceType == 0) {
                return Move.NONE;
            }
            disambigStart = 1;
        }

        // 消歧部分：从棋子字母之后到目标格之前，去掉 'x'
        String disambig = san.substring(disambigStart, sanLen - 2).replace("x", "");
        int wantFile = -1;
        int wantRank = -1;
        for (int i = 0; i < disambig.length(); i++) {
            char c = disambig.charAt(i);
            if (c >= 'a' && c <= 'h') {
                wantFile = c - 'a';
            } else if (c >= '1' && c <= '8') {
                wantRank = c - '1';
            }
        }

        for (int i = 0; i < n; i++) {
            int m = moves[i];
            if (Move.to(m) != to) {
                continue;
            }
            int from = Move.from(m);
            if (Chess.type(b.sq[from]) != pieceType) {
                continue;
            }
            if (wantFile >= 0 && Chess.file(from) != wantFile) {
                continue;
            }
            if (wantRank >= 0 && Chess.rank(from) != wantRank) {
                continue;
            }
            if (promo != 0 && Move.promo(m) != promo) {
                continue;
            }
            if (promo == 0 && Move.promo(m) != 0) {
                continue;
            }
            return m;
        }
        return Move.NONE;
    }

    private static int findCastle(int[] moves, int n, boolean kingSide) {
        int flag = kingSide ? Move.FLAG_CASTLE_K : Move.FLAG_CASTLE_Q;
        for (int i = 0; i < n; i++) {
            if (Move.has(moves[i], flag)) {
                return moves[i];
            }
        }
        return Move.NONE;
    }

    private static int letterToType(char c) {
        switch (Character.toUpperCase(c)) {
            case 'N':
                return Chess.KNIGHT;
            case 'B':
                return Chess.BISHOP;
            case 'R':
                return Chess.ROOK;
            case 'Q':
                return Chess.QUEEN;
            case 'K':
                return Chess.KING;
            default:
                return 0;
        }
    }

    /** 去掉注释、将军/将死标记、e.p.、!? 等。 */
    public static String clean(String san) {
        StringBuilder sb = new StringBuilder(san.length());
        for (int i = 0; i < san.length(); i++) {
            char c = san.charAt(i);
            if (c == '+' || c == '#' || c == '!' || c == '?' || c == ' ' || c == '\t') {
                continue;
            }
            if (c == '.') {
                continue;
            }
            sb.append(c);
        }
        String s = sb.toString();
        // 去掉结尾的 e.p.
        if (s.endsWith("e.p.") || s.endsWith("ep")) {
            s = s.substring(0, s.length() - (s.endsWith("e.p.") ? 4 : 2));
        }
        return s;
    }
}
