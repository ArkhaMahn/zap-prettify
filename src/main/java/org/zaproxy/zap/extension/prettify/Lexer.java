package org.zaproxy.zap.extension.prettify;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Character classification built on RSTA's own token makers.
 *
 * <p>Tokenizing the whole body lets the pretty-printers treat structural punctuation (braces,
 * brackets, semicolons, commas) correctly without ad-hoc string/regular-expression heuristics: any
 * character inside a string, template literal, regular expression, comment or markup attribute
 * value is marked "opaque" and copied verbatim.
 */
final class Lexer {

    /**
     * Longest piece handed to a token maker in one call. RSTA's token list grows in tiny
     * increments and copies the whole list on every growth, so tokenizing a minified body as one
     * huge line costs time quadratic in its length. Longer lines are tokenized in chunks with the
     * carried state.
     */
    private static final int TOKENIZE_CHUNK = 8192;

    /** String / character / template / regular-expression literals and markup attribute values. */
    final boolean[] literal;

    /** {@link #literal} spans plus comments and CDATA / DTD / processing-instruction blocks. */
    final boolean[] opaque;

    /** Single-line comments (so the formatter can force a line break after them). */
    final boolean[] lineComment;

    /** Markup tag delimiters such as {@code <}, {@code </} and {@code >}. */
    final boolean[] tagDelim;

    private Lexer(int length) {
        literal = new boolean[length];
        opaque = new boolean[length];
        lineComment = new boolean[length];
        tagDelim = new boolean[length];
    }

    /**
     * Tokenizes {@code body} with the token maker for {@code styleKey}.
     *
     * @return the classification, or {@code null} if no token maker is available or tokenization
     *     failed (callers then leave the body unchanged rather than risk corrupting it).
     */
    static Lexer of(String styleKey, String body) {
        TokenMaker tm;
        try {
            tm = TokenMakerFactory.getDefaultInstance().getTokenMaker(styleKey);
        } catch (RuntimeException e) {
            return null;
        }
        if (tm == null) {
            return null;
        }
        Lexer lex = new Lexer(body.length());
        int n = body.length();
        int state = 0;
        int lineStart = 0;
        int pos = 0;
        try {
            while (pos <= n) {
                int nl = body.indexOf('\n', pos);
                int end = (nl < 0) ? n : nl;
                int len = end - pos;
                char[] arr = new char[len];
                if (len > 0) {
                    body.getChars(pos, end, arr, 0);
                }
                int chunkFrom = 0;
                int inState = state;
                boolean lastChunk;
                do {
                    int chunkTo = Math.min(len, chunkFrom + TOKENIZE_CHUNK);
                    // Do not cut a surrogate pair in half; a lone surrogate would tokenize as an
                    // error.
                    if (chunkTo < len && Character.isHighSurrogate(arr[chunkTo - 1])) {
                        chunkTo++;
                    }
                    lastChunk = chunkTo >= len;
                    Token t =
                            tm.getTokenList(
                                    new Segment(arr, chunkFrom, chunkTo - chunkFrom),
                                    inState,
                                    lineStart + chunkFrom);
                    int endTokenType = inState;
                    int lastTokenType = inState;
                    boolean sawEndToken = false;
                    for (Token x = t; x != null; x = x.getNextToken()) {
                        int type = x.getType();
                        if (type == TokenTypes.NULL) {
                            endTokenType = type;
                            sawEndToken = true;
                            continue;
                        }
                        lastTokenType = type;
                        int length = x.length();
                        if (length <= 0) {
                            continue;
                        }
                        int from = Math.max(0, x.getOffset() - lineStart);
                        int to = Math.min(len, from + length);
                        if (isLiteral(type)) {
                            mark(lex.literal, pos, from, to);
                            mark(lex.opaque, pos, from, to);
                        } else if (isComment(type)) {
                            mark(lex.opaque, pos, from, to);
                            if (type == TokenTypes.COMMENT_EOL) {
                                mark(lex.lineComment, pos, from, to);
                            }
                        } else if (type == TokenTypes.MARKUP_TAG_DELIMITER) {
                            mark(lex.tagDelim, pos, from, to);
                        }
                    }
                    // A cut inside the line continues from the token maker's own state; the end of
                    // a real line keeps the last token type, as before, because some makers treat
                    // an end-of-line comment state differently on the following line.
                    state = lastChunk
                            ? lastTokenType
                            : (sawEndToken ? endTokenType : lastTokenType);
                    inState = state;
                    chunkFrom = chunkTo;
                } while (!lastChunk);
                lineStart += len + 1;
                if (nl < 0) {
                    break;
                }
                pos = end + 1;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return lex;
    }

    private static void mark(boolean[] mask, int base, int from, int to) {
        int lo = base + from;
        int hi = base + to;
        if (lo < 0) {
            lo = 0;
        }
        if (hi > mask.length) {
            hi = mask.length;
        }
        for (int i = lo; i < hi; i++) {
            mask[i] = true;
        }
    }

    private static boolean isLiteral(int type) {
        switch (type) {
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE:
            case TokenTypes.LITERAL_CHAR:
            case TokenTypes.LITERAL_BACKQUOTE:
            case TokenTypes.REGEX:
            case TokenTypes.VARIABLE:
            case TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE:
                return true;
            default:
                return false;
        }
    }

    private static boolean isComment(int type) {
        switch (type) {
            case TokenTypes.COMMENT_EOL:
            case TokenTypes.COMMENT_MULTILINE:
            case TokenTypes.COMMENT_DOCUMENTATION:
            case TokenTypes.COMMENT_KEYWORD:
            case TokenTypes.COMMENT_MARKUP:
            case TokenTypes.MARKUP_COMMENT:
            case TokenTypes.MARKUP_CDATA:
            case TokenTypes.MARKUP_CDATA_DELIMITER:
            case TokenTypes.MARKUP_DTD:
            case TokenTypes.MARKUP_PROCESSING_INSTRUCTION:
                return true;
            default:
                return false;
        }
    }
}
