package org.zaproxy.zap.extension.prettify;

/**
 * Maps offsets between the raw message and the pretty-printed projection shown in the view.
 *
 * <p>The pretty printer only ever inserts, removes or reflows whitespace, so the two texts contain
 * the same non-whitespace characters in the same order. Walking them together gives an exact
 * mapping in both directions, which is what search, highlighting, copy and save use. Keeping the raw
 * message as the source of truth means an untouched view never rewrites the message and an edit only
 * changes the characters the user actually touched.
 */
public final class TextAlignment {

    private TextAlignment() {}

    /** Whether {@code a} and {@code b} contain the same non-whitespace characters in order. */
    public static boolean sameNonWhitespace(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        int i = 0;
        int j = 0;
        int an = a.length();
        int bn = b.length();
        while (true) {
            while (i < an && isWhitespace(a.charAt(i))) {
                i++;
            }
            while (j < bn && isWhitespace(b.charAt(j))) {
                j++;
            }
            if (i >= an || j >= bn) {
                return i >= an && j >= bn;
            }
            if (a.charAt(i) != b.charAt(j)) {
                return false;
            }
            i++;
            j++;
        }
    }

    /**
     * Maps an offset in the displayed (formatted) text to the corresponding offset in the raw text.
     * Returns -1 when the two texts are not whitespace-equivalent.
     */
    public static int displayToRaw(String display, String raw, int offset) {
        if (display == null || raw == null || !sameNonWhitespace(display, raw)) {
            return -1;
        }
        int at = clamp(offset, 0, display.length());
        int count = nonWhitespaceBefore(display, at);
        if (at < display.length() && !isWhitespace(display.charAt(at))) {
            return indexOfNonWhitespace(raw, count + 1);
        }
        return offsetAfterNonWhitespace(raw, count);
    }

    /**
     * Maps the end of a range in the displayed text to the end of the range in the raw text. Unlike
     * {@link #displayToRaw} this maps just past the last non-whitespace character before the
     * offset, so a range such as a search match keeps its end exactly where it is.
     */
    public static int displayToRawEnd(String display, String raw, int offset) {
        if (display == null || raw == null || !sameNonWhitespace(display, raw)) {
            return -1;
        }
        int at = clamp(offset, 0, display.length());
        return offsetAfterNonWhitespace(raw, nonWhitespaceBefore(display, at));
    }

    /**
     * Maps an offset in the raw text to the corresponding offset in the displayed (formatted) text.
     * Returns -1 when the two texts are not whitespace-equivalent.
     */
    public static int rawToDisplay(String raw, String display, int offset) {
        if (raw == null || display == null || !sameNonWhitespace(raw, display)) {
            return -1;
        }
        int at = clamp(offset, 0, raw.length());
        int count = nonWhitespaceBefore(raw, at);
        if (at < raw.length() && !isWhitespace(raw.charAt(at))) {
            return indexOfNonWhitespace(display, count + 1);
        }
        return offsetAfterNonWhitespace(display, count);
    }

    /**
     * Maps the end of a range in the raw text to the end of the range in the displayed text. Unlike
     * {@link #rawToDisplay} this maps just past the last non-whitespace character before the
     * offset.
     */
    public static int rawToDisplayEnd(String raw, String display, int offset) {
        if (raw == null || display == null || !sameNonWhitespace(raw, display)) {
            return -1;
        }
        int at = clamp(offset, 0, raw.length());
        return offsetAfterNonWhitespace(display, nonWhitespaceBefore(raw, at));
    }

