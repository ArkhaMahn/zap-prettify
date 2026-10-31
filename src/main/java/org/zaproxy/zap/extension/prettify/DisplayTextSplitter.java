package org.zaproxy.zap.extension.prettify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Inserts hard line breaks into overlong single lines so the text area never has to lay out an
 * extremely long logical line.
 *
 * <p>A minified payload can be a single line hundreds of thousands of characters long. The wrapped
 * view has to compute every wrap position for that logical line, which is slow and can misalign
 * rows. This splitter pre-breaks such lines into short display lines while keeping the original text
 * and the exact offsets of every inserted break, so search/copy can map back to the untouched
 * message.
 *
 * <p>When a target column count is supplied the breaks are aligned to the wrap width so the visible
 * text still fills each row; otherwise a fixed chunk length is used.
 *
 * <p>Adapted from the Pretty View add-on's {@code DisplayLineSplitter}.
 */
public final class DisplayTextSplitter {

    /** Preferred chunk length for breaks outside a string/comment. */
    public static final int CHUNK_CHARS = 2000;

    /** Hard maximum chunk length; used while inside a string or comment. */
    public static final int MAX_CHUNK_CHARS = 4000;

    private static final int NONE = 0;
    private static final int LINE_COMMENT = 1;
    private static final int BLOCK_COMMENT = 2;

    /** Minimum line length worth caching; shorter lines tokenize faster than a cache lookup. */
    private static final int MIN_CACHED_LINE = 64;

    /** Longest leading whitespace copied onto a continuation row, in characters. */
    private static final int MAX_CONTINUATION_INDENT = 64;

    /** Approximate limit, in retained characters, of the per-line token cache. */
    private static final long LINE_CACHE_BUDGET = 16L * 1024 * 1024;

    /**
     * Text longer than this skips span recording. Recording a type and an end for every character
     * costs several times the message size in memory; a huge body is shown correctly by falling
     * back to the document's own (chunked) tokenization instead.
     */
    static final int MAX_TOKEN_SPAN_CHARS = 16 * 1024 * 1024;

    /**
     * Tokenized lines, shared by every split. The same message is split once per view (headers,
     * body, whole message), so the expensive bodies are tokenized once and reused instead of once
     * per view.
     */
    private static final LineCache LINE_CACHE = new LineCache(LINE_CACHE_BUDGET);

    private DisplayTextSplitter() {}

    /** Drops the shared token cache so an unloaded add-on leaves no retained text behind. */
    static void clearCache() {
        LINE_CACHE.clear();
    }

    /** Supplies the rendered width, in pixels, of a single code point. */
    public interface CharWidths {
        double widthOf(int codePoint);
    }

    /**
     * Splits {@code string} into display lines that are no wider than {@code maxWidth} pixels,
     * keeping whole existing lines and CR/LF pairs. Character-count wrapping is not safe for text
     * with characters the font renders wider than a typical glyph (high-latin1/binary bodies,
     * control pictures, etc.), so the break position is chosen from {@code widths}. If {@code
     * maxWidth} is not positive this falls back to {@link #split(String)}.
     */
    public static Split split(String string, int maxWidth, CharWidths widths) {
        return split(string, string, maxWidth, widths);
    }

