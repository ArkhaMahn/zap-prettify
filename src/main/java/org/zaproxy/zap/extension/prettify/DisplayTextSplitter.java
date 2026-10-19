package org.zaproxy.zap.extension.prettify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        if (maxWidth <= 0 || widths == null) {
            return split(string);
        }
        if (string == null || string.isEmpty()) {
            String s = string == null ? "" : string;
            return new Split(s, s, new int[0], numberingSource);
        }
        List<Integer> breakOffsets = new ArrayList<>();
        int n = string.length();
        StringBuilder display = new StringBuilder(n + n / 16 + 16);
        int lineStart = 0;
        for (int i = 0; i < n; i++) {
            // Only '\n' (and '\r\n') separates lines in the text document; a lone '\r' is ordinary
            // content. Breaking on it here would let the document merge the rows back together.
            if (string.charAt(i) == '\n') {
                appendLineByWidth(string, lineStart, i, maxWidth, widths, display, breakOffsets);
                display.append('\n');
                lineStart = i + 1;
            }
        }
        appendLineByWidth(string, lineStart, n, maxWidth, widths, display, breakOffsets);
        return new Split(display.toString(), string, toIntArray(breakOffsets), numberingSource);
    }

    private static void appendLineByWidth(
            String string,
            int start,
            int end,
            int maxWidth,
            CharWidths widths,
            StringBuilder display,
            List<Integer> breakOffsets) {
        int segmentStart = start;
        double width = 0;
        int i = start;
        while (i < end) {
            int cp = string.codePointAt(i);
            double cw = widths.widthOf(cp);
            // Always keep at least one code point per row, even if it alone is wider than maxWidth.
            if (i > segmentStart && width + cw > maxWidth) {
                display.append(string, segmentStart, i);
                breakOffsets.add(display.length());
                display.append('\n');
                segmentStart = i;
                width = 0;
            }
            width += cw;
            i += Character.charCount(cp);
        }
        if (segmentStart < end) {
            display.append(string, segmentStart, end);
        }
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
        private final int[] displayLineNumbers;
        private final int originalLineCount;

        Split(String displayText, String originalText, int[] breakOffsets) {
            this(displayText, originalText, breakOffsets, originalText);
        }

        Split(String displayText, String originalText, int[] breakOffsets, String numberingSource) {
            this.displayText = displayText;
            this.originalText = originalText;
            this.breakOffsets = breakOffsets;
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

        /** Maps a display offset back to the offset in the untouched original text. */
        public int toOriginalOffset(int offset) {
            int dropped = 0;
            for (int breakOffset : breakOffsets) {
                if (breakOffset >= offset) {
                    break;
                }
                dropped++;
            }
            return offset - dropped;
        }

        /** Maps an original offset forward to the offset in the display text. */
        public int toDisplayOffset(int offset) {
            int display = Math.max(0, Math.min(offset, originalText.length()));
            int previous = 0;
            for (int breakOffset : breakOffsets) {
                if (breakOffset - previous > display) {
                    break;
                }
                display++;
                previous++;
            }
            return Math.min(display, displayText.length());
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