    /**
     * Applies the change the user made in the formatted text to the raw message text.
     *
     * <p>Only the changed range is rewritten: everything outside it keeps the exact characters and
     * whitespace of the raw message. When the texts are not whitespace-equivalent (a formatter that
     * changed more than whitespace) the edited text is returned unchanged so the caller can decide
     * not to save.
     */
    public static String applyEditToRaw(String raw, String formatted, String edited) {
        if (raw == null || edited == null || formatted == null) {
            return edited;
        }
        if (formatted.equals(edited)) {
            return raw;
        }
        if (formatted.equals(raw)) {
            // The view is not a projection (nothing was pretty-printed), so the edit applies as-is.
            return edited;
        }
        if (!sameNonWhitespace(formatted, raw)) {
            return edited;
        }
        if (raw.startsWith(formatted) && stripTrailingNewlines(raw).equals(formatted)) {
            // The only difference is the trailing newline(s) the view hides (a header terminator):
            // keep them and apply the edit at the same offsets. If the edit has supplied a blank
            // line of its own, that line is now the header/body separator; appending the hidden
            // terminator would push those newlines into the new body.
            if (hasBlankLine(edited)) {
                return edited;
            }
            return edited + raw.substring(formatted.length());
        }
        // A difference in whitespace only is still a user edit (indentation or a line break the
        // user typed), so it is applied to the raw text like any other change.
        int limit = Math.min(formatted.length(), edited.length());
        int prefix = 0;
        while (prefix < limit && formatted.charAt(prefix) == edited.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && formatted.charAt(formatted.length() - 1 - suffix)
                        == edited.charAt(edited.length() - 1 - suffix)) {
            suffix++;
        }

        int formattedStart = prefix;
        int formattedEnd = formatted.length() - suffix;
        String replacement = edited.substring(formattedStart, edited.length() - suffix);

        int before = nonWhitespaceBefore(formatted, formattedStart);
        int after = nonWhitespaceBefore(formatted, formattedEnd);

        boolean startsOnCharacter =
                formattedStart < formatted.length()
                        && !isWhitespace(formatted.charAt(formattedStart));
        int rawStart =
                startsOnCharacter
                        ? indexOfNonWhitespace(raw, before + 1)
                        : offsetAfterNonWhitespace(raw, before);

        int rawEnd;
        if (formattedEnd == formattedStart) {
            rawEnd = rawStart;
        } else if (!isWhitespace(formatted.charAt(formattedEnd - 1))) {
            rawEnd = offsetAfterNonWhitespace(raw, after);
        } else {
            rawEnd = indexOfNonWhitespace(raw, after + 1);
        }
        if (rawStart < 0 || rawEnd < rawStart || rawEnd > raw.length()) {
            return edited;
        }

        if (rawStart == rawEnd && replacement.isEmpty()) {
            return raw;
        }
        return raw.substring(0, rawStart) + replacement + raw.substring(rawEnd);
    }

    /** Removes trailing CR/LF characters from {@code text}. */
    private static String stripTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    /**
     * Whether {@code text} contains an empty line, which separates an HTTP header block from its
     * body. Both LF and CRLF line endings are recognised; a line containing only spaces is not
     * empty.
     */
    private static boolean hasBlankLine(String text) {
        int lineBreaks = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                lineBreaks++;
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (c == '\n') {
                lineBreaks++;
            } else {
                lineBreaks = 0;
            }
            if (lineBreaks >= 2) {
                return true;
            }
        }
        return false;
    }

    /** Number of characters that are not whitespace in {@code text[0, end)}. */
    public static int nonWhitespaceBefore(String text, int end) {
        int limit = clamp(end, 0, text.length());
        int count = 0;
        for (int i = 0; i < limit; i++) {
            if (!isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /** Offset of the {@code count}th (1-based) non-whitespace character, or the text length. */
    public static int indexOfNonWhitespace(String text, int count) {
        if (count <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!isWhitespace(text.charAt(i))) {
                seen++;
                if (seen == count) {
                    return i;
                }
            }
        }
        return text.length();
    }

    /** Offset just after the {@code count}th (1-based) non-whitespace character. */
    public static int offsetAfterNonWhitespace(String text, int count) {
        if (count <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!isWhitespace(text.charAt(i))) {
                seen++;
                if (seen == count) {
                    return i + 1;
                }
            }
        }
        return text.length();
    }

    /** Number of lines in {@code text} (a trailing newline starts an empty last line). */
    public static int countLines(String text) {
        int lines = 1;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
        }
        return lines;
    }

    static boolean isWhitespace(char c) {
        return Character.isWhitespace(c);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
