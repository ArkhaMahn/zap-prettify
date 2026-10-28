package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class LineNumbersTest {

    @Test
    void formattedLinesKeepTheRawLineNumbers() {
        String raw = "{\"a\":1,\"b\":2}";
        String formatted = "{\n  \"a\": 1,\n  \"b\": 2\n}";
        assertArrayEquals(new int[] {1, 0, 0, 0}, LineNumbers.forDisplay(raw, formatted));
    }

    @Test
    void multipleRawLinesMapToOneDisplayLineEach() {
        String raw = "GET / HTTP/1.1\nHost: example.com\n\n{\"a\":1}";
        String formatted = "GET / HTTP/1.1\nHost: example.com\n\n{\n  \"a\": 1\n}";
        assertArrayEquals(new int[] {1, 2, 3, 4, 0, 0}, LineNumbers.forDisplay(raw, formatted));
    }

    @Test
    void nonWhitespaceDivergenceFallsBackToSequentialNumbers() {
        assertArrayEquals(new int[] {1, 2, 3}, LineNumbers.forDisplay("abc", "a\nx\nc"));
    }

    @Test
    void aBlankRawLineIsNumbered() {
        assertArrayEquals(new int[] {1, 2, 3}, LineNumbers.forDisplay("a\n\nb", "a\n\nb"));
    }

    @Test
    void aFormatterInsertedBlankLineStaysUnnumbered() {
        assertArrayEquals(new int[] {1, 0, 2}, LineNumbers.forDisplay("a\nb", "a\n\nb"));
    }

    @Test
    void aTrailingNewlineStillCountsAsARawLine() {
        assertArrayEquals(new int[] {1, 2}, LineNumbers.forDisplay("a\n", "a\n"));
    }
}
