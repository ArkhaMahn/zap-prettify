package org.zaproxy.zap.extension.prettify;

import java.util.Locale;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Token maker for a full HTTP message (or its header block). It colours the first line, the headers
 * (including cookies) and, for a full message, delegates the body to the appropriate RSTA token
 * maker selected from the {@code Content-Type} header.
 *
 * <p>The wrap applied by {@link DisplayTextSplitter} inserts hard line breaks into overlong logical
 * lines. A wrapping continuation therefore starts in the middle of an HTTP construct (a header
 * value, a cookie value, a request target, ...). To colour those continuation rows correctly this
 * token maker is a small streaming lexer: the token type of the zero-length end-of-line token
 * carries the lexer state at the cut point, so the next display line can resume the construct
 * instead of mis-reading the fragment as a brand new header. {@link PrettifyDocument} supplies the
 * complementary information - whether a line actually continues the previous one - by resetting the
 * carried state to {@link #S_HDR_START} at every real (non-continuation) line boundary.
 */
public abstract class HttpTokenMaker extends AbstractTokenMaker {

    /** State meaning "the next line starts a fresh header line (or the blank line before a body)". */
    static final int S_HDR_START = -1;

    /** State meaning "still inside the request method (or between method and target)". */
    static final int S_REQ_METHOD = -2;

    /** State meaning "still inside the request-target path". */
    static final int S_REQ_TARGET = -3;

    /** State meaning "inside a query-string parameter name". */
    static final int S_REQ_QUERY_NAME = -4;

    /** State meaning "inside a query-string parameter value". */
    static final int S_REQ_QUERY_VALUE = -5;

    /** State meaning "inside the HTTP version / trailing part of the first line". */
    static final int S_REQ_VERSION = -6;

    /** State meaning "inside a response status line". */
    static final int S_RES_LINE = -7;

    /** State meaning "inside a header field name". */
    static final int S_HDR_NAME = -8;

    /** State meaning "inside a plain header field value". */
    static final int S_HDR_VALUE = -9;

    /** State meaning "inside a cookie attribute name". */
    static final int S_COOKIE_NAME = -10;

    /** State meaning "inside a cookie attribute value". */
    static final int S_COOKIE_VALUE = -11;

    /**
     * Base used to encode the body delegate state in the end-of-line token type. Values are kept
     * negative because RSTA only treats tokens with a positive type as paintable/measurable, so a
     * synthetic (non-token-type) state must never be positive.
     */
    static final int BODY_BASE = -13_000_000;

    /** Any line state at or below this value is an encoded body state. */
    static final int BODY_DETECT = -11_000_000;

    private final boolean response;
    private final boolean headersOnly;

    private char[] arr;
    private int lineOff;
    private int lineStart;

    private final StringBuilder hdrName = new StringBuilder();
    private final StringBuilder contentTypeValue = new StringBuilder();
    private boolean contentTypeHeader;
    private String bodyContentType;

    private TokenMaker bodyTokenMaker;
    private boolean bodyResolved;
    private String bodyStyleKey;

    /**
     * The content type changed since the body delegate was resolved, so a decisive new type
     * supersedes it. Set by {@link #finalizeContentType()}, consumed by
     * {@link #getBodyTokenMaker(Segment)}.
     */
    private boolean bodyContentTypeStale;

    protected HttpTokenMaker(boolean response, boolean headersOnly) {
        this.response = response;
        this.headersOnly = headersOnly;
    }

    /**
     * Maps a carried line state to the state that a following real (non-continuation) line must
     * start from. Header-region construct states collapse to a fresh header line; body delegate
     * states are passed through unchanged so the delegate keeps its own multi-line context.
     */
    static int resetAfterLine(int state) {
        return state <= BODY_DETECT ? state : S_HDR_START;
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
            int state =
                    response
                            ? tokenizeStatusLine(lineOff, end)
                            : tokenizeRequestLine(lineOff, end, S_REQ_METHOD);
            endLine(state);
            return firstToken;
        }

        if (isRequestLineState(initialTokenType)) {
            endLine(tokenizeRequestLine(lineOff, end, initialTokenType));
            return firstToken;
        }
        if (initialTokenType == S_RES_LINE) {
            endLine(tokenizeStatusLine(lineOff, end));
            return firstToken;
        }

        // Header block: either a fresh header line or a continuation of a header construct.
        if (initialTokenType == S_HDR_START) {
            if (isBlank(lineOff, end)) {
                // Blank line separating headers from body.
                finalizeContentType();
                endLine(headersOnly ? S_HDR_START : BODY_BASE);
                return firstToken;
            }
            hdrName.setLength(0);
        }

        endLine(tokenizeHeaderFragment(lineOff, end, initialTokenType));
        return firstToken;
    }

    private static boolean isRequestLineState(int state) {
        switch (state) {
            case S_REQ_METHOD:
            case S_REQ_TARGET:
            case S_REQ_QUERY_NAME:
            case S_REQ_QUERY_VALUE:
            case S_REQ_VERSION:
                return true;
            default:
                return false;
        }
    }

    // -- Request line -------------------------------------------------------------------------

    /**
     * Tokenises a request line, or the continuation of one, starting in {@code state}. Returns the
     * lexer state at the end of the fragment so the next display line can resume.
     */
    private int tokenizeRequestLine(int p, int end, int state) {
        while (p < end) {
            if (state == S_REQ_METHOD || state == S_REQ_TARGET || state == S_REQ_VERSION) {
                boolean stopAtQuery = state == S_REQ_TARGET;
                int s = p;
                while (p < end && !isSpace(arr[p]) && !(stopAtQuery && arr[p] == '?')) {
                    p++;
                }
                emit(s, p - 1, PrettifyTokenTypes.HTTP_FIRST_LINE);
                if (p >= end) {
                    break;
                }
                if (stopAtQuery && arr[p] == '?') {
                    emit(p, p, PrettifyTokenTypes.HTTP_FIRST_LINE);
                    p++;
                    state = S_REQ_QUERY_NAME;
                } else {
                    int ws = p;
                    while (p < end && isSpace(arr[p])) {
                        p++;
                    }
                    emit(ws, p - 1, TokenTypes.WHITESPACE);
                    state = (state == S_REQ_METHOD) ? S_REQ_TARGET : S_REQ_VERSION;
                }
            } else if (state == S_REQ_QUERY_NAME) {
                int s = p;
                while (p < end && arr[p] != '=' && arr[p] != '&' && !isSpace(arr[p])) {
                    p++;
                }
                emit(s, p - 1, PrettifyTokenTypes.HTTP_PARAMETER_NAME);
                if (p >= end) {
                    break;
                }
                char c = arr[p];
                if (c == '=') {
                    emit(p, p, TokenTypes.OPERATOR);
                    p++;
                    state = S_REQ_QUERY_VALUE;
                } else if (c == '&') {
                    emit(p, p, TokenTypes.SEPARATOR);
                    p++;
                    state = S_REQ_QUERY_NAME;
                } else {
                    p = consumeTrailingSpaces(p, end);
                    state = S_REQ_VERSION;
                }
            } else if (state == S_REQ_QUERY_VALUE) {
                int s = p;
                while (p < end && arr[p] != '&' && !isSpace(arr[p])) {
                    p++;
                }
                emit(s, p - 1, PrettifyTokenTypes.HTTP_PARAMETER_VALUE);
                if (p >= end) {
                    break;
                }
                if (arr[p] == '&') {
                    emit(p, p, TokenTypes.SEPARATOR);
                    p++;
                    state = S_REQ_QUERY_NAME;
                } else {
                    // The version follows the query: keep it, so that it is tokenized as part of
                    // the request line instead of being swallowed as trailing whitespace.
                    p = consumeTrailingSpaces(p, end);
                    state = S_REQ_VERSION;
                }
            } else {
                break;
            }
        }
        return state;
    }

    /**
     * Emits the whitespace run from {@code p} and returns the position after it, so that what
     * follows (the HTTP version) is tokenized as part of the request line.
     */
    private int consumeTrailingSpaces(int p, int end) {
        int ws = p;
        while (p < end && isSpace(arr[p])) {
            p++;
        }
        emit(ws, p - 1, TokenTypes.WHITESPACE);
        return p;
    }

    // -- Response status line -----------------------------------------------------------------

    private int tokenizeStatusLine(int p, int end) {
        while (p < end) {
            int s = p;
            while (p < end && !isSpace(arr[p])) {
                p++;
            }
            emit(s, p - 1, PrettifyTokenTypes.HTTP_FIRST_LINE);
            int ws = p;
            while (p < end && isSpace(arr[p])) {
                p++;
            }
            emit(ws, p - 1, TokenTypes.WHITESPACE);
        }
        return S_RES_LINE;
    }

    // -- Headers ------------------------------------------------------------------------------

    private int tokenizeHeaderFragment(int p, int end, int state) {
        if (state == S_HDR_START || state == S_HDR_NAME) {
            if (state == S_HDR_START) {
                int ws = p;
                while (p < end && isSpace(arr[p])) {
                    p++;
                }
                emit(ws, p - 1, TokenTypes.WHITESPACE);
            }
            int s = p;
            while (p < end && arr[p] != ':') {
                hdrName.append(arr[p]);
                p++;
            }
            emit(s, p - 1, PrettifyTokenTypes.HTTP_HEADER_NAME);
            if (p >= end) {
                return S_HDR_NAME;
            }
            // ':'
            emit(p, p, TokenTypes.SEPARATOR);
            p++;
            state = startHeaderValue();
            if (state == S_COOKIE_NAME) {
                int ws = p;
                while (p < end && isSpace(arr[p])) {
                    p++;
                }
                emit(ws, p - 1, TokenTypes.WHITESPACE);
            }
        }

        if (state == S_HDR_VALUE) {
            int ws = p;
            while (p < end && isSpace(arr[p])) {
                p++;
            }
            emit(ws, p - 1, TokenTypes.WHITESPACE);
            if (contentTypeHeader && p < end) {
                contentTypeValue.append(arr, p, end - p);
            }
            emit(p, end - 1, PrettifyTokenTypes.HTTP_HEADER_VALUE);
            return S_HDR_VALUE;
        }

        if (state == S_COOKIE_NAME || state == S_COOKIE_VALUE) {
            return tokenizeCookieFragment(p, end, state);
        }

        emit(p, end - 1, PrettifyTokenTypes.HTTP_HEADER_VALUE);
        return S_HDR_VALUE;
    }

    /** Decides, from the accumulated header field name, how its value is tokenised. */
    private int startHeaderValue() {
        String name = hdrName.toString().trim().toLowerCase(Locale.ROOT);
        if ("cookie".equals(name) || "set-cookie".equals(name)) {
            finalizeContentType();
            contentTypeHeader = false;
            return S_COOKIE_NAME;
        }
        boolean isContentType = "content-type".equals(name);
        if (!isContentType) {
            finalizeContentType();
        }
        contentTypeHeader = isContentType;
        if (isContentType) {
            contentTypeValue.setLength(0);
        }
        return S_HDR_VALUE;
    }

    /**
     * Snapshots the value of the content-type header, if one is currently being read, so the body
     * delegate can be chosen later even when other headers (such as content-length) follow it.
     */
    private void finalizeContentType() {
        if (contentTypeHeader) {
            bodyContentType = contentTypeValue.toString().trim();
            bodyContentTypeStale = true;
        }
    }

    private int tokenizeCookieFragment(int p, int end, int state) {
        while (p < end) {
            if (state == S_COOKIE_NAME) {
                int ws = p;
                while (p < end && isSpace(arr[p])) {
                    p++;
                }
                emit(ws, p - 1, TokenTypes.WHITESPACE);
                int s = p;
                while (p < end && arr[p] != '=' && arr[p] != ';') {
                    p++;
                }
                emit(s, p - 1, PrettifyTokenTypes.HTTP_PARAMETER_NAME);
                if (p >= end) {
                    break;
                }
                if (arr[p] == '=') {
                    emit(p, p, TokenTypes.OPERATOR);
                    p++;
                    state = S_COOKIE_VALUE;
                } else {
                    emit(p, p, TokenTypes.SEPARATOR);
                    p = skipCookieSpaces(p + 1, end);
                    state = S_COOKIE_NAME;
                }
            } else {
                int s = p;
                while (p < end && arr[p] != ';') {
                    p++;
                }
                emit(s, p - 1, PrettifyTokenTypes.HTTP_PARAMETER_VALUE);
                if (p >= end) {
                    break;
                }
                emit(p, p, TokenTypes.SEPARATOR);
                p = skipCookieSpaces(p + 1, end);
                state = S_COOKIE_NAME;
            }
        }
        return state;
    }

    /** Emits the whitespace that follows a cookie ';' separator and returns the next position. */
    private int skipCookieSpaces(int p, int end) {
        int ws = p;
        while (p < end && isSpace(arr[p])) {
            p++;
        }
        emit(ws, p - 1, TokenTypes.WHITESPACE);
        return p;
    }

    // -- Body ---------------------------------------------------------------------------------

    private Token tokenizeBody(Segment text, int delegateState, int startOffset) {
        boolean reResolved = resolveBodyDelegate(text);
        if (bodyTokenMaker == null) {
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

        // A delegate re-resolved on this line must start fresh: the carried state belongs to the
        // delegate it was produced by, and a different kind cannot pick it up.
        int state = reResolved ? 0 : delegateState;
        Token list = bodyTokenMaker.getTokenList(text, state, startOffset);
        Token t = list;
        while (t != null) {
            int len = t.length();
            char[] ta = t.getTextArray();
            if (len > 0 && ta != null) {
                addToken(ta, t.getTextOffset(), t.getTextOffset() + len - 1, t.getType(), t.getOffset());
            }
            t = t.getNextToken();
        }
        // Carry the delegate's line state, not the last token type: a string cut by a wrap break is
        // signalled through the state (the ContinuedStringTokenMaker marker), not the token list.
        // The wrapped delegate records the state while it builds the list; asking it again would
        // tokenize the whole body line a second time.
        int last =
                bodyTokenMaker instanceof ContinuedStringTokenMaker
                        ? ((ContinuedStringTokenMaker) bodyTokenMaker).lastState()
                        : bodyTokenMaker.getLastTokenTypeOnLine(text, state);
        endLine(BODY_BASE - last);
        return firstToken;
    }

    /**
     * The token maker for the body, resolved when the first body line asks for it and re-resolved
     * when a content type header read later makes the body's type decisive, so that a body typed
     * before its {@code Content-Type:} header is keyworded once the header names a styled type.
     * A request body highlights whatever the header names, and urlencoded form parameters when it
     * names no styled type. A response body keeps the sniff.
     *
     * <p>A type the header leaves undetermined keeps what the initial resolution decided: the
     * sniff reads the leading text of the whole body, and re-sniffing line by line would only
     * colour a sniffed body worse.
     *
     * @return whether the delegate was swapped on this line
     */
    private boolean resolveBodyDelegate(Segment firstLine) {
        if (!bodyResolved) {
            bodyResolved = true;
            bodyContentTypeStale = false;
            resetBodyDelegate(
                    response
                            ? ContentType.styleKeyFor(bodyContentType, prefixOf(firstLine))
                            : ContentType.styleKeyForRequestBody(bodyContentType));
            // A body always starts fresh, so the caller keeps the carried state as it is.
            return false;
        }
        if (bodyContentTypeStale) {
            bodyContentTypeStale = false;
            String typeKey = ContentType.styleKeyFor(bodyContentType);
            if (!ContentType.PLAIN_STYLE.equals(typeKey) && !typeKey.equals(bodyStyleKey)) {
                return resetBodyDelegate(typeKey);
            }
        }
        return false;
    }

    /**
     * Identity of the resolved body delegate, used to key the splitter's per-line token cache, or
     * null while the headers (and therefore the delegate) are still being read. Plain bodies are not
     * cached because they are cheap to tokenize.
     */
    String bodyCacheContext() {
        return bodyResolved && bodyTokenMaker != null ? "body:" + bodyStyleKey : null;
    }

    /** Whether the resolved body delegate is a script token maker (JavaScript or TypeScript). */
    boolean bodyIsScript() {
        return ContentType.isScriptStyle(bodyStyleKey);
    }

    /**
     * Builds the delegate a resolved style key asks for and records the key. A plain (or
     * undetermined) body has no delegate: whole-line tokens of a tokenless body are painted as
     * identifiers.
     */
    private boolean resetBodyDelegate(String styleKey) {
        boolean changed = !java.util.Objects.equals(styleKey, bodyStyleKey);
        bodyStyleKey = styleKey;
        if (styleKey == null || ContentType.PLAIN_STYLE.equals(styleKey)) {
            bodyTokenMaker = null;
        } else if (ContentType.FORM_STYLE.equals(styleKey)) {
            bodyTokenMaker = new FormUrlEncodedTokenMaker();
        } else {
            // Wrap the delegate so a string that a wrap break cuts in half stays a string on the
            // continuation rows, exactly as it does for a body-only view whose document token
            // maker is itself wrapped.
            bodyTokenMaker = new ContinuedStringTokenMaker(
                    PrettifyTokenMakerFactory.bodyTokenMaker(styleKey));
        }
        return changed;
    }

    /** The body's leading text, used to sniff a style when the content type is not decisive. */
    private static String prefixOf(Segment text) {
        if (text == null || text.count <= 0) {
            return "";
        }
        return new String(text.array, text.offset, Math.min(text.count, 4096));
    }

    // -- Shared helpers -----------------------------------------------------------------------

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

    private boolean isBlank(int start, int end) {
        for (int p = start; p < end; p++) {
            if (!isSpace(arr[p])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }
}
