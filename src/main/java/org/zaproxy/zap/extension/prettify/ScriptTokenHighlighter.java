package org.zaproxy.zap.extension.prettify;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Colours object literal keys inside the {@code <script>} blocks of a markup document.
 *
 * <p>RSTA's HTML token maker lexes script bodies with its own JavaScript rules, so quoted keys are
 * painted with the same string colour as values. The formatted output puts each property on its own
 * line, which makes key detection cheap: a quoted (or unquoted, after {@code '{'} / {@code ','})
 * token followed by {@code ':'} is a key and is retyped to {@link TokenTypes#VARIABLE}, the same
 * type RSTA's JSON maker uses for keys. A name followed by {@code '('} in the same position is a
 * method shorthand and becomes {@link TokenTypes#FUNCTION}.
 */
final class ScriptTokenHighlighter {

    private ScriptTokenHighlighter() {}

    /**
     * Retypes the object literal keys in one already tokenized display line. Only tokens that start
     * inside one of {@code ranges} (pairs of script-body start/end offsets) are touched. Idempotent.
     *
     * <p>A line that starts at a wrap break cannot start with a key: the break fell inside an
     * expression, so a name followed by {@code ':'} there is a ternary or label branch, not a
     * property ({@code cond?global:other} wrapped after {@code global?}).
     */
    static void recolor(Token list, int[] ranges, boolean continuationLine) {
        // Walk the token list directly instead of first copying it into an ArrayList: this runs for
        // every visible line on every repaint of an HTML/JS document, so the per-line list
        // allocation used to add up while scrolling. The tokens are compared by character rather
        // than by getLexeme(), which would allocate a String for every token on every repaint.
        boolean keyPosition = !continuationLine;
        for (Token t = list; t != null; t = t.getNextToken()) {
            if (t.getNextToken() == null || t.length() <= 0 || isInvisible(t.getType())) {
                continue;
            }
            int type = t.getType();
            boolean quoted =
                    type == TokenTypes.LITERAL_STRING_DOUBLE_QUOTE
                            || type == TokenTypes.LITERAL_CHAR;
            // keyPosition is true exactly when the previous visible token was '{' or ',' (or this
            // is the first token of a non-continuation line), so it subsumes the old checks on the
            // previous lexeme.
            boolean unquoted =
                    keyPosition && (type == TokenTypes.IDENTIFIER || type == TokenTypes.VARIABLE);
            if (keyPosition && (quoted || unquoted) && inRanges(t.getOffset(), ranges)) {
                Token next = nextVisible(t);
                if (next != null) {
                    if (isSingle(next, ':')) {
                        t.setType(TokenTypes.VARIABLE);
                    } else if (isSingle(next, '(') && methodBodyFollows(next)) {
                        t.setType(TokenTypes.FUNCTION);
                    }
                }
            }
            keyPosition = isSingle(t, '{') || isSingle(t, ',');
        }
    }

    /** The next painted token after {@code t}, or {@code null} at the end-of-line null token. */
    private static Token nextVisible(Token t) {
        for (Token next = t.getNextToken(); next != null; next = next.getNextToken()) {
            if (next.getNextToken() == null) {
                // RSTA's trailing, text-less token only carries the end-of-line state.
                return null;
            }
            if (next.length() <= 0 || isInvisible(next.getType())) {
                continue;
            }
            return next;
        }
        return null;
    }

    private static boolean inRanges(int offset, int[] ranges) {
        int lo = 0;
        int hi = ranges.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int start = ranges[mid * 2];
            int end = ranges[mid * 2 + 1];
            if (offset < start) {
                hi = mid - 1;
            } else if (offset >= end) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private static boolean isSingle(Token token, char c) {
        return token.length() == 1 && token.charAt(0) == c;
    }

    /** True when the parenthesis at {@code open} closes before an object body brace. */
    private static boolean methodBodyFollows(Token open) {
        int depth = 0;
        for (Token t = open; t != null; t = t.getNextToken()) {
            if (t.getNextToken() == null || t.length() <= 0 || isInvisible(t.getType())) {
                continue;
            }
            if (isSingle(t, '(')) {
                depth++;
            } else if (isSingle(t, ')')) {
                depth--;
                if (depth == 0) {
                    Token after = nextVisible(t);
                    return after != null && isSingle(after, '{');
                }
            }
        }
        return false;
    }

    private static boolean isInvisible(int type) {
        switch (type) {
            case TokenTypes.WHITESPACE:
            case TokenTypes.COMMENT_EOL:
            case TokenTypes.COMMENT_MULTILINE:
            case TokenTypes.COMMENT_DOCUMENTATION:
            case TokenTypes.COMMENT_KEYWORD:
            case TokenTypes.COMMENT_MARKUP:
                return true;
            default:
                return false;
        }
    }
}