    /**
     * Splits {@code string} into display lines like {@link #split(String, int, CharWidths)}, but
     * aware of {@code style}'s tokens so the wrapped view is coloured like the unwrapped one.
     * Breaks sit at the exact width (filling every row), and when a break falls inside a token the
     * rows it spans are recorded with that token's original type, so {@link PrettifyDocument} paints
     * the fragments correctly instead of showing whatever a per-line tokenizer would make of them.
     * When {@code style} is null (or has no token maker) the usual tokenizer-based colouring applies.
     */
    public static Split split(
            String string, String style, int maxWidth, CharWidths widths) {
        if (maxWidth <= 0 || widths == null) {
            return split(string);
        }
        if (string == null || string.isEmpty()) {
            String s = string == null ? "" : string;
            return new Split(s, s, new int[0]);
        }
        List<Integer> breakOffsets = new ArrayList<>();
        List<Integer> breakIndents = new ArrayList<>();
        List<Integer> tokenStarts = new ArrayList<>();
        List<Integer> tokenEnds = new ArrayList<>();
        List<Integer> tokenTypes = new ArrayList<>();
        // Which logical lines wrap, recorded while the display text is built. Tokenizing is only
        // needed when something actually wraps; typical messages never reach the width limit, so
        // this also means the text is walked once, not twice.
        java.util.BitSet wrappedLines = new java.util.BitSet();
        int n = string.length();
        StringBuilder display = new StringBuilder(n + n / 16 + 16);
        int lineStart = 0;
        int lineIndex = 0;
        for (int i = 0; i < n; i++) {
            // Only '\n' (and '\r\n') separates lines in the text document; a lone '\r' is ordinary
            // content. Breaking on it here would let the document merge the rows back together.
            if (string.charAt(i) == '\n') {
                appendLineByWidth(
                        string,
                        lineStart,
                        i,
                        maxWidth,
                        widths,
                        lineIndex,
                        wrappedLines,
                        display,
                        breakOffsets,
                        breakIndents);
                display.append('\n');
                lineStart = i + 1;
                lineIndex++;
            }
        }
        appendLineByWidth(
                string,
                lineStart,
                n,
                maxWidth,
                widths,
                lineIndex,
                wrappedLines,
                display,
                breakOffsets,
                breakIndents);
        // Tokenize (and record the per-row spans) only when a row was actually broken. Deferring
        // this until after the split is what lets an unwrapped message skip tokenization entirely.
        TokenInfo info =
                breakOffsets.isEmpty() ? null : tokenInfo(string, style, wrappedLines);
        if (info != null) {
            recordAllRowTokens(
                    info,
                    display,
                    toIntArray(breakOffsets),
                    toIntArray(breakIndents),
                    tokenStarts,
                    tokenEnds,
                    tokenTypes);
        }
        return new Split(
                display.toString(),
                string,
                toIntArray(breakOffsets),
                toIntArray(breakIndents),
                toIntArray(tokenStarts),
                toIntArray(tokenEnds),
                toIntArray(tokenTypes));
    }

    private static void appendLineByWidth(
            String string,
            int start,
            int end,
            int maxWidth,
            CharWidths widths,
            int lineIndex,
            java.util.BitSet wrappedLines,
            StringBuilder display,
            List<Integer> breakOffsets,
            List<Integer> breakIndents) {
        // Continuation rows are indented to the wrapped line's own indentation level so a wrapped
        // value does not snap back to column zero but stays under the block it belongs to. Cap the
        // indent at half a row: a line whose whole content is whitespace would otherwise copy the
        // entire indent onto every one-character row, growing the display quadratically.
        int indentLength =
                continuationIndentLength(string, start, end, maxWidth, widths);
        double indentWidth = 0;
        for (int k = start; k < start + indentLength; k++) {
            indentWidth += widths.widthOf(string.codePointAt(k));
        }
        int segmentStart = start;
        double width = 0;
        int i = start;
        while (i < end) {
            int cp = string.codePointAt(i);
            double cw = widths.widthOf(cp);
            // Always keep at least one code point per row, even if it alone is wider than maxWidth.
            if (i > segmentStart && width + cw > maxWidth) {
                // Fill the row to the width limit and cut here. Cutting in the middle of a token is
                // safe because recordAllRowTokens() records the closed-over token's original type
                // for each fragment, so a split token still paints with its own colour. Snapping
                // the break back to a token boundary instead would leave the row short (a header
                // name stranded before its value, or trailing empty space before the wrap).
                display.append(string, segmentStart, i);
                breakOffsets.add(display.length());
                display.append('\n');
                if (indentLength > 0) {
                    display.append(string, start, start + indentLength);
                }
                breakIndents.add(indentLength);
                wrappedLines.set(lineIndex);
                segmentStart = i;
                width = indentWidth;
            } else {
                width += cw;
                i += Character.charCount(cp);
            }
        }
        if (segmentStart < end) {
            display.append(string, segmentStart, end);
        }
    }

