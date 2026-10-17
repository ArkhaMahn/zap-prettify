package org.zaproxy.zap.extension.prettify;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Token maker for a full HTTP message (or its header block). It colours the first line, the headers
 * (including cookies) and, for a full message, delegates the body to the appropriate RSTA token
 * maker selected from the {@code Content-Type} header.
 *
 * <p>The header/body boundary is carried from one line to the next through the token type of the
 * zero-length end-of-line token returned by {@link #getTokenList}.
 */
public abstract class HttpTokenMaker extends AbstractTokenMaker {

    /** State meaning "the next line is still part of the header block". */
    private static final int HEADERS_STATE = -1;

    /**
     * Base used to encode the body delegate state in the end-of-line token type. Values are kept
     * negative because RSTA only treats tokens with a positive type as paintable/measurable, so a
     * synthetic (non-token-type) state must never be positive.
     */
    private static final int BODY_BASE = -10_000_000;

    /** Any line state at or below this value is an encoded body state. */
    private static final int BODY_DETECT = -9_000_000;

    private final boolean response;
    private final boolean headersOnly;

    private char[] arr;
    private int lineOff;
    private int lineStart;

    private String bodyStyleKey;
    private TokenMaker bodyTokenMaker;
    private boolean bodyResolved;

    protected HttpTokenMaker(boolean response, boolean headersOnly) {
        this.response = response;
        this.headersOnly = headersOnly;
    }

    @Override
    public TokenMap getWordsToHighlight() {
        return null;
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();

        this.arr = text.array;
        this.lineOff = text.offset;
        this.lineStart = startOffset;
        int end = text.offset + text.count;

        if (initialTokenType <= BODY_DETECT) {
            return tokenizeBody(text, BODY_BASE - initialTokenType, startOffset);
        }

        if (initialTokenType == 0) {
            // First line of the document: reset any body content-type detected previously.
            this.bodyStyleKey = null;
            this.bodyTokenMaker = null;
            this.bodyResolved = false;

            if (response) {
                tokenizeResponseLine(end);
            } else {
                tokenizeRequestLine(end);
            }
            endLine(HEADERS_STATE);
        } else if (!headersOnly && isBlank(text.offset, end)) {
            // Blank line separating headers from body.
            endLine(BODY_BASE);
        } else {
            tokenizeHeader(text.offset, end);
            endLine(HEADERS_STATE);
        }

        return firstToken;
    }

    private void tokenizeRequestLine(int end) {
        int p = lineOff;
        int e1 = indexOfSpace(p, end);
        emit(p, e1 - 1, BurpTokenTypes.HTTP_FIRST_LINE);
        p = skipSpaces(e1, end, true);

        int e2 = indexOfSpace(p, end);
        tokenizeTarget(p, e2);
        p = skipSpaces(e2, end, true);

        if (p < end) {
            emit(p, end - 1, BurpTokenTypes.HTTP_FIRST_LINE);
        }
    }

    private void tokenizeTarget(int start, int end) {
        if (start >= end) {
            return;
        }
        int pathEnd = end;
        int qm = indexOf('?', start, end);
        int hash = indexOf('#', start, end);
        if (qm >= 0 && qm < pathEnd) {
            pathEnd = qm;
        }
        if (hash >= 0 && hash < pathEnd) {
            pathEnd = hash;
        }

        emit(start, pathEnd - 1, BurpTokenTypes.HTTP_FIRST_LINE);
        if (pathEnd >= end) {
            return;
        }

        char c = arr[pathEnd];
        emit(pathEnd, pathEnd, BurpTokenTypes.HTTP_FIRST_LINE);
        int rest = pathEnd + 1;
        if (c == '?') {
            int frag = (hash >= 0) ? hash : end;
            tokenizeQuery(rest, frag);
            if (frag < end) {
                emit(frag, frag, BurpTokenTypes.HTTP_FIRST_LINE);
                emit(frag + 1, end - 1, BurpTokenTypes.HTTP_FIRST_LINE);
            }
        } else {
            emit(rest, end - 1, BurpTokenTypes.HTTP_FIRST_LINE);
        }
    }

    private void tokenizeQuery(int start, int end) {
        int p = start;
        while (p < end) {
            int amp = indexOf('&', p, end);
            int segEnd = (amp >= 0) ? amp : end;
            int eq = indexOf('=', p, segEnd);
            if (eq >= 0) {
                emit(p, eq - 1, BurpTokenTypes.HTTP_PARAMETER_NAME);
                emit(eq, eq, TokenTypes.OPERATOR);
                emit(eq + 1, segEnd - 1, BurpTokenTypes.HTTP_PARAMETER_VALUE);
            } else {
                emit(p, segEnd - 1, BurpTokenTypes.HTTP_PARAMETER_NAME);
            }
            if (amp >= 0) {
                emit(amp, amp, TokenTypes.SEPARATOR);
                p = amp + 1;
            } else {
                p = end;
            }
        }
    }

    private void tokenizeResponseLine(int end) {
        int p = lineOff;
        while (p < end) {
            int s = p;
            while (p < end && !isSpace(arr[p])) {
                p++;
            }
            emit(s, p - 1, BurpTokenTypes.HTTP_FIRST_LINE);
            int ws = p;
            while (p < end && isSpace(arr[p])) {
                p++;
            }
            emit(ws, p - 1, TokenTypes.WHITESPACE);
        }
    }

    private void tokenizeHeader(int start, int end) {
        int p = start;
        while (p < end && isSpace(arr[p])) {
            p++;
        }
        emit(start, p - 1, TokenTypes.WHITESPACE);

        int colon = indexOf(':', p, end);
        if (colon < 0) {
            emit(p, end - 1, BurpTokenTypes.HTTP_HEADER_NAME);
            return;
        }
        emit(p, colon - 1, BurpTokenTypes.HTTP_HEADER_NAME);
        emit(colon, colon, TokenTypes.SEPARATOR);

        int vs = colon + 1;
        while (vs < end && isSpace(arr[vs])) {
            vs++;
        }
        emit(colon + 1, vs - 1, TokenTypes.WHITESPACE);

        if (vs >= end) {
            return;
        }
        String name = new String(arr, p, colon - p).trim().toLowerCase();
        if ("cookie".equals(name) || "set-cookie".equals(name)) {
            tokenizeCookies(vs, end);
        } else {
            emit(vs, end - 1, BurpTokenTypes.HTTP_HEADER_VALUE);
        }
        if ("content-type".equals(name)) {
            this.bodyStyleKey = ContentType.styleKeyFor(new String(arr, vs, end - vs).trim());
        }
    }

    private void tokenizeCookies(int start, int end) {
        int p = start;
        while (p < end) {
            int semi = indexOf(';', p, end);
            int segEnd = (semi >= 0) ? semi : end;

            int s = p;
            while (s < segEnd && isSpace(arr[s])) {
                s++;
            }
            emit(p, s - 1, TokenTypes.WHITESPACE);

            int e = segEnd;
            while (e > s && isSpace(arr[e - 1])) {
                e--;
            }
            if (e > s) {
                int eq = indexOf('=', s, e);
                if (eq >= 0) {
                    emit(s, eq - 1, BurpTokenTypes.HTTP_PARAMETER_NAME);
                    emit(eq, eq, TokenTypes.OPERATOR);
                    emit(eq + 1, e - 1, BurpTokenTypes.HTTP_PARAMETER_VALUE);
                } else {
                    emit(s, e - 1, BurpTokenTypes.HTTP_PARAMETER_NAME);
                }
            }
            if (semi >= 0) {
                emit(semi, semi, TokenTypes.SEPARATOR);
                p = semi + 1;
            } else {
                p = end;
            }
        }
    }

    private Token tokenizeBody(Segment text, int delegateState, int startOffset) {
        TokenMaker dm = getBodyTokenMaker();
        if (dm == null) {
            if (text.count > 0) {
                addToken(
                        text.array,
                        text.offset,
                        text.offset + text.count - 1,
                        TokenTypes.IDENTIFIER,
                        startOffset);
            }
            endLine(BODY_BASE);
            return firstToken;
        }

        Token list = dm.getTokenList(text, delegateState, startOffset);
        int state = 0;
        Token t = list;
        while (t != null) {
            int len = t.length();
            char[] ta = t.getTextArray();
            if (len > 0 && ta != null) {
                addToken(ta, t.getTextOffset(), t.getTextOffset() + len - 1, t.getType(), t.getOffset());
            }
            state = t.getType();
            t = t.getNextToken();
        }
        endLine(BODY_BASE - state);
        return firstToken;
    }

    private TokenMaker getBodyTokenMaker() {
        if (!bodyResolved) {
            bodyResolved = true;
            if (bodyStyleKey == null || "text/plain".equals(bodyStyleKey)) {
                bodyTokenMaker = null;
            } else if ("prettify/form".equals(bodyStyleKey)) {
                bodyTokenMaker = new FormUrlEncodedTokenMaker();
            } else {
                bodyTokenMaker = TokenMakerFactory.getDefaultInstance().getTokenMaker(bodyStyleKey);
            }
        }
        return bodyTokenMaker;
    }

    private void endLine(int state) {
        addNullToken();
        if (currentToken != null) {
            currentToken.setType(state);
        }
    }

    private void emit(int start, int endInclusive, int type) {
        if (endInclusive < start) {
            return;
        }
        addToken(arr, start, endInclusive, type, lineStart + (start - lineOff));
    }

    private int skipSpaces(int p, int end, boolean emitWhitespace) {
        int s = p;
        while (p < end && isSpace(arr[p])) {
            p++;
        }
        if (emitWhitespace) {
            emit(s, p - 1, TokenTypes.WHITESPACE);
        }
        return p;
    }

    private int indexOfSpace(int start, int end) {
        int p = start;
        while (p < end && !isSpace(arr[p])) {
            p++;
        }
        return p;
    }

    private int indexOf(char c, int start, int end) {
        for (int p = start; p < end; p++) {
            if (arr[p] == c) {
                return p;
            }
        }
        return -1;
    }

    private boolean isBlank(int start, int end) {
        for (int p = start; p < end; p++) {
            if (!isSpace(arr[p])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }
}
