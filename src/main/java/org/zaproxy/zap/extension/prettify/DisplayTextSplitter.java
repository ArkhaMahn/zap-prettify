package org.zaproxy.zap.extension.prettify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.text.Segment;
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

    /** Approximate limit, in retained characters, of the per-line token cache. */
    private static final long LINE_CACHE_BUDGET = 16L * 1024 * 1024;

    /**
     * Tokenized lines, shared by every split. The same message is split once per view (headers,
     * body, whole message), so the expensive bodies are tokenized once and reused instead of once
     * per view.
     */
    private static final LineCache LINE_CACHE = new LineCache(LINE_CACHE_BUDGET);

    private DisplayTextSplitter() {}

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
     * derives the gutter line numbers from {@code numberingSource} instead of from {@code string}.
     *
     * <p>This is used when {@code string} is a reformatted (pretty-printed) view of {@code
     * numberingSource}: the pretty-printer only inserts and reflows whitespace, so the two texts
     * have the same non-whitespace characters in the same order. Walking them together lets the
     * gutter keep the <em>original</em> message line numbers - one number per original line, on its
     * first display row - instead of numbering every line the formatter produced.
     *
     * @param numberingSource the original text whose line numbers the gutter should show; if null
     *     or equal to {@code string} the normal (split) numbering is used
     */
    public static Split split(
            String string, String numberingSource, int maxWidth, CharWidths widths) {
        return split(string, numberingSource, null, maxWidth, widths);
    }

    /**
     * Splits {@code string} into display lines like {@link #split(String, String, int, CharWidths)},
     * but aware of {@code style}'s tokens so the wrapped view is coloured like the unwrapped one.
     * Breaks sit at the exact width (filling every row), and when a break falls inside a token the
     * rows it spans are recorded with that token's original type, so {@link PrettifyDocument} paints
     * the fragments correctly instead of showing whatever a per-line tokenizer would make of them.
     * When {@code style} is null (or has no token maker) the usual tokenizer-based colouring applies.
     */
    public static Split split(
            String string, String numberingSource, String style, int maxWidth, CharWidths widths) {
        if (maxWidth <= 0 || widths == null) {
            return split(string);
        }
        if (string == null || string.isEmpty()) {
            String s = string == null ? "" : string;
            return new Split(s, s, new int[0], numberingSource);
        }
        // Tokenizing is only needed when something actually wraps; typical messages never reach the
        // width limit, so skip it entirely then.
        TokenInfo info =
                needsWrap(string, maxWidth, widths)
                        ? tokenInfo(string, style, maxWidth, widths)
                        : null;
        List<Integer> breakOffsets = new ArrayList<>();
        List<Integer> breakIndents = new ArrayList<>();
        List<Integer> tokenStarts = new ArrayList<>();
        List<Integer> tokenEnds = new ArrayList<>();
        List<Integer> tokenTypes = new ArrayList<>();
        int n = string.length();
        StringBuilder display = new StringBuilder(n + n / 16 + 16);
        int lineStart = 0;
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
                        info,
                        display,
                        breakOffsets,
                        breakIndents,
                        tokenStarts,
                        tokenEnds,
                        tokenTypes);
                display.append('\n');
                lineStart = i + 1;
            }
        }
        appendLineByWidth(
                string,
                lineStart,
                n,
                maxWidth,
                widths,
                info,
                display,
                breakOffsets,
                breakIndents,
                tokenStarts,
                tokenEnds,
                tokenTypes);
        return new Split(
                display.toString(),
                string,
                toIntArray(breakOffsets),
                toIntArray(breakIndents),
                toIntArray(tokenStarts),
                toIntArray(tokenEnds),
                toIntArray(tokenTypes),
                numberingSource);
    }

    private static void appendLineByWidth(
            String string,
            int start,
            int end,
            int maxWidth,
            CharWidths widths,
            TokenInfo info,
            StringBuilder display,
            List<Integer> breakOffsets,
            List<Integer> breakIndents,
            List<Integer> tokenStarts,
            List<Integer> tokenEnds,
            List<Integer> tokenTypes) {
        // Continuation rows are indented to the wrapped line's own indentation level so a wrapped
        // value does not snap back to column zero but stays under the block it belongs to.
        int indentLength = leadingIndentLength(string, start, end);
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
                // safe because recordRowTokens() records the closed-over token's original type for
                // each fragment, so a split token still paints with its own colour. Snapping the
                // break back to a token boundary instead would leave the row short (a header name
                // stranded before its value, or trailing empty space before the wrap).
                int breakAt = i;
                int contentDisplayStart = display.length();
                display.append(string, segmentStart, breakAt);
                recordRowTokens(
                        info,
                        segmentStart,
                        breakAt,
                        contentDisplayStart,
                        tokenStarts,
                        tokenEnds,
                        tokenTypes);
                breakOffsets.add(display.length());
                display.append('\n');
                if (indentLength > 0) {
                    int indentDisplayStart = display.length();
                    display.append(string, start, start + indentLength);
                    if (info != null) {
                        tokenStarts.add(indentDisplayStart);
                        tokenEnds.add(indentDisplayStart + indentLength);
                        tokenTypes.add(TokenTypes.WHITESPACE);
                    }
                }
                breakIndents.add(indentLength);
                segmentStart = breakAt;
                width = indentWidth;
                i = breakAt;
                continue;
            }
            width += cw;
            i += Character.charCount(cp);
        }
        if (segmentStart < end) {
            int contentDisplayStart = display.length();
            display.append(string, segmentStart, end);
            recordRowTokens(
                    info,
                    segmentStart,
                    end,
                    contentDisplayStart,
                    tokenStarts,
                    tokenEnds,
                    tokenTypes);
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

    /** Whether the line {@code [start, end)} is wider than {@code maxWidth}. */
    private static boolean lineWraps(
            String string, int start, int end, int maxWidth, CharWidths widths) {
        double w = 0;
        int i = start;
        while (i < end) {
            int cp = string.codePointAt(i);
            w += widths.widthOf(cp);
            if (w > maxWidth) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
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

    /** Whether any logical line is wider than {@code maxWidth} and therefore needs wrapping. */
    private static boolean needsWrap(String string, int maxWidth, CharWidths widths) {
        int n = string.length();
        int lineStart = 0;
        for (int i = 0; i <= n; i++) {
            if (i == n || string.charAt(i) == '\n') {
                double w = 0;
                int k = lineStart;
                while (k < i) {
                    int cp = string.codePointAt(k);
                    w += widths.widthOf(cp);
                    if (w > maxWidth) {
                        return true;
                    }
                    k += Character.charCount(cp);
                }
                lineStart = i + 1;
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
            String text, String style, int maxWidth, CharWidths widths) {
        if (style == null) {
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
                                || lineWraps(text, pos, end, maxWidth, widths)
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
                        char[] chars = new char[len];
                        if (len > 0) {
                            text.getChars(pos, end, chars, 0);
                        }
                        Segment segment = new Segment(chars, 0, len);
                        Token nullToken = null;
                        int lastType = inState;
                        for (Token t = tokenMaker.getTokenList(segment, inState, pos);
                                t != null;
                                t = t.getNextToken()) {
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
                            for (int k = offset; k < to; k++) {
                                info.tokenType[k] = (byte) type;
                                info.tokenEnd[k] = to;
                            }
                        }
                        // The HTTP token maker encodes its carried state in the trailing null
                        // token; every other style carries the state the maker reports for the
                        // line (as RSTA does), which lets a multi-line string keep its state
                        // across rows.
                        state =
                                http
                                        ? HttpTokenMaker.resetAfterLine(
                                                nullToken != null ? nullToken.getType() : lastType)
                                        : tokenMaker instanceof ContinuedStringTokenMaker
                                                ? ((ContinuedStringTokenMaker) tokenMaker)
                                                        .lastState()
                                                : tokenMaker.getLastTokenTypeOnLine(
                                                        segment, inState);
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
            }
        } catch (RuntimeException e) {
            return null;
        }
        return info;
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
     * Splits {@code string} into display lines no longer than {@code columns} characters, keeping
     * whole existing lines and CR/LF pairs. If {@code columns} is not positive this falls back to
     * {@link #split(String)}.
     */
    public static Split split(String string, int columns) {
        if (columns <= 0) {
            return split(string);
        }
        if (string == null || string.isEmpty()) {
            String s = string == null ? "" : string;
            return new Split(s, s, new int[0]);
        }
        List<Integer> breakOffsets = new ArrayList<>();
        int n = string.length();
        StringBuilder display = new StringBuilder(n + n / columns + 16);
        int lineStart = 0;
        for (int i = 0; i < n; i++) {
            // Only '\n' (and '\r\n') separates lines; a lone '\r' is ordinary content.
            if (string.charAt(i) == '\n') {
                appendLineByColumns(string, lineStart, i, columns, display, breakOffsets);
                display.append('\n');
                lineStart = i + 1;
            }
        }
        appendLineByColumns(string, lineStart, n, columns, display, breakOffsets);
        return new Split(display.toString(), string, toIntArray(breakOffsets));
    }

    private static void appendLineByColumns(
            String string,
            int start,
            int end,
            int columns,
            StringBuilder display,
            List<Integer> breakOffsets) {
        int s = start;
        while (s < end) {
            int e = Math.min(end, s + columns);
            display.append(string, s, e);
            if (e < end) {
                breakOffsets.add(display.length());
                display.append('\n');
            }
            s = e;
        }
    }

    /** Splits {@code string} using fixed chunk lengths, ignoring the view width. */
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

    /**
     * Builds, for every display line (0-based), the 1-based number of the original message line it
     * belongs to, or {@code 0} if the line is a continuation produced by an inserted break. The
     * gutter uses this so wrapped/inserted rows are not numbered - only real message lines are.
     */
    private static int[] buildDisplayLineNumbers(String displayText, int[] breakOffsets) {
        int n = displayText.length();
        int lines = 1;
        for (int i = 0; i < n; i++) {
            if (displayText.charAt(i) == '\n') {
                lines++;
            }
        }
        int[] numbers = new int[lines];
        numbers[0] = 1;
        int originalLine = 1;
        int breakIndex = 0;
        int lineIndex = 0;
        for (int i = 0; i < n; i++) {
            if (displayText.charAt(i) != '\n') {
                continue;
            }
            boolean injected = breakIndex < breakOffsets.length && breakOffsets[breakIndex] == i;
            if (injected) {
                breakIndex++;
            }
            lineIndex++;
            if (injected) {
                numbers[lineIndex] = 0;
            } else {
                originalLine++;
                numbers[lineIndex] = originalLine;
            }
        }
        return numbers;
    }

    private static int countOriginalLines(int[] displayLineNumbers) {
        int max = 1;
        for (int n : displayLineNumbers) {
            if (n > max) {
                max = n;
            }
        }
        return max;
    }

    /**
     * Picks the per-display-line numbers: when {@code numberingSource} is the same text that was
     * split (the normal case) the numbers follow the display text's own newlines. When it differs
     * (the split text is a pretty-printed view of it) the numbers are taken from the original text
     * by aligning the two on their non-whitespace characters.
     */
    private static int[] computeDisplayLineNumbers(
            String displayText, String splitText, int[] breakOffsets, String numberingSource) {
        if (numberingSource == null || numberingSource.equals(splitText)) {
            return buildDisplayLineNumbers(displayText, breakOffsets);
        }
        int[] formattedLineToOriginal = mapFormattedToOriginal(splitText, numberingSource);
        if (formattedLineToOriginal == null) {
            return buildDisplayLineNumbers(displayText, breakOffsets);
        }
        return lineNumbersFromMap(displayText, breakOffsets, formattedLineToOriginal);
    }

    /**
     * Maps every line of {@code formatted} (0-based) to the 1-based line of {@code source} its first
     * non-whitespace character came from, or {@code 0} for a line with no non-whitespace character.
     *
     * <p>The pretty-printer only ever adds, removes or reflows whitespace, so the two texts share
     * the same non-whitespace characters in the same order; walking them together recovers which
     * source line each formatted line belongs to. Returns {@code null} if they diverge (a formatter
     * that is not whitespace-only), so callers fall back to ordinary numbering.
     */
    private static int[] mapFormattedToOriginal(String formatted, String source) {
        int lines = 1;
        for (int i = 0; i < formatted.length(); i++) {
            if (formatted.charAt(i) == '\n') {
                lines++;
            }
        }
        int[] map = new int[lines];
        int n = source.length();
        int sourceIndex = 0;
        int sourceLine = 1;
        int formattedLine = 0;
        boolean seenOnLine = false;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\n') {
                if (!seenOnLine) {
                    // A line with no content: the blank line that ends the header block is a line
                    // of the message like any other and is numbered, while a blank line the pretty
                    // printer added inside a body is not.
                    int blank = blankSourceLineAt(source, sourceIndex, sourceLine);
                    if (blank > 0) {
                        map[formattedLine] = blank;
                        sourceLine = blank;
                        sourceIndex = blankLineEnd(source, sourceIndex);
                    }
                }
                formattedLine++;
                seenOnLine = false;
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            while (sourceIndex < n && Character.isWhitespace(source.charAt(sourceIndex))) {
                if (source.charAt(sourceIndex) == '\n') {
                    sourceLine++;
                }
                sourceIndex++;
            }
            if (sourceIndex >= n || source.charAt(sourceIndex) != c) {
                return null;
            }
            sourceIndex++;
            if (!seenOnLine) {
                map[formattedLine] = sourceLine;
                seenOnLine = true;
            }
        }
        return map;
    }

    /**
     * The line of the source a blank line of the formatted text stands for, or 0 when there is
     * none: the line after the one the source pointer is on, but only when that line is blank too.
     */
    private static int blankSourceLineAt(String source, int sourceIndex, int sourceLine) {
        int n = source.length();
        int index = sourceIndex;
        while (index < n && source.charAt(index) != '\n') {
            if (!Character.isWhitespace(source.charAt(index))) {
                return 0;
            }
            index++;
        }
        if (index >= n) {
            return 0;
        }
        index++;
        int end = index;
        while (end < n && source.charAt(end) != '\n') {
            if (!Character.isWhitespace(source.charAt(end))) {
                return 0;
            }
            end++;
        }
        // The line holds nothing but whitespace, so it is a blank line of the message.
        return sourceLine + 1;
    }

    /** Offset just past the blank line the source pointer is on. */
    private static int blankLineEnd(String source, int sourceIndex) {
        int n = source.length();
        int index = sourceIndex;
        while (index < n && source.charAt(index) != '\n') {
            index++;
        }
        return index < n ? index + 1 : n;
    }


    /**
     * Builds the per-display-line numbers from a formatted-line-to-original-line map: a display row
     * is numbered only when it starts a formatted line that in turn starts a new original line, so
     * each original message line is numbered exactly once (on its first display row) and every
     * reformat and wrap continuation row is left blank.
     */
    private static int[] lineNumbersFromMap(
            String displayText, int[] breakOffsets, int[] formattedLineToOriginal) {
        int n = displayText.length();
        int lines = 1;
        for (int i = 0; i < n; i++) {
            if (displayText.charAt(i) == '\n') {
                lines++;
            }
        }
        int[] numbers = new int[lines];
        int previousOriginal = 0;
        if (formattedLineToOriginal.length > 0 && formattedLineToOriginal[0] != 0) {
            numbers[0] = formattedLineToOriginal[0];
            previousOriginal = numbers[0];
        }
        int formattedLine = 0;
        int breakIndex = 0;
        int lineIndex = 0;
        for (int i = 0; i < n; i++) {
            if (displayText.charAt(i) != '\n') {
                continue;
            }
            boolean injected = breakIndex < breakOffsets.length && breakOffsets[breakIndex] == i;
            if (injected) {
                breakIndex++;
            } else {
                formattedLine++;
            }
            lineIndex++;
            if (injected) {
                continue;
            }
            int original =
                    formattedLine < formattedLineToOriginal.length
                            ? formattedLineToOriginal[formattedLine]
                            : 0;
            if (original != 0 && original != previousOriginal) {
                numbers[lineIndex] = original;
                previousOriginal = original;
            }
        }
        return numbers;
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
        private final int[] displayLineNumbers;
        private final int originalLineCount;

        Split(String displayText, String originalText, int[] breakOffsets) {
            this(displayText, originalText, breakOffsets, new int[breakOffsets.length], originalText);
        }

        Split(String displayText, String originalText, int[] breakOffsets, String numberingSource) {
            this(displayText, originalText, breakOffsets, new int[breakOffsets.length], numberingSource);
        }

        Split(
                String displayText,
                String originalText,
                int[] breakOffsets,
                int[] breakIndents,
                String numberingSource) {
            this(
                    displayText,
                    originalText,
                    breakOffsets,
                    breakIndents,
                    new int[0],
                    new int[0],
                    new int[0],
                    numberingSource);
        }

        Split(
                String displayText,
                String originalText,
                int[] breakOffsets,
                int[] breakIndents,
                int[] tokenStarts,
                int[] tokenEnds,
                int[] tokenTypes,
                String numberingSource) {
            this.displayText = displayText;
            this.originalText = originalText;
            this.breakOffsets = breakOffsets;
            this.breakIndents = breakIndents;
            this.tokenStarts = tokenStarts;
            this.tokenEnds = tokenEnds;
            this.tokenTypes = tokenTypes;
            this.displayLineNumbers =
                    computeDisplayLineNumbers(displayText, originalText, breakOffsets, numberingSource);
            this.originalLineCount = countOriginalLines(displayLineNumbers);
        }

        public String displayText() {
            return displayText;
        }

        public String originalText() {
            return originalText;
        }

        public int addedBreaks() {
            return breakOffsets.length;
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
         * Turns text edited in the wrapped display back into message text: the line breaks this
         * split inserted (and the indentation it copied onto the continuation rows) are taken out
         * again, so what is left is the message as it was, with the user's changes.
         *
         * <p>The edited text is matched against the display text that was installed: the unchanged
         * head and tail of the two are known to correspond, so the breaks in them can be located
         * and removed, while the part the user actually changed is kept as they typed it.
         */
        public String toMessageText(String edited) {
            if (edited == null) {
                return null;
            }
            if (edited.equals(displayText)) {
                return edited;
            }
            int prefix = 0;
            int limit = Math.min(displayText.length(), edited.length());
            while (prefix < limit && displayText.charAt(prefix) == edited.charAt(prefix)) {
                prefix++;
            }
            int suffix = 0;
            while (suffix < limit - prefix
                    && displayText.charAt(displayText.length() - 1 - suffix)
                            == edited.charAt(edited.length() - 1 - suffix)) {
                suffix++;
            }
            int[] inserted = insertedOffsets();
            StringBuilder out = new StringBuilder(edited.length());
            appendUnwrapped(out, edited, 0, prefix, 0, inserted);
            out.append(edited, prefix, edited.length() - suffix);
            appendUnwrapped(
                    out,
                    edited,
                    edited.length() - suffix,
                    edited.length(),
                    displayText.length() - suffix,
                    inserted);
            return out.toString();
        }

        /** Offsets of the characters the split inserted: the breaks and their indentation. */
        private int[] insertedOffsets() {
            int count = 0;
            for (int i = 0; i < breakOffsets.length; i++) {
                count += 1 + (i < breakIndents.length ? breakIndents[i] : 0);
            }
            int[] inserted = new int[count];
            int at = 0;
            for (int i = 0; i < breakOffsets.length; i++) {
                inserted[at++] = breakOffsets[i];
                int indent = i < breakIndents.length ? breakIndents[i] : 0;
                for (int j = 1; j <= indent; j++) {
                    inserted[at++] = breakOffsets[i] + j;
                }
            }
            Arrays.sort(inserted);
            return inserted;
        }

        /**
         * Appends the edited text, skipping the characters the split inserted. The edited range
         * {@code [from, to)} corresponds to the display range starting at {@code displayFrom}.
         */
        private static void appendUnwrapped(
                StringBuilder out, String edited, int from, int to, int displayFrom, int[] inserted) {
            for (int i = from; i < to; i++) {
                int displayOffset = displayFrom + (i - from);
                if (Arrays.binarySearch(inserted, displayOffset) >= 0) {
                    continue;
                }
                out.append(edited.charAt(i));
            }
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
         * For each 0-based display line, the 1-based original message line it belongs to, or {@code
         * 0} if it is a continuation row created by a break.
         */
        public int[] displayLineNumbers() {
            return displayLineNumbers;
        }

        /** Number of lines the original (unsplit) message has. */
        public int originalLineCount() {
            return originalLineCount;
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

        /** Maps an original offset forward to the offset in the display text. */
        public int toDisplayOffset(int offset) {
            int original = Math.max(0, Math.min(offset, originalText.length()));
            int display = original;
            int injectedBefore = 0;
            for (int i = 0; i < breakOffsets.length; i++) {
                int originalAtBreak = breakOffsets[i] - injectedBefore;
                if (originalAtBreak > original) {
                    break;
                }
                int block = 1 + indentAt(i);
                display += block;
                injectedBefore += block;
            }
            return Math.min(display, displayText.length());
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
