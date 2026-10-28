package org.zaproxy.zap.extension.prettify;

/**
 * Maps the lines of the pretty-printed projection back to the line numbers of the raw message.
 *
 * <p>The pretty printer inserts line breaks but never changes the non-whitespace characters, so the
 * first non-whitespace character of each displayed line can be followed back to the raw line it came
 * from. Only the first display line of each raw line is numbered; the continuation lines the
 * formatter inserted are left blank, which keeps the gutter showing the message's own line numbers
 * instead of the formatter's.
 */
public final class LineNumbers {

    private LineNumbers() {}

    /**
     * For each 0-based display line, the 1-based raw line it starts on, or {@code 0} when the line
     * is a continuation line the formatter inserted.
     */
    public static int[] forDisplay(String raw, String display) {
        String source = raw == null ? "" : raw;
        String text = display == null ? "" : display;
        int lines = TextAlignment.countLines(text);
        int[] map = new int[lines];
        int rawIndex = 0;
        int rawLine = 1;
        int displayLine = 0;
        int lastNumbered = 0;
        boolean seenOnLine = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (!seenOnLine) {
                    int blank = blankRawLineAt(source, rawIndex, rawLine);
                    if (blank > 0 && blank != lastNumbered) {
                        map[displayLine] = blank;
                        lastNumbered = blank;
                        rawLine = blank + 1;
                        rawIndex = blankLineEnd(source, rawIndex);
                    }
                }
                displayLine++;
                seenOnLine = false;
                continue;
            }
            if (TextAlignment.isWhitespace(c)) {
                continue;
            }
            while (rawIndex < source.length()
                    && TextAlignment.isWhitespace(source.charAt(rawIndex))) {
                if (source.charAt(rawIndex) == '\n') {
                    rawLine++;
                }
                rawIndex++;
            }
            if (rawIndex >= source.length() || source.charAt(rawIndex) != c) {
                // The formatter changed more than whitespace; ordinary numbering is the only safe
                // answer.
                return sequential(lines);
            }
            rawIndex++;
            if (!seenOnLine) {
                if (rawLine != lastNumbered) {
                    map[displayLine] = rawLine;
                    lastNumbered = rawLine;
                }
                seenOnLine = true;
            }
        }
        if (!seenOnLine) {
            int blank = blankRawLineAt(source, rawIndex, rawLine);
            if (blank > 0 && blank != lastNumbered) {
                map[displayLine] = blank;
            }
        }
        return map;
    }

    /** Line numbers 1..{@code lines}, used when the display text does not align with the raw one. */
    public static int[] sequential(int lines) {
        int count = Math.max(1, lines);
        int[] numbers = new int[count];
        for (int i = 0; i < count; i++) {
            numbers[i] = i + 1;
        }
        return numbers;
    }

    /**
     * The raw line a blank display line stands for, or 0 when there is none. A blank display line is
     * numbered only when the raw text is blank there too, so the formatter's own blank lines (for
     * example between JSON members) stay unnumbered.
     */
    private static int blankRawLineAt(String raw, int rawIndex, int rawLine) {
        int n = raw.length();
        int index = rawIndex;
        while (index < n && raw.charAt(index) != '\n') {
            if (!TextAlignment.isWhitespace(raw.charAt(index))) {
                return 0;
            }
            index++;
        }
        if (index >= n) {
            return 0;
        }
        index++;
        int end = index;
        while (end < n && raw.charAt(end) != '\n') {
            if (!TextAlignment.isWhitespace(raw.charAt(end))) {
                return 0;
            }
            end++;
        }
        return rawLine + 1;
    }

    /** Offset just past the blank raw line the pointer is on. */
    private static int blankLineEnd(String raw, int rawIndex) {
        int n = raw.length();
        int index = rawIndex;
        // Skip the rest of the current line.
        while (index < n && raw.charAt(index) != '\n') {
            index++;
        }
        if (index < n) {
            index++;
        }
        // Skip the blank line itself.
        while (index < n && raw.charAt(index) != '\n') {
            index++;
        }
        return index < n ? index + 1 : n;
    }
}
