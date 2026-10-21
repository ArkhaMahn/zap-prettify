package org.zaproxy.zap.extension.prettify;

import java.util.ArrayList;
import java.util.List;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Colours object literal keys inside the {@code <script>} blocks of a markup document.
 *
 * <p>RSTA's HTML token maker lexes script bodies with its own JavaScript rules, so quoted keys are
 * painted with the same string colour as values. The formatted output puts each property on its own
 * line, which makes key detection cheap: a quoted (or unquoted, after {@code '{'} / {@code ','})
 * token followed by {@code ':'} is a key and is retyped to {@link TokenTypes#VARIABLE}, the type
 * RSTA's JSON maker uses for keys. A name followed by {@code '('} in the same position is a method
 * shorthand and becomes {@link TokenTypes#FUNCTION}.
 */
final class ScriptTokenHighlighter {

    private ScriptTokenHighlighter() {}

    /**
     * Retypes the object literal keys in one already tokenized display line. Only tokens that start
     * inside one of {@code ranges} (pairs of script-body start/end offsets) are touched. Idempotent.
     */
    static void recolor(Token list, int[] ranges) {
        List<Token> tokens = new ArrayList<>();
        for (Token t = list; t != null; t = t.getNextToken()) {
            if (t.getNextToken() == null || t.length() <= 0 || isInvisible(t.getType())) {
                continue;
            }
            tokens.add(t);
        }
        String previous = null;
        boolean keyPosition = true;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            String lexeme = t.getLexeme();
            int type = t.getType();
            boolean quoted =
                    type == TokenTypes.LITERAL_STRING_DOUBLE_QUOTE
                            || type == TokenTypes.LITERAL_CHAR;
            boolean unquoted =
                    type == TokenTypes.IDENTIFIER
                            && ("{".equals(previous)
                                    || ",".equals(previous)
                                    || (keyPosition && previous == null));
            if (keyPosition && (quoted || unquoted) && inRanges(t.getOffset(), ranges)) {
                Token next = (i + 1 < tokens.size()) ? tokens.get(i + 1) : null;
                if (next != null) {
                    String nextText = next.getLexeme();
                    if (":".equals(nextText)) {
                        t.setType(TokenTypes.VARIABLE);
                    } else if ("(".equals(nextText) && methodBodyFollows(tokens, i + 1)) {
                        t.setType(TokenTypes.FUNCTION);
                    }
                }
            }
            keyPosition = "{".equals(lexeme) || ",".equals(lexeme);
            previous = lexeme;
        }
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

    /** True when the parenthesis at {@code open} closes before an object body brace. */
    private static boolean methodBodyFollows(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            String text = tokens.get(i).getLexeme();
            if ("(".equals(text)) {
                depth++;
            } else if (")".equals(text)) {
                depth--;
                if (depth == 0) {
                    return i + 1 < tokens.size() && "{".equals(tokens.get(i + 1).getLexeme());
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
