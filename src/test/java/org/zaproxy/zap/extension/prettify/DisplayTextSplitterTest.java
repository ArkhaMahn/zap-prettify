package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DisplayTextSplitterTest {

    /** Every character is one pixel wide, which makes the expected break positions explicit. */
    private static final DisplayTextSplitter.CharWidths ONE_PIXEL = codePoint -> 1.0;

    @Test
    void splitsLongLinesAtTheGivenWidth() {
        DisplayTextSplitter.Split split = DisplayTextSplitter.split("abcdefghij", 5, ONE_PIXEL);
        assertEquals("abcde\nfghij", split.displayText());
        for (String line : split.displayText().split("\n", -1)) {
            assertTrue(line.length() <= 5, line);
        }
    }

    @Test
    void continuationRowsAreNotNumbered() {
        String raw = "abcdefghij";
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(raw, raw, null, 5, ONE_PIXEL);
        assertArrayEquals(new int[] {1, 0}, LineNumbers.forDisplay(raw, split.displayText()));
    }

    @Test
    void displayOffsetsMapBackToTheRawText() {
        String raw = "abcdefghij";
        DisplayTextSplitter.Split split = DisplayTextSplitter.split(raw, 5, ONE_PIXEL);
        String display = split.displayText();
        int displayStart = display.indexOf("fgh");
        int rawStart = TextAlignment.displayToRaw(display, raw, displayStart);
        assertEquals(raw.indexOf("fgh"), rawStart);
        assertEquals(
                3, TextAlignment.displayToRawEnd(display, raw, displayStart + 3) - rawStart);
    }
}
