package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fife.ui.rsyntaxtextarea.TokenTypes;
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

    @Test
    void coloursLongStringsSplitAcrossTokenizerChunks() {
        StringBuilder raw = new StringBuilder("{\"value\":\"");
        for (int i = 0; i < 20_000; i++) {
            raw.append((char) ('a' + i % 26));
        }
        raw.append("\"}");
        String text = raw.toString();
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, text, "text/json", 100, ONE_PIXEL);
        int valueStart = text.indexOf(":\"") + 2;
        int valueEnd = valueStart + 20_000;
        int coloured = 0;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                int original = split.toOriginalOffset(k);
                if (original >= valueStart && original < valueEnd) {
                    assertEquals(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, type);
                    coloured++;
                }
            }
        }
        assertEquals(20_000, coloured);
    }

    @Test
    void coloursLongLineCommentsSplitAcrossTokenizerChunks() {
        String text = "var a=1; // " + "x".repeat(20_000) + "\nvar b=2;";
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, text, "text/javascript", 100, ONE_PIXEL);
        int commentStart = text.indexOf("// ") + 3;
        int commentEnd = commentStart + 20_000;
        int coloured = 0;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                int original = split.toOriginalOffset(k);
                if (original >= commentStart && original < commentEnd) {
                    assertEquals(TokenTypes.COMMENT_EOL, type);
                    coloured++;
                }
            }
        }
        assertEquals(20_000, coloured);
    }

    @Test
    void keepsShortRegexTogetherWhenItStraddlesAChunkBoundary() {
        String text = "x".repeat(8183) + "var r=/abc/;";
        int slash = text.indexOf("/abc");
        assertTrue(slash < TokenizeChunks.SIZE);
        assertTrue(slash + 5 > TokenizeChunks.SIZE);
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, text, "text/javascript", 100, ONE_PIXEL);
        int coloured = 0;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                int original = split.toOriginalOffset(k);
                if (original > slash && original < slash + 4) {
                    assertEquals(TokenTypes.REGEX, type);
                    coloured++;
                }
            }
        }
        assertEquals(3, coloured);
    }
}
