package org.zaproxy.zap.extension.prettify;

import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * An {@link RSyntaxDocument} that does not tokenise its text itself: it replays the token spans
 * {@link DisplayTextSplitter} already computed from the unwrapped message while finding its wrap
 * breaks.
 *
 * <p>The splitter has to tokenize the text anyway to break on token boundaries and to keep cut
 * tokens coloured correctly. Letting the document tokenize the (broken) display text a second time
 * would both waste work and get a cut regular expression or number wrong, so instead the document
 * turns the splitter's per-row spans straight into {@link Token}s. The result is one tokenization
 * for the whole pipeline, and the wrapped rows are coloured exactly like the unwrapped view.
 *
 * <p>Only the styles the splitter could tokenize carry spans; for everything else (no style, no
 * token maker, or a message that did not wrap) the spans are empty and the ordinary RSTA
 * tokenization runs as before. HTTP messages additionally need the state-aware handling below when
 * their spans are empty, because RSTA's own state carry does not understand the HTTP token maker's
 * end-of-line state encoding.
 */
public class PrettifyDocument extends RSyntaxDocument {

    private final int[] tokenStarts;
    private final int[] tokenEnds;
    private final int[] tokenTypes;
    private final SplitTokenMaker tokenMaker;

    private final boolean http;
    private final boolean scanScripts;
    private final int[] continuationStarts;
    private final TokenMaker stateTokenMaker;
    private final TokenMaker fallbackTokenMaker;
    private final Segment segment = new Segment();

    private int[] scriptRanges;
    private int[] lineState;
    private int computedUpTo = -1;

    private Token cachedTokenList;
    private int cachedTokenLine = -1;

    public PrettifyDocument(
            TokenMakerFactory tokenMakerFactory,
            String styleKey,
            DisplayTextSplitter.Split split) {
        super(tokenMakerFactory, styleKey);
        this.tokenStarts = split == null ? new int[0] : split.tokenStarts();
        this.tokenEnds = split == null ? new int[0] : split.tokenEnds();
        this.tokenTypes = split == null ? new int[0] : split.tokenTypes();
        this.tokenMaker = new SplitTokenMaker(tokenStarts, tokenEnds, tokenTypes);

        this.http = PrettifyTokenMakerFactory.isHttpStyle(styleKey);
        // The All-message views wrap the body in an HTTP token maker, so HTML there is not a
        // distinct style key; script bodies only appear in messages whose body carries markup, and
        // prepareScriptLines() finds nothing to recolor otherwise.
        this.scanScripts = http || ContentType.isHtmlStyle(styleKey);
        this.stateTokenMaker = http ? tokenMakerFactory.getTokenMaker(styleKey) : null;
        // Lines the splitter left spanless are tokenized lazily with this maker, starting at state
        // zero (the splitter only skips lines that cannot carry state).
        this.fallbackTokenMaker = tokenMakerFactory.getTokenMaker(styleKey);
        this.continuationStarts = continuationStarts(split);
        this.lineState = new int[Math.max(16, lineCount())];
    }

    private static int[] continuationStarts(DisplayTextSplitter.Split split) {
        if (split == null) {
            return new int[0];
        }
        int[] breaks = split.breakOffsets();
        int[] starts = new int[breaks.length];
        for (int i = 0; i < breaks.length; i++) {
            starts[i] = breaks[i] + 1;
        }
        return starts;
    }

    @Override
    public Token getTokenListForLine(int line) {
        Token list = tokenListForLine(line);
        if (list != null && scanScripts && scriptRanges != null && scriptRanges.length != 0) {
            ScriptTokenHighlighter.recolor(list, scriptRanges);
        }
        return list;
    }

    private Token tokenListForLine(int line) {
        if (tokenStarts.length != 0 && lineHasSpans(line)) {
            return replayTokenList(line);
        }
        if (!http) {
            if (tokenStarts.length != 0) {
                return fallbackTokenList(line);
            }
            return super.getTokenListForLine(line);
        }
        if (line == cachedTokenLine && cachedTokenList != null) {
            return cachedTokenList;
        }
        Element root = getDefaultRootElement();
        if (line < 0 || line >= root.getElementCount()) {
            return super.getTokenListForLine(line);
        }
        Element lineElement = root.getElement(line);
        int start = lineElement.getStartOffset();
        int length = lineElement.getEndOffset() - 1 - start;
        if (length < 0) {
            length = 0;
        }
        int initialTokenType = line == 0 ? 0 : getLastTokenTypeOnLine(line - 1);
        try {
            getText(start, length, segment);
        } catch (BadLocationException e) {
            return super.getTokenListForLine(line);
        }
        // Painting goes through our own state-aware token maker so that the header state it built
        // during precomputeLineStates() (including the body Content-Type) is the one used here.
        cachedTokenLine = line;
        cachedTokenList = stateTokenMaker.getTokenList(segment, initialTokenType, start);
        return cachedTokenList;
    }

