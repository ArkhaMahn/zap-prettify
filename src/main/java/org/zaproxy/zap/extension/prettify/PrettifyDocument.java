package org.zaproxy.zap.extension.prettify;

import java.util.Collections;
import java.util.Set;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

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

    /**
     * How much of the message is read when locating the end of the header block. Headers are
     * normally small, but cookies, authentication tokens and custom metadata can make them much
     * larger than the old 8 KB limit, and a body that looks like a header would then be misread.
     */
    private static final int MAX_HEADER_SCAN = 64 * 1024;

    private final int[] tokenStarts;
    private final int[] tokenEnds;
    private final int[] tokenTypes;
    private final SplitTokenMaker tokenMaker;

    private final boolean http;
    private final boolean markupStyle;
    private final boolean scriptStyle;
    /** Start offsets of the rows that continue the line above them; moved when the text changes. */
    private int[] continuationStarts;
    private final TokenMaker stateTokenMaker;
    private final TokenMaker fallbackTokenMaker;
    private final Segment segment = new Segment();

    private int[] scriptRanges;
    private int[] lineState;
    private int computedUpTo = -1;

    private Token cachedTokenList;
    private int cachedTokenLine = -1;

    /**
     * Set once the text was changed in the text area. The recorded tokens describe the text as it
     * was split, so they no longer match the document and the document stops replaying them.
     */
    private volatile boolean edited;

    /**
     * Whether the recorded tokens may be replayed. A document that is edited in the text area
     * cannot replay them (they describe the text as it was split), and an editable document is
     * built with the replay switched off from the start.
     */
    private final boolean replayEnabled;

    public PrettifyDocument(
            TokenMakerFactory tokenMakerFactory,
            String styleKey,
            DisplayTextSplitter.Split split) {
        this(tokenMakerFactory, styleKey, split, true);
    }

    public PrettifyDocument(
            TokenMakerFactory tokenMakerFactory,
            String styleKey,
            DisplayTextSplitter.Split split,
            boolean replayEnabled) {
        // A document that paints from recorded spans never asks its base token maker for tokens:
        // replayTokenList()/fallbackTokenList() or the HTTP state maker answer every paint. Giving
        // it a no-op base maker keeps the document from tokenizing the whole text a second time
        // while it is filled, which is the dominant cost for a large message.
        super(baseFactory(tokenMakerFactory, styleKey, split, replayEnabled), styleKey);
        this.replayEnabled = replayEnabled;
        this.tokenStarts = split == null ? new int[0] : split.tokenStarts();
        this.tokenEnds = split == null ? new int[0] : split.tokenEnds();
        this.tokenTypes = split == null ? new int[0] : split.tokenTypes();
        this.tokenMaker = new SplitTokenMaker(tokenStarts, tokenEnds, tokenTypes);

        this.http = PrettifyTokenMakerFactory.isHttpStyle(styleKey);
        this.markupStyle = !http && ContentType.isHtmlStyle(styleKey);
        this.scriptStyle = !http && ContentType.isScriptStyle(styleKey);
        this.stateTokenMaker = http ? tokenMakerFactory.getTokenMaker(styleKey) : null;
        // Lines the splitter left spanless are tokenized lazily with this maker, starting at state
        // zero (the splitter only skips lines that cannot carry state).
        this.fallbackTokenMaker = tokenMakerFactory.getTokenMaker(styleKey);
        this.continuationStarts = continuationStarts(split);
        this.lineState = new int[Math.max(16, lineCount())];
    }

    /**
     * The token maker factory the base {@link RSyntaxDocument} should use. A replaying document
     * whose rows all have recorded spans (or an HTTP document, which paints through its own state
     * maker) never needs the base token maker, so it is given a no-op one: filling the document then
     * does not tokenize the text a second time. Other documents keep the real factory so RSTA's
     * ordinary tokenization still highlights them.
     */
    private static TokenMakerFactory baseFactory(
            TokenMakerFactory tokenMakerFactory,
            String styleKey,
            DisplayTextSplitter.Split split,
            boolean replayEnabled) {
        boolean hasSpans = split != null && split.tokenStarts().length != 0;
        if (replayEnabled
                && (hasSpans || PrettifyTokenMakerFactory.isHttpStyle(styleKey))) {
            return NoOpTokenMakerFactory.INSTANCE;
        }
        return tokenMakerFactory;
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

    /**
     * Watches the document for changes made in the text area. Called once the text has been
     * inserted, so that filling the document is not seen as an edit.
     */
    public void watchForEdits() {
        addDocumentListener(
                new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        markEdited(e);
                        markInserted(e.getOffset(), e.getLength());
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        markEdited(e);
                        markRemoved(e.getOffset(), e.getLength());
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        // Style change only, the text is the same.
                    }
                });
    }

    /**
     * Notes that the text was changed: the recorded tokens no longer describe the document, the
     * cached line tokens no longer match the text, and the line states carried from the top are
     * stale from the edited line on, so they are recomputed from the text as it is now.
     */
    private void markEdited(DocumentEvent e) {
        edited = true;
        cachedTokenLine = -1;
        cachedTokenList = null;
        int offset = e.getOffset();
        if (offset < 0) {
            return;
        }
        if (http && isHeaderOffset(offset)) {
            // Editing the header block can change the body delegate (Content-Type, for example),
            // so the token maker's own header/body state has to be rebuilt from the top rather
            // than resumed from the edited line.
            if (stateTokenMaker instanceof HttpTokenMaker) {
                ((HttpTokenMaker) stateTokenMaker).resetForEdit();
            }
            computedUpTo = -1;
            return;
        }
        int line = getDefaultRootElement().getElementIndex(offset);
        computedUpTo = Math.max(-1, line - 1);
    }

    /**
     * Whether {@code offset} is inside the header block. The separator is searched within the same
     * bounded scan used elsewhere; when no separator is found in that window an edit near the top
     * is treated as a header edit while one deep in the message is treated as a body edit, so a
     * message with an enormous header block does not re-tokenize from its first line on every
     * keystroke.
     */
    private boolean isHeaderOffset(int offset) {
        int bodyStart = bodyStart();
        if (bodyStart >= 0) {
            return offset < bodyStart;
        }
        return offset <= MAX_HEADER_SCAN;
    }

    /** Notes an edit of the text: the rows that continue a line move with it. */
    private void markInserted(int offset, int inserted) {
        continuationStarts =
                DisplayTextSplitter.shiftOffsets(continuationStarts, offset, 0, inserted);
    }

    /** Notes the removal of text: the rows that continue a line move with it. */
    private void markRemoved(int offset, int removed) {
        continuationStarts =
                DisplayTextSplitter.shiftOffsets(continuationStarts, offset, removed, 0);
    }

    @Override
    public Token getTokenListForLine(int line) {
        Token list = tokenListForLine(line);
        if (list != null && scriptRanges != null && scriptRanges.length != 0) {
            ScriptTokenHighlighter.recolor(list, scriptRanges, isContinuationLine(line));
        }
        return list;
    }

    /** Whether the display line starts at a wrap break rather than at a real message line. */
    private boolean isContinuationLine(int line) {
        if (continuationStarts.length == 0) {
            return false;
        }
        Element root = getDefaultRootElement();
        if (line < 0 || line >= root.getElementCount()) {
            return false;
        }
        int start = root.getElement(line).getStartOffset();
        return java.util.Arrays.binarySearch(continuationStarts, start) >= 0;
    }

    private Token tokenListForLine(int line) {
        if (replayEnabled && !edited && tokenStarts.length != 0 && lineHasSpans(line)) {
            return replayTokenList(line);
        }
        if (!http) {
            if (replayEnabled && !edited && tokenStarts.length != 0) {
                return fallbackTokenList(line);
            }
            return super.getTokenListForLine(line);
        }
        if (line == cachedTokenLine && cachedTokenList != null) {
            return cachedTokenList;
        }
        Element root = getDefaultRootElement();
        if (line < 0 || line >= root.getElementCount()) {
            // The line is gone (the text was edited); there is nothing to paint.
            return null;
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
        if (replayEnabled && !edited && tokenStarts.length != 0 && lineHasSpans(line)) {
            return replayLastTokenType(line);
        }
        if (!http) {
            if (replayEnabled && !edited && tokenStarts.length != 0) {
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
     * Records the character ranges whose object literal keys should be recoloured: the
     * {@code <script>} bodies of a markup document, or the whole of a script document. For
     * the All-message views the body type comes from the header block. Called once, off the Event
     * Dispatch Thread, after the text has been inserted.
     */
    public void prepareScriptLines() {
        int length = getLength();
        if (length == 0) {
            return;
        }
        if (!http) {
            if (markupStyle) {
                scriptRanges = scriptTagRanges();
            } else if (scriptStyle) {
                scriptRanges = new int[] {0, length};
            }
            return;
        }
        String type = bodyContentType();
        if (type == null) {
            return;
        }
        if (type.contains("html")) {
            scriptRanges = scriptTagRanges();
        } else if (type.contains("javascript")
                || type.contains("ecmascript")
                || type.contains("typescript")) {
            int bodyStart = bodyStart();
            if (bodyStart >= 0 && bodyStart < length) {
                scriptRanges = new int[] {bodyStart, length};
            }
        }
    }

    /** Pairs of start/end offsets of the {@code <script>} bodies, ignoring commented-out tags. */
    private int[] scriptTagRanges() {
        String text;
        try {
            text = getText(0, getLength());
        } catch (BadLocationException e) {
            return new int[0];
        }
        java.util.List<Integer> ranges = new java.util.ArrayList<>();
        int from = 0;
        // The comment markers are followed forward once, together with the script tags. Searching
        // backwards from every tag instead made this quadratic for a minified document with many
        // script tags on one line, which is exactly the case a large payload tends to be.
        boolean inComment = false;
        int nextCommentOpen = indexOfIgnoreCase(text, "<!--", 0);
        int nextCommentClose = -1;
        while (from < text.length()) {
            int open = indexOfIgnoreCase(text, "<script", from);
            if (open < 0) {
                break;
            }
            while (true) {
                if (!inComment) {
                    if (nextCommentOpen < 0 || nextCommentOpen >= open) {
                        break;
                    }
                    inComment = true;
                    nextCommentClose = indexOfIgnoreCase(text, "-->", nextCommentOpen + 4);
                    if (nextCommentClose < 0) {
                        nextCommentClose = Integer.MAX_VALUE;
                    }
                    nextCommentOpen = -1;
                } else {
                    if (nextCommentClose >= open) {
                        break;
                    }
                    inComment = false;
                    nextCommentOpen = indexOfIgnoreCase(text, "<!--", nextCommentClose + 3);
                }
            }
            if (inComment) {
                from = open + 7;
                continue;
            }
            int close = indexOfIgnoreCase(text, "</script", open);
            int end = close < 0 ? text.length() : Math.min(text.length(), close + 8);
            ranges.add(open);
            ranges.add(end);
            from = end;
        }
        int[] result = new int[ranges.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ranges.get(i);
        }
        return result;
    }

    /** Case-insensitive {@code indexOf}, so the text does not have to be lowercased first. */
    private static int indexOfIgnoreCase(String text, String needle, int from) {
        int limit = text.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The lowercased {@code Content-Type} header value of an HTTP message, or null when the header
     * block has no such header. Package-private so the header-line parsing can be asserted
     * directly.
     */
    String bodyContentType() {
        String headers = headerBlock();
        if (headers == null) {
            return null;
        }
        int at = 0;
        while (at < headers.length()) {
            int lineEnd = headers.indexOf('\n', at);
            if (lineEnd < 0) {
                lineEnd = headers.length();
            }
            int lineStop = lineEnd;
            if (lineStop > at && headers.charAt(lineStop - 1) == '\r') {
                lineStop--;
            }
            int nameStart = at;
            while (nameStart < lineStop
                    && (headers.charAt(nameStart) == ' ' || headers.charAt(nameStart) == '\t')) {
                nameStart++;
            }
            int colon = headers.indexOf(':', nameStart);
            if (colon >= 0 && colon < lineStop
                    && "content-type".equals(headers.substring(nameStart, colon).trim())) {
                return headers.substring(colon + 1, lineStop).trim();
            }
            at = lineEnd + 1;
        }
        return null;
    }

    /** Offset of the first body byte (just past the blank line ending the headers), or -1. */
    private int bodyStart() {
        String head;
        try {
            head = getText(0, Math.min(getLength(), MAX_HEADER_SCAN));
        } catch (BadLocationException e) {
            return -1;
        }
        int separator = head.indexOf("\r\n\r\n");
        if (separator >= 0) {
            return separator + 4;
        }
        separator = head.indexOf("\n\n");
        return separator < 0 ? -1 : separator + 2;
    }

    /** The lowercased header block of an HTTP message, or null when it has no blank-line ending. */
    private String headerBlock() {
        try {
            String head =
                    getText(0, Math.min(getLength(), MAX_HEADER_SCAN))
                            .toLowerCase(java.util.Locale.ROOT);
            int separator = head.indexOf("\r\n\r\n");
            if (separator < 0) {
                separator = head.indexOf("\n\n");
            }
            return separator < 0 ? null : head.substring(0, separator);
        } catch (BadLocationException | RuntimeException e) {
            return null;
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
            // The line is gone (the text was edited); there is nothing to paint.
            return null;
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
     * Factory for {@link NoOpTokenMaker}. Used as the base token maker of a document that paints
     * from recorded spans, so filling it never tokenizes the text.
     */
    private static final class NoOpTokenMakerFactory extends TokenMakerFactory {

        static final NoOpTokenMakerFactory INSTANCE = new NoOpTokenMakerFactory();

        private final TokenMaker tokenMaker = new NoOpTokenMaker();

        @Override
        protected TokenMaker getTokenMakerImpl(String key) {
            return tokenMaker;
        }

        @Override
        public Set<String> keySet() {
            return Collections.emptySet();
        }
    }

    /**
     * A token maker that treats every line as one plain token. It is only used as the base token
     * maker of a document that never asks it for tokens; it exists so that
     * {@link RSyntaxDocument}'s insertion bookkeeping is O(1) per line instead of a tokenization.
     */
    private static final class NoOpTokenMaker extends TokenMakerBase {

        @Override
        public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
            resetTokenList();
            if (text.count > 0) {
                addToken(
                        text,
                        text.offset,
                        text.offset + text.count - 1,
                        TokenTypes.IDENTIFIER,
                        startOffset);
            }
            addNullToken();
            return firstToken;
        }

        @Override
        public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
            return 0;
        }
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