    /**
     * Records, for one display row, the original token type of each character it contains, as
     * display-offset ranges. {@link PrettifyDocument} replays these spans as the row's tokens, so the
     * wrapped view is coloured exactly like the unwrapped one - a token a wrap break cut in half (a
     * long regular expression, for example) keeps its colour instead of showing whatever fragments
     * the per-line tokenizer would produce.
     */
    private static void recordRowTokens(
            TokenInfo info,
            int rowStart,
            int rowEnd,
            int contentDisplayStart,
            List<Integer> tokenStarts,
            List<Integer> tokenEnds,
            List<Integer> tokenTypes) {
        if (info == null || info.tokenType == null || rowStart >= rowEnd) {
            return;
        }
        int n = info.tokenType.length;
        // A row whose line was not tokenized has no recorded types; leave it spanless so the
        // document tokenizes it lazily.
        boolean any = false;
        for (int k = rowStart; k < rowEnd; k++) {
            if (k < n && info.tokenType[k] != 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        int pos = rowStart;
        while (pos < rowEnd) {
            int type = pos < n ? (info.tokenType[pos] & 0xFF) : 0;
            int tokenEnd = pos < n && info.tokenEnd[pos] > pos ? info.tokenEnd[pos] : pos + 1;
            int fragmentEnd = Math.min(tokenEnd, rowEnd);
            if (fragmentEnd <= pos) {
                fragmentEnd = pos + 1;
            }
            // Whitespace/unknown runs are painted like whitespace, never like an error fragment.
            if (type == 0) {
                type = TokenTypes.WHITESPACE;
            }
            tokenStarts.add(contentDisplayStart + (pos - rowStart));
            tokenEnds.add(contentDisplayStart + (fragmentEnd - rowStart));
            tokenTypes.add(type);
            pos = fragmentEnd;
        }
    }

    /**
     * Records the token spans of every display row once the text has been split. Only called when
     * at least one break was inserted, so an unwrapped message never pays for it.
     */
    private static void recordAllRowTokens(
            TokenInfo info,
            StringBuilder display,
            int[] breakOffsets,
            int[] breakIndents,
            List<Integer> tokenStarts,
            List<Integer> tokenEnds,
            List<Integer> tokenTypes) {
        int n = display.length();
        int breakIndex = 0;
        int originalPos = 0;
        int lineStart = 0;
        boolean continuation = false;
        int indent = 0;
        while (lineStart <= n) {
            int nl = display.indexOf("\n", lineStart);
            int lineEnd = nl < 0 ? n : nl;
            int contentStart = Math.min(lineEnd, lineStart + (continuation ? indent : 0));
            if (continuation && indent > 0 && contentStart > lineStart) {
                // The indentation copied onto a continuation row is painted as whitespace.
                tokenStarts.add(lineStart);
                tokenEnds.add(contentStart);
                tokenTypes.add(TokenTypes.WHITESPACE);
            }
            int contentLength = lineEnd - contentStart;
            if (contentLength > 0) {
                recordRowTokens(
                        info,
                        originalPos,
                        originalPos + contentLength,
                        contentStart,
                        tokenStarts,
                        tokenEnds,
                        tokenTypes);
                originalPos += contentLength;
            }
            if (nl < 0) {
                break;
            }
            boolean injected =
                    breakIndex < breakOffsets.length && breakOffsets[breakIndex] == nl;
            if (injected) {
                continuation = true;
                indent = breakIndex < breakIndents.length ? breakIndents[breakIndex] : 0;
                breakIndex++;
            } else {
                continuation = false;
                indent = 0;
                originalPos++;
            }
            lineStart = nl + 1;
        }
    }

    /**
     * Conservative check for text that could leave the tokenizer in a carried state (a comment,
     * string, HTML/JS/CSS opener, or a line continuation). Skipped lines are assumed to end in the
     * state they started in - zero - so anything that might not is tokenized instead.
     */
    private static boolean mayCarryState(String string, int start, int end) {
        for (int i = start; i < end; i++) {
            switch (string.charAt(i)) {
                case '"':
                case '\'':
                case '`':
                case '\\':
                case '/':
                case '*':
                case '<':
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    /**
     * Tokenizes the lines of {@code text} with the same token maker the view uses for {@code style}
     * (including the HTTP message token maker, whose body delegate is chosen from the {@code
     * Content-Type} header) and records, for every character of a tokenized line, which token covers
     * it and where that token ends. This yields the token types a cut token must keep.
     *
     * <p>Only lines that need it are tokenized: a line is tokenized when it wraps, when it starts
     * inside a carried construct (state != 0), or when it could open one. Lines that cannot change
     * the carried state are skipped and left with no recorded tokens, so {@link PrettifyDocument}
     * tokenizes them lazily instead of paying for them up front. Returns null when no token maker is
     * available or tokenization fails, in which case breaks sit at the exact width.
     */
    private static TokenInfo tokenInfo(
            String text, String style, java.util.BitSet wrappedLines) {
        if (style == null || text.length() > MAX_TOKEN_SPAN_CHARS) {
            return null;
        }
        TokenMaker tokenMaker;
        try {
            tokenMaker = new PrettifyTokenMakerFactory().getTokenMaker(style);
        } catch (RuntimeException e) {
            return null;
        }
        if (tokenMaker == null) {
            return null;
        }
        boolean http = PrettifyTokenMakerFactory.isHttpStyle(style);
        boolean script = ContentType.isScriptStyle(style);
        // Match the token maker the view's document uses for this style: HTTP messages handle string
        // continuation inside the body delegate, every other style wraps its maker in
        // ContinuedStringTokenMaker so a string that spans display rows keeps the string colour.
        if (!http) {
            tokenMaker = new ContinuedStringTokenMaker(tokenMaker);
        }
        int n = text.length();
        TokenInfo info = new TokenInfo();
        info.tokenType = new byte[n];
        info.tokenEnd = new int[n];
        int state = 0;
        int pos = 0;
        int lineIndex = 0;
        try {
            while (pos <= n) {
                int nl = text.indexOf('\n', pos);
                int end = nl < 0 ? n : nl;
                // A line that cannot affect the carried state is left untokenized: it never wraps,
                // so it stays a single display row with no spans, and the document tokenizes it
                // lazily when (and only if) it is painted.
                boolean tokenize =
                        http
                                || state != 0
                                || wrappedLines.get(lineIndex)
                                || mayCarryState(text, pos, end);
                if (tokenize) {
                    int len = end - pos;
                    int inState = state;
                    String context = null;
                    String line = null;
                    LineTokens cached = null;
                    if (len >= MIN_CACHED_LINE) {
                        // HTTP bodies may only be cached once their delegate is known; header lines
                        // are cheap and their tokenization feeds the delegate choice.
                        context =
                                http
                                        ? ((HttpTokenMaker) tokenMaker).bodyCacheContext()
                                        : "";
                        if (context != null) {
                            line = text.substring(pos, end);
                            cached = LINE_CACHE.get(style, context, inState, line);
                        }
                    }
                    if (cached != null) {
                        System.arraycopy(cached.types, 0, info.tokenType, pos, len);
                        System.arraycopy(cached.ends, 0, info.tokenEnd, pos, len);
                        state = cached.outState;
                    } else {
                        state = tokenizeLine(tokenMaker, http, script, text, pos, end, inState, info);
                        if (line != null) {
                            LINE_CACHE.put(
                                    style,
                                    context,
                                    inState,
                                    line,
                                    Arrays.copyOfRange(info.tokenType, pos, end),
                                    Arrays.copyOfRange(info.tokenEnd, pos, end),
                                    state);
                        }
                    }
                } else {
                    state = 0;
                }
                if (nl < 0) {
                    break;
                }
                pos = end + 1;
                lineIndex++;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return info;
    }

    /**
     * Tokenizes the logical line {@code [start, end)} and records its token types and ends in {@code
     * info}. A line longer than {@link TokenizeChunks#SIZE} is tokenized in pieces with the token
     * maker's state carried across each cut: RSTA's token list grows in tiny increments and copies
     * the whole list on every growth, so handing it one huge line costs time quadratic in the line
     * length. A regular expression that closes beyond the cut makes the piece longer rather than
     * leaving its body to be read as code.
     *
     * @return the state the line leaves for the next line
     */
    private static int tokenizeLine(
            TokenMaker tokenMaker,
            boolean http,
            boolean script,
            String text,
            int start,
            int end,
            int inState,
            TokenInfo info) {
        int state = inState;
        int chunkStart = start;
        do {
            int chunkEnd = Math.min(end, chunkStart + TokenizeChunks.SIZE);
            // Do not cut a surrogate pair in half; a lone surrogate would tokenize as an error.
            if (chunkEnd < end && Character.isHighSurrogate(text.charAt(chunkEnd - 1))) {
                chunkEnd++;
            }
            chunkEnd = TokenizeChunks.adjustForSlash(text, end, chunkEnd);
            chunkEnd = TokenizeChunks.extendForRegex(text, chunkStart, end, chunkEnd);
            ChunkResult result =
                    tokenizeChunk(
                            tokenMaker,
                            http,
                            script,
                            text,
                            chunkStart,
                            chunkEnd,
                            end,
                            state,
                            info);
            while (result.retry) {
                chunkEnd = result.nextStart;
                result =
                        tokenizeChunk(
                                tokenMaker,
                                http,
                                script,
                                text,
                                chunkStart,
                                chunkEnd,
                                end,
                                state,
                                info);
            }
            state = result.state;
            chunkStart = result.nextStart;
        } while (chunkStart < end);
        // Only a real line end resets the HTTP header state; a chunk cut inside the line must keep
        // the construct state so the next chunk resumes it.
        return http ? HttpTokenMaker.resetAfterLine(state) : state;
    }

    /** Tokenizes one chunk of a line and returns where tokenization resumes and the state there. */
    private static ChunkResult tokenizeChunk(
            TokenMaker tokenMaker,
            boolean http,
            boolean script,
            String text,
            int start,
            int end,
            int lineEnd,
            int inState,
            TokenInfo info) {
        int len = end - start;
        char[] chars = new char[len];
        if (len > 0) {
            text.getChars(start, end, chars, 0);
        }
        Segment segment = new Segment(chars, 0, len);
        Token list = tokenMaker.getTokenList(segment, inState, start);
        boolean scriptHere =
                script
                        || (http
                                && tokenMaker instanceof HttpTokenMaker
                                && ((HttpTokenMaker) tokenMaker).bodyIsScript());
        Token nullToken = null;
        int lastType = inState;
        int lastPaintableType = -1;
        int lastPaintableEnd = -1;
        int n = text.length();
        Token lastSignificant = null;
        for (Token t = list; t != null; t = t.getNextToken()) {
            int type = t.getType();
            if (type == TokenTypes.NULL) {
                nullToken = t;
                continue;
            }
            lastType = type;
            int length = t.length();
            if (length <= 0) {
                continue;
            }
            int offset = t.getOffset();
            if (offset < 0 || offset > n) {
                continue;
            }
            int to = Math.min(n, offset + length);

            if (scriptHere
                    && type == TokenTypes.OPERATOR
                    && length == 1
                    && t.charAt(0) == '/'
                    && RSyntaxUtilities.regexCanFollowInJavaScript(lastSignificant)) {
                int closer = TokenizeChunks.findClosingSlash(text, offset + 1, lineEnd);
                if (closer >= end) {
                    // The literal closes beyond this chunk. Re-tokenize with the closing slash
                    // included; the whole literal is one token, so the larger chunk is cheap.
                    return ChunkResult.retry(closer + 1);
                }
                if (closer < 0) {
                    // An unclosed literal: mark the rest of the line directly. Feeding it to the
                    // token maker would read it as code and grow the token list quadratically.
                    for (int k = offset; k < lineEnd; k++) {
                        info.tokenType[k] = (byte) TokenTypes.REGEX;
                        info.tokenEnd[k] = lineEnd;
                    }
                    return ChunkResult.lineEnd(
                            lineEnd,
                            http && inState <= HttpTokenMaker.BODY_DETECT
                                    ? HttpTokenMaker.BODY_BASE
                                    : 0);
                }
                // The closer is inside the chunk, so the token maker saw it and still chose
                // division. Leave its decision alone.
            }

            lastPaintableType = type;
            lastPaintableEnd = to;
            for (int k = offset; k < to; k++) {
                info.tokenType[k] = (byte) type;
                info.tokenEnd[k] = to;
            }
            if (t.isPaintable() && !t.isComment() && !t.isWhitespace()) {
                lastSignificant = t;
            }
        }
        if (lastPaintableType == TokenTypes.COMMENT_EOL && lastPaintableEnd >= end) {
            // A line comment runs to the end of the logical line. The token maker sees the end of
            // the chunk as the end of the line, so fill the rest directly instead of relying on a
            // carried state.
            for (int k = end; k < lineEnd; k++) {
                info.tokenType[k] = (byte) TokenTypes.COMMENT_EOL;
                info.tokenEnd[k] = lineEnd;
            }
            return ChunkResult.lineEnd(
                    lineEnd,
                    http && inState <= HttpTokenMaker.BODY_DETECT
                            ? HttpTokenMaker.BODY_BASE
                            : 0);
        }
        // The HTTP token maker encodes its carried state in the trailing null token; every other
        // style carries the state the maker reports for the chunk (as RSTA does), which lets a
        // multi-line string or comment keep its state across rows and chunk cuts.
        int state;
        if (http) {
            state = nullToken != null ? nullToken.getType() : lastType;
        } else {
            state =
                    tokenMaker instanceof ContinuedStringTokenMaker
                            ? ((ContinuedStringTokenMaker) tokenMaker).lastState()
                            : tokenMaker.getLastTokenTypeOnLine(segment, inState);
        }
        return ChunkResult.done(end, state);
    }

    /** Result of tokenizing one chunk. */
    private static final class ChunkResult {
        final int nextStart;
        final int state;
        final boolean retry;

        private ChunkResult(int nextStart, int state, boolean retry) {
            this.nextStart = nextStart;
            this.state = state;
            this.retry = retry;
        }

        static ChunkResult done(int nextStart, int state) {
            return new ChunkResult(nextStart, state, false);
        }

        static ChunkResult retry(int nextStart) {
            return new ChunkResult(nextStart, 0, true);
        }

        static ChunkResult lineEnd(int lineEnd, int state) {
            return new ChunkResult(lineEnd, state, false);
        }
    }

    /** One tokenized line: per-character token types/ends relative to the line start, and the
     * state it leaves behind. */
    private static final class LineTokens {
        final byte[] types;
        final int[] ends;
        final int outState;
        final long cost;

        LineTokens(byte[] types, int[] ends, int outState) {
            this.types = types;
            this.ends = ends;
            this.outState = outState;
            this.cost = types.length + 4L * ends.length;
        }
    }

    /** Cache key: the tokenization inputs that fully determine a line's tokens. */
    private static final class Key {
        private final String style;
        private final String context;
        private final int state;
        private final String line;

        Key(String style, String context, int state, String line) {
            this.style = style;
            this.context = context;
            this.state = state;
            this.line = line;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return state == other.state
                    && style.equals(other.style)
                    && context.equals(other.context)
                    && line.equals(other.line);
        }

        @Override
        public int hashCode() {
            int h = style.hashCode();
            h = 31 * h + context.hashCode();
            h = 31 * h + state;
            return 31 * h + line.hashCode();
        }
    }

    /** Bounded least-recently-used cache of tokenized lines. */
    private static final class LineCache {
        private final long budget;
        private long retained;
        private final Map<Key, LineTokens> entries =
                new LinkedHashMap<>(64, 0.75f, true);

        LineCache(long budget) {
            this.budget = budget;
        }

        synchronized LineTokens get(String style, String context, int state, String line) {
            return entries.get(new Key(style, context, state, line));
        }

        synchronized void put(
                String style,
                String context,
                int state,
                String line,
                byte[] types,
                int[] ends,
                int outState) {
            LineTokens value = new LineTokens(types, ends, outState);
            LineTokens old = entries.put(new Key(style, context, state, line), value);
            if (old != null) {
                retained -= old.cost;
            }
            retained += 2L * line.length() + value.cost;
            Iterator<Map.Entry<Key, LineTokens>> it = entries.entrySet().iterator();
            while (retained > budget && it.hasNext()) {
                Map.Entry<Key, LineTokens> eldest = it.next();
                retained -= 2L * eldest.getKey().line.length() + eldest.getValue().cost;
                it.remove();
            }
        }

        /** Drops every cached line, for example when the add-on is unloaded. */
        synchronized void clear() {
            entries.clear();
            retained = 0;
        }
    }

    /** Per-character original token information used to recolour wrap breaks. */
    private static final class TokenInfo {
        /** For each offset, the type of the token covering it, or 0 outside any paintable token. */
        byte[] tokenType;

        /** For each offset, the exclusive end of the token covering it. */
        int[] tokenEnd;
    }

    /** Length of the leading run of spaces/tabs on the line {@code [start, end)}. */
    private static int leadingIndentLength(String string, int start, int end) {
        int i = start;
        while (i < end) {
            char c = string.charAt(i);
            if (c != ' ' && c != '\t') {
                break;
            }
            i++;
        }
        return i - start;
    }

    /**
     * How much of the line's leading whitespace to copy onto each continuation row. The indent is
     * capped at half a row so a mostly-whitespace line keeps a useful amount of content per row
     * instead of repeating an arbitrarily long indent once per character.
     */
    static int continuationIndentLength(
            String string, int start, int end, double maxWidth, CharWidths widths) {
        int indent = Math.min(leadingIndentLength(string, start, end), MAX_CONTINUATION_INDENT);
        int limit = Math.min(end, start + indent);
        double width = 0;
        int i = start;
        while (i < limit) {
            int cp = string.codePointAt(i);
            double cw = widths.widthOf(cp);
            if (width + cw > maxWidth / 2) {
                break;
            }
            width += cw;
            i += Character.charCount(cp);
        }
        return i - start;
    }

    /**
     * Splits {@code string} using fixed chunk lengths, ignoring the view width.
     */
    public static Split split(String string) {
        if (string == null || string.isEmpty()) {
            String s = string == null ? "" : string;
            return new Split(s, s, new int[0]);
        }
        List<Integer> breakOffsets = new ArrayList<>();
        StringBuilder display = new StringBuilder(string.length() + 1024);
        int lineStart = 0;
        for (int i = 0; i < string.length(); i++) {
            // Only '\n' (and '\r\n') separates lines; a lone '\r' is ordinary content.
            if (string.charAt(i) == '\n') {
                copyChunked(string, lineStart, i, display, breakOffsets);
                display.append('\n');
                lineStart = i + 1;
            }
        }
        copyChunked(string, lineStart, string.length(), display, breakOffsets);
        return new Split(display.toString(), string, toIntArray(breakOffsets));
    }

    private static void copyChunked(
            String string, int start, int end, StringBuilder display, List<Integer> breakOffsets) {
        int length = end - start;
        if (length <= CHUNK_CHARS) {
            if (length > 0) {
                display.append(string, start, end);
            }
            return;
        }
        int chunkStart = start;
        char quote = '\0';
        int comment = NONE;
        int i = start;
        while (i < end) {
            int limit = quote == '\0' ? CHUNK_CHARS : MAX_CHUNK_CHARS;
            if (i - chunkStart >= limit) {
                chunkStart = emitBreak(string, chunkStart, i, display, breakOffsets);
            }
            char c = string.charAt(i);
            if (comment == LINE_COMMENT) {
                if (c == '\n' || c == '\r') {
                    comment = NONE;
                }
                i++;
                continue;
            }
            if (comment == BLOCK_COMMENT) {
                if (c == '*' && i + 1 < end && string.charAt(i + 1) == '/') {
                    comment = NONE;
                    i++;
                }
                i++;
                continue;
            }
            if (quote != '\0') {
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    quote = '\0';
                }
                i++;
                continue;
            }
            if (c == '/' && i + 1 < end) {
                char next = string.charAt(i + 1);
                if (next == '/' || next == '*') {
                    comment = next == '/' ? LINE_COMMENT : BLOCK_COMMENT;
                    i += 2;
                    continue;
                }
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            }
            i++;
        }
        if (end > chunkStart) {
            display.append(string, chunkStart, end);
        }
    }

    private static int emitBreak(
            String string, int start, int end, StringBuilder display, List<Integer> breakOffsets) {
        display.append(string, start, end);
        breakOffsets.add(display.length());
        display.append('\n');
        return end;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /**
     * Moves the offsets that are after an edited range; the ones inside it are gone. Used to
     * follow the rows of a document that is edited in the text area.
     */
    static int[] shiftOffsets(int[] offsets, int offset, int removed, int inserted) {
        int delta = inserted - removed;
        int[] shifted = new int[offsets.length];
        int count = 0;
        for (int o : offsets) {
            if (o < offset) {
                shifted[count++] = o;
            } else if (o >= offset + removed) {
                shifted[count++] = o + delta;
            }
        }
        if (count == shifted.length) {
            return shifted;
        }
        int[] trimmed = new int[count];
        System.arraycopy(shifted, 0, trimmed, 0, count);
        return trimmed;
    }

    /** The display text plus everything needed to map offsets back to the original text. */
    public static final class Split {

        private final String displayText;
        private final String originalText;
        private final int[] breakOffsets;
        private final int[] breakIndents;
        private final int[] tokenStarts;
        private final int[] tokenEnds;
        private final int[] tokenTypes;

        Split(String displayText, String originalText, int[] breakOffsets) {
            this(
                    displayText,
                    originalText,
                    breakOffsets,
                    new int[breakOffsets.length],
                    new int[0],
                    new int[0],
                    new int[0]);
        }

        Split(
                String displayText,
                String originalText,
                int[] breakOffsets,
                int[] breakIndents,
                int[] tokenStarts,
                int[] tokenEnds,
                int[] tokenTypes) {
            this.displayText = displayText;
            this.originalText = originalText;
            this.breakOffsets = breakOffsets;
            this.breakIndents = breakIndents;
            this.tokenStarts = tokenStarts;
            this.tokenEnds = tokenEnds;
            this.tokenTypes = tokenTypes;
        }

        public String displayText() {
            return displayText;
        }

        public String originalText() {
            return originalText;
        }

        /**
         * Display-text offsets of the {@code '\n'} characters inserted by the split, in ascending
         * order. The row that follows a break starts at {@code breakOffsets()[i] + 1}.
         */
        public int[] breakOffsets() {
            return breakOffsets;
        }

        /**
         * Number of indentation characters injected right after each inserted break (see {@link
         * #breakOffsets()}), so a wrapped continuation row lines up with the line's indentation.
         */
        public int[] breakIndents() {
            return breakIndents;
        }

        /**
         * Display-offset ranges (parallel to {@link #tokenEnds()}/{@link #tokenTypes()}) of the
         * tokens each display row is made of, taken from the unwrapped original text. {@link
         * PrettifyDocument} replays them so the wrapped rows are coloured exactly like the unwrapped
         * view, without tokenizing the display text a second time. Empty when no token maker was
         * available, in which case the document tokenizes the rows as usual.
         */
        public int[] tokenStarts() {
            return tokenStarts;
        }

        public int[] tokenEnds() {
            return tokenEnds;
        }

        public int[] tokenTypes() {
            return tokenTypes;
        }

        /**
         * Maps a display offset back to the offset in the untouched original text, dropping every
         * character the split inserted - both the line breaks and the indentation copied onto the
         * continuation rows.
         */
        public int toOriginalOffset(int offset) {
            int dropped = 0;
            for (int i = 0; i < breakOffsets.length; i++) {
                int breakOffset = breakOffsets[i];
                if (breakOffset >= offset) {
                    break;
                }
                int block = 1 + indentAt(i);
                int inBlock = Math.min(offset - breakOffset, block);
                dropped += inBlock;
                if (offset < breakOffset + block) {
                    break;
                }
            }
            return offset - dropped;
        }

        private int indentAt(int breakIndex) {
            return breakIndex < breakIndents.length ? breakIndents[breakIndex] : 0;
        }

        @Override
        public String toString() {
            return "Split[display="
                    + displayText.length()
                    + ", original="
                    + originalText.length()
                    + ", breaks="
                    + Arrays.toString(breakOffsets)
                    + "]";
        }
    }
}
