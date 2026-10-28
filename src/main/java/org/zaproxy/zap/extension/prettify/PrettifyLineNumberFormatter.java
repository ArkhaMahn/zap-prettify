package org.zaproxy.zap.extension.prettify;

import org.fife.ui.rtextarea.LineNumberFormatter;

/**
 * A gutter line-number formatter that shows a number only on rows that start a real message line.
 *
 * <p>The view pre-splits overlong lines (and an editable view may soft-wrap them further). Those
 * continuation rows must not be numbered, otherwise a single message line appears as dozens of
 * numbered rows. Only the first display line of each original message line gets its number.
 */
public final class PrettifyLineNumberFormatter implements LineNumberFormatter {

    private final int[] displayLineNumbers;
    private final int maxLength;

    public PrettifyLineNumberFormatter(int[] displayLineNumbers, int originalLineCount) {
        this.displayLineNumbers = displayLineNumbers;
        // RTextArea sizes the gutter from getMaxLength() + 1 digit, so ask for one digit less than
        // the widest number actually shown; otherwise the column keeps a blank digit of padding.
        this.maxLength = Math.max(0, Integer.toString(originalLineCount).length() - 1);
    }

    @Override
    public String format(int lineNumber) {
        int index = lineNumber - 1;
        if (displayLineNumbers == null || index < 0 || index >= displayLineNumbers.length) {
            return "";
        }
        int originalLine = displayLineNumbers[index];
        return originalLine > 0 ? Integer.toString(originalLine) : "";
    }

    @Override
    public int getMaxLength(int maxLineNumber) {
        return maxLength;
    }
}
