package org.zaproxy.zap.extension.prettify;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
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
        // Keep a string that a chunk cut leaves open in the string colour on the next chunk, exactly
        // as the splitter's own tokenization does. The wrapper also reports the token maker's real
        // carried state, which is what a chunk boundary needs.
        ContinuedStringTokenMaker wrapped = new ContinuedStringTokenMaker(tm);
        tm = wrapped;
        boolean script = ContentType.isScriptStyle(styleKey);
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
                boolean done = false;
                while (!done) {
                    int chunkTo = Math.min(len, chunkFrom + TokenizeChunks.SIZE);
                    // Do not cut a surrogate pair in half; a lone surrogate would tokenize as an
                    // error.
                    if (chunkTo < len && Character.isHighSurrogate(arr[chunkTo - 1])) {
                        chunkTo++;
                    }
                    chunkTo = TokenizeChunks.adjustForSlash(body, end, pos + chunkTo) - pos;
                    chunkTo =
                            TokenizeChunks.extendForRegex(body, pos + chunkFrom, end, pos + chunkTo)
                                    - pos;
                    boolean retry;
                    do {
                        retry = false;
                        boolean lastChunk = chunkTo >= len;
                        Token t =
                                tm.getTokenList(
                                        new Segment(arr, chunkFrom, chunkTo - chunkFrom),
                                        inState,
                                        lineStart + chunkFrom);
                        Token lastSignificant = null;
                        int lastPaintableType = -1;
                        int lastPaintableEnd = -1;
                        boolean unclosedRegex = false;
                        for (Token x = t; x != null; x = x.getNextToken()) {
                            int type = x.getType();
                            if (type == TokenTypes.NULL) {
                                continue;
                            }
                            int length = x.length();
                            if (length <= 0) {
                                continue;
                            }
                            int from = Math.max(0, x.getOffset() - lineStart);
                            int to = Math.min(len, from + length);
                            if (script
                                    && type == TokenTypes.OPERATOR
                                    && length == 1
                                    && x.charAt(0) == '/'
                                    && RSyntaxUtilities.regexCanFollowInJavaScript(lastSignificant)) {
                                int closer =
                                        TokenizeChunks.findClosingSlash(
                                                body, x.getOffset() + 1, end);
                                if (closer >= pos + chunkTo) {
                                    chunkTo = closer + 1 - pos;
                                    retry = true;
                                    break;
                                }
                                if (closer < 0) {
                                    // Unclosed literal: mark the rest directly instead of letting
                                    // the token maker read it as code and grow its token list
                                    // quadratically.
                                    mark(lex.literal, pos, from, len);
                                    mark(lex.opaque, pos, from, len);
                                    state = 0;
                                    inState = 0;
                                    chunkFrom = len;
                                    unclosedRegex = true;
                                    break;
                                }
                            }
                            lastPaintableType = type;
                            lastPaintableEnd = to;
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
                            if (x.isPaintable() && !x.isComment() && !x.isWhitespace()) {
                                lastSignificant = x;
                            }
                        }
                        if (retry) {
                            continue;
                        }
                        if (unclosedRegex) {
                            done = true;
                            break;
                        }
                        if (!lastChunk
                                && lastPaintableType == TokenTypes.COMMENT_EOL
                                && lastPaintableEnd >= chunkTo) {
                            // A line comment runs to the end of the logical line. The token maker
                            // sees the end of the chunk as the end of the line, so mark the rest
                            // directly instead of relying on a carried state.
                            mark(lex.opaque, pos, chunkTo, len);
                            mark(lex.lineComment, pos, chunkTo, len);
                            state = 0;
                            inState = 0;
                            chunkFrom = len;
                            done = true;
                            break;
                        }
                        // The wrapper reports the state the chunk leaves behind: the token maker's
                        // own carried state, or a marker for a string the cut left open.
                        state = wrapped.lastState();
                        inState = state;
                        chunkFrom = chunkTo;
                        if (lastChunk) {
                            done = true;
                        }
                    } while (retry);
                }
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
