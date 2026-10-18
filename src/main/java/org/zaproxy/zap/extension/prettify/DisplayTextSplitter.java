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
            char c = string.charAt(i);
            if (c == '\n' || c == '\r') {
                appendLineByColumns(string, lineStart, i, columns, display, breakOffsets);
                display.append(c);
                if (c == '\r' && i + 1 < n && string.charAt(i + 1) == '\n') {
                    i++;
                    display.append('\n');
                }
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
            char c = string.charAt(i);
            if (c == '\n' || c == '\r') {
                copyChunked(string, lineStart, i, display, breakOffsets);
                display.append(c);
                lineStart = i + 1;
                if (c == '\r'
                        && lineStart < string.length()
                        && string.charAt(lineStart) == '\n') {
                    display.append('\n');
                    lineStart++;
                }
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

    /** The display text plus everything needed to map offsets back to the original text. */
    public static final class Split {

        private final String displayText;
        private final String originalText;
        private final int[] breakOffsets;
        private final int[] displayLineNumbers;
        private final int originalLineCount;

        Split(String displayText, String originalText, int[] breakOffsets) {
            this.displayText = displayText;
            this.originalText = originalText;
            this.breakOffsets = breakOffsets;
            this.displayLineNumbers = buildDisplayLineNumbers(displayText, breakOffsets);
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