    @Override
    public int getLastTokenTypeOnLine(int line) {
        if (tokenStarts.length != 0 && lineHasSpans(line)) {
            return replayLastTokenType(line);
        }
        if (!http) {
            if (tokenStarts.length != 0) {
                return fallbackLastType(line);
            }
            return super.getLastTokenTypeOnLine(line);
        }
        if (line < 0) {
            return 0;
        }
        advanceTo(line);
        return line < lineState.length ? lineState[line] : 0;
    }

    /**
     * Computes the carried state for every line. Called once, off the Event Dispatch Thread, right
     * after the text has been inserted, so that painting never has to tokenize from the top of a
     * large document. Only HTTP messages whose spans were not recorded (they did not wrap) need it.
     */
    public void precomputeLineStates() {
        if (tokenStarts.length == 0 && http) {
            advanceTo(lineCount() - 1);
        }
    }

    /**
     * Records the character ranges of the {@code <script>} bodies, so their object literal keys can
     * be recoloured when a line containing them is tokenized. Called once, off the Event Dispatch
     * Thread, after the text has been inserted.
     */
    public void prepareScriptLines() {
        if (!scanScripts || (http && !looksLikeHtmlBody())) {
            return;
        }
        String text;
        try {
            text = getText(0, getLength());
        } catch (BadLocationException e) {
            return;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        java.util.List<Integer> ranges = new java.util.ArrayList<>();
        int from = 0;
        while (from < lower.length()) {
            int open = lower.indexOf("<script", from);
            if (open < 0) {
                break;
            }
            int commentOpen = lower.lastIndexOf("<!--", open);
            int commentClose = lower.lastIndexOf("-->", open);
            if (commentOpen > commentClose) {
                from = open + 7;
                continue;
            }
            int close = lower.indexOf("</script", open);
            int end = close < 0 ? text.length() : Math.min(text.length(), close + 8);
            ranges.add(open);
            ranges.add(end);
            from = end;
        }
        int[] result = new int[ranges.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ranges.get(i);
        }
        scriptRanges = result;
    }


    /**
     * Whether the HTTP message's header block declares an HTML body. Keeps the script scan (which
     * reads the whole document) away from large binary payloads.
     */
    private boolean looksLikeHtmlBody() {
        try {
            String head = getText(0, Math.min(getLength(), 8192)).toLowerCase(java.util.Locale.ROOT);
            int separator = head.indexOf("\r\n\r\n");
            if (separator < 0) {
                separator = head.indexOf("\n\n");
            }
            if (separator < 0) {
                return false;
            }
            String headers = head.substring(0, separator);
            int at = headers.indexOf("content-type:");
            if (at < 0) {
                return false;
            }
            String value = headers.substring(at + "content-type:".length());
            int eol = value.indexOf('\n');
            if (eol >= 0) {
                value = value.substring(0, eol);
            }
            return value.contains("html");
        } catch (BadLocationException | RuntimeException e) {
            return false;
        }
    }

    // -- Replayed spans -----------------------------------------------------------------------

    /** Whether the display line has recorded spans, and so can be replayed instead of tokenized. */
    private boolean lineHasSpans(int line) {
        Element root = getDefaultRootElement();
        if (line < 0 || line >= root.getElementCount()) {
            return false;
        }
        Element lineElement = root.getElement(line);
        int start = lineElement.getStartOffset();
        int end = Math.max(start, lineElement.getEndOffset() - 1);
        if (end <= start) {
            return false;
        }
        int i = firstSpanAtOrAfter(start);
        return i < tokenStarts.length && tokenStarts[i] < end;
    }

    /** First span whose start is at or after {@code offset}. */
    private int firstSpanAtOrAfter(int offset) {
        int lo = 0;
        int hi = tokenStarts.length - 1;
        int answer = tokenStarts.length;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (tokenStarts[mid] >= offset) {
                answer = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return answer;
    }

    private Token replayTokenList(int line) {
        if (line == cachedTokenLine && cachedTokenList != null) {
            return cachedTokenList;
        }
        Element root = getDefaultRootElement();
        if (line < 0 || line >= root.getElementCount()) {
            return super.getTokenListForLine(line);
        }
        Element lineElement = root.getElement(line);
        int start = lineElement.getStartOffset();
        int length = lineElement.getEndOffset() - 1 - start;
        if (length < 0) {
            length = 0;
        }
        try {
            getText(start, length, segment);
        } catch (BadLocationException e) {
            return super.getTokenListForLine(line);
        }
        cachedTokenLine = line;
        cachedTokenList = tokenMaker.getTokenList(segment, 0, start);
        return cachedTokenList;
    }

    private int replayLastTokenType(int line) {
        if (line < 0) {
            return 0;
        }
        Element root = getDefaultRootElement();
        if (line >= root.getElementCount()) {
            return 0;
        }
        Element lineElement = root.getElement(line);
        int end = lineElement.getEndOffset() - 1;
        if (end <= lineElement.getStartOffset()) {
            return 0;
        }
        int index = spanContaining(end - 1);
        return index >= 0 ? tokenTypes[index] : 0;
    }

    /** Index of the span whose half-open range {@code [start, end)} contains {@code offset}. */
    private int spanContaining(int offset) {
        int lo = 0;
        int hi = tokenStarts.length - 1;
        int candidate = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (tokenStarts[mid] <= offset) {
                candidate = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (candidate >= 0 && offset < tokenEnds[candidate]) {
            return candidate;
        }
        return -1;
    }

    /**
     * Tokenizes a line the splitter left spanless, starting from state zero. Such a line cannot
     * carry state in or out, so it is independent of the replayed rows around it.
     */
    private Token fallbackTokenList(int line) {
        Element root = getDefaultRootElement();
        if (line < 0 || line >= root.getElementCount() || fallbackTokenMaker == null) {
            return super.getTokenListForLine(line);
        }
        Element lineElement = root.getElement(line);
        int start = lineElement.getStartOffset();
        int length = lineElement.getEndOffset() - 1 - start;
        if (length < 0) {
            length = 0;
        }
        try {
            getText(start, length, segment);
        } catch (BadLocationException e) {
            return super.getTokenListForLine(line);
        }
        return fallbackTokenMaker.getTokenList(segment, 0, start);
    }

    private int fallbackLastType(int line) {
        Element root = getDefaultRootElement();
        if (line < 0 || line >= root.getElementCount() || fallbackTokenMaker == null) {
            return 0;
        }
        Element lineElement = root.getElement(line);
        int start = lineElement.getStartOffset();
        int length = lineElement.getEndOffset() - 1 - start;
        if (length < 0) {
            return 0;
        }
        try {
            getText(start, length, segment);
        } catch (BadLocationException e) {
            return 0;
        }
        return fallbackTokenMaker.getLastTokenTypeOnLine(segment, 0);
    }

    // -- HTTP carried state (no replayed spans) -----------------------------------------------

    private int lineCount() {
        return getDefaultRootElement().getElementCount();
    }

    private void advanceTo(int line) {
        int count = lineCount();
        if (line >= count) {
            line = count - 1;
        }
        if (lineState.length < count) {
            int[] grown = new int[count];
            System.arraycopy(lineState, 0, grown, 0, lineState.length);
            lineState = grown;
        }
        Element root = getDefaultRootElement();
        for (int i = computedUpTo + 1; i <= line; i++) {
            int init = (i == 0) ? 0 : lineState[i - 1];
            int construct = constructStateAtEnd(root.getElement(i), init);
            lineState[i] =
                    isContinuation(i + 1)
                            ? construct
                            : HttpTokenMaker.resetAfterLine(construct);
        }
        if (line > computedUpTo) {
            computedUpTo = line;
        }
    }

    private int constructStateAtEnd(Element lineElement, int initialTokenType) {
        int start = lineElement.getStartOffset();
        int length = lineElement.getEndOffset() - 1 - start;
        if (length < 0) {
            length = 0;
        }
        try {
            getText(start, length, segment);
        } catch (BadLocationException e) {
            return initialTokenType;
        }
        return stateTokenMaker.getLastTokenTypeOnLine(segment, initialTokenType);
    }

    private boolean isContinuation(int line) {
        if (line <= 0 || line >= lineCount()) {
            return false;
        }
        int start = getDefaultRootElement().getElement(line).getStartOffset();
        return contains(continuationStarts, start);
    }

    private static boolean contains(int[] sorted, int value) {
        int lo = 0;
        int hi = sorted.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = sorted[mid];
            if (v == value) {
                return true;
            }
            if (v < value) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return false;
    }

    /**
     * Replays the splitter's per-row token spans as an RSTA token list, so no tokenization of the
     * display text happens here.
     */
    private static final class SplitTokenMaker extends TokenMakerBase {

        private final int[] starts;
        private final int[] ends;
        private final int[] types;

        SplitTokenMaker(int[] starts, int[] ends, int[] types) {
            this.starts = starts;
            this.ends = ends;
            this.types = types;
        }

        @Override
        public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
            resetTokenList();
            int lineStart = startOffset;
            int lineEnd = lineStart + text.count;
            int i = firstSpanAtOrAfter(lineStart);
            for (; i < starts.length && starts[i] < lineEnd; i++) {
                int spanStart = starts[i];
                int spanEnd = Math.min(ends[i], lineEnd);
                if (spanEnd > spanStart) {
                    // addToken uses inclusive array indices and takes the document offset separately.
                    addToken(text, spanStart, spanEnd - 1, types[i], spanStart);
                }
            }
            addNullToken();
            return firstToken;
        }

        @Override
        public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
            int lineStart = text.offset;
            int lineEnd = lineStart + text.count;
            int index = -1;
            for (int i = firstSpanAtOrAfter(lineStart); i < starts.length && starts[i] < lineEnd; i++) {
                index = i;
            }
            return index >= 0 ? types[index] : 0;
        }

        /** First span whose start is at or after {@code offset}. */
        private int firstSpanAtOrAfter(int offset) {
            int lo = 0;
            int hi = starts.length - 1;
            int answer = starts.length;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] >= offset) {
                    answer = mid;
                    hi = mid - 1;
                } else {
                    lo = mid + 1;
                }
            }
            return answer;
        }
    }
}
