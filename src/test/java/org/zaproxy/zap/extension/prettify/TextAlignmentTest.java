package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextAlignmentTest {

    private static final String RAW_JSON = "{\"a\":1,\"b\":2}";
    private static final String FORMATTED_JSON = "{\n  \"a\": 1,\n  \"b\": 2\n}";

    @Test
    void untouchedFormattedTextLeavesRawUnchanged() {
        assertEquals(RAW_JSON, TextAlignment.applyEditToRaw(RAW_JSON, FORMATTED_JSON, FORMATTED_JSON));
    }

    @Test
    void editingAValueChangesOnlyThatValueInRaw() {
        String edited = FORMATTED_JSON.replace("\"a\": 1", "\"a\": 9");
        assertEquals(
                "{\"a\":9,\"b\":2}",
                TextAlignment.applyEditToRaw(RAW_JSON, FORMATTED_JSON, edited));
    }

    @Test
    void editingAKeyChangesOnlyThatKeyInRaw() {
        String edited = FORMATTED_JSON.replace("\"b\"", "\"c\"");
        assertEquals(
                "{\"a\":1,\"c\":2}",
                TextAlignment.applyEditToRaw(RAW_JSON, FORMATTED_JSON, edited));
    }

    @Test
    void deletingAValueRemovesItFromRaw() {
        String edited = FORMATTED_JSON.replace("\"b\": 2", "");
        assertEquals(
                "{\"a\":1,}",
                TextAlignment.applyEditToRaw(RAW_JSON, FORMATTED_JSON, edited));
    }

    @Test
    void whitespaceOnlyEditIsAppliedToRaw() {
        String edited = FORMATTED_JSON.replace("  ", "    ");
        String result = TextAlignment.applyEditToRaw(RAW_JSON, FORMATTED_JSON, edited);
        assertNotEquals(RAW_JSON, result);
        assertTrue(result.contains("\n"));
        assertTrue(TextAlignment.sameNonWhitespace(RAW_JSON, result));
    }

    @Test
    void insertingALineBreakInABodyIsAppliedToRaw() {
        String raw = "{\"a\":1,\"b\":2}";
        String formatted = "{\n  \"a\": 1,\n  \"b\": 2\n}";
        String edited = formatted.replace("\"a\": 1,", "\"a\": 1,\n  ");
        String result = TextAlignment.applyEditToRaw(raw, formatted, edited);
        assertTrue(result.contains("\n"));
        assertTrue(TextAlignment.sameNonWhitespace(raw, result));
    }

    @Test
    void appendingToAStrippedHeaderKeepsTheTerminator() {
        String raw = "GET / HTTP/1.1\nHost: example.com\n\n";
        String formatted = "GET / HTTP/1.1\nHost: example.com";
        assertEquals(
                "GET / HTTP/1.1\nHost: example.comX\n\n",
                TextAlignment.applyEditToRaw(raw, formatted, formatted + "X"));
    }

    @Test
    void editingWhitespaceInsideAStrippedHeaderIsKept() {
        String raw = "GET / HTTP/1.1\nHost: example.com\n\n";
        String formatted = "GET / HTTP/1.1\nHost: example.com";
        String edited = "GET / HTTP/1.1\nHost:  example.com";
        assertEquals(
                "GET / HTTP/1.1\nHost:  example.com\n\n",
                TextAlignment.applyEditToRaw(raw, formatted, edited));
    }

    @Test
    void appendingABodyToAStrippedHeaderDoesNotDuplicateTheTerminator() {
        String raw = "GET / HTTP/1.1\nHost: example.com\n\n";
        String formatted = "GET / HTTP/1.1\nHost: example.com";
        String edited = formatted + "\n\ntext";
        assertEquals(
                "GET / HTTP/1.1\nHost: example.com\n\ntext",
                TextAlignment.applyEditToRaw(raw, formatted, edited));
    }

    @Test
    void appendingABodyWithCrlfToAStrippedHeaderDoesNotDuplicateTheTerminator() {
        String raw = "GET / HTTP/1.1\nHost: example.com\n\n";
        String formatted = "GET / HTTP/1.1\nHost: example.com";
        String edited = formatted + "\r\n\r\ntext";
        assertEquals(edited, TextAlignment.applyEditToRaw(raw, formatted, edited));
    }

    @Test
    void addingOnlyTheHeaderTerminatorDoesNotGrowIt() {
        String raw = "GET / HTTP/1.1\nHost: example.com\n\n";
        String formatted = "GET / HTTP/1.1\nHost: example.com";
        String edited = formatted + "\n\n";
        assertEquals(edited, TextAlignment.applyEditToRaw(raw, formatted, edited));
    }

    @Test
    void displayOffsetsMapToRawOffsets() {
        int displayStart = FORMATTED_JSON.indexOf("\"b\"");
        int rawStart = TextAlignment.displayToRaw(FORMATTED_JSON, RAW_JSON, displayStart);
        assertEquals(RAW_JSON.indexOf("\"b\""), rawStart);

        int displayEnd = displayStart + 3;
        int rawEnd = TextAlignment.displayToRawEnd(FORMATTED_JSON, RAW_JSON, displayEnd);
        assertEquals(RAW_JSON.indexOf("\"b\"") + 3, rawEnd);
    }

    @Test
    void rawOffsetsMapBackToDisplayOffsets() {
        int rawStart = RAW_JSON.indexOf("2");
        int displayStart = TextAlignment.rawToDisplay(RAW_JSON, FORMATTED_JSON, rawStart);
        assertEquals(FORMATTED_JSON.indexOf("2"), displayStart);

        int rawEnd = rawStart + 1;
        int displayEnd = TextAlignment.rawToDisplayEnd(RAW_JSON, FORMATTED_JSON, rawEnd);
        assertEquals(FORMATTED_JSON.indexOf("2") + 1, displayEnd);
    }

    @Test
    void sameNonWhitespaceIgnoresWhitespaceButNotCharacters() {
        assertTrue(TextAlignment.sameNonWhitespace(RAW_JSON, FORMATTED_JSON));
        assertFalse(TextAlignment.sameNonWhitespace(RAW_JSON, FORMATTED_JSON.replace("1", "9")));
        assertFalse(TextAlignment.sameNonWhitespace("a b", "ab c"));
    }

    @Test
    void addingAHeaderAfterTheLastHeaderLineKeepsItOnItsOwnLine() {
        String raw =
                "POST / HTTP/1.1\n"
                        + "Host: example.com\n"
                        + "X-Test: one\n"
                        + "X-Test: two\n"
                        + "\n"
                        + "{\"a\":1,\"b\":2}";
        String formatted =
                "POST / HTTP/1.1\n"
                        + "Host: example.com\n"
                        + "X-Test: one\n"
                        + "X-Test: two\n"
                        + "\n"
                        + "{\n  \"a\": 1,\n  \"b\": 2\n}";
        String edited =
                "POST / HTTP/1.1\n"
                        + "Host: example.com\n"
                        + "X-Test: one\n"
                        + "X-Test: two\n"
                        + "X-Test: three\n"
                        + "\n"
                        + "{\n  \"a\": 1,\n  \"b\": 2\n}";

        assertEquals(
                "POST / HTTP/1.1\n"
                        + "Host: example.com\n"
                        + "X-Test: one\n"
                        + "X-Test: two\n"
                        + "X-Test: three\n"
                        + "\n"
                        + "{\"a\":1,\"b\":2}",
                TextAlignment.applyEditToRaw(raw, formatted, edited));
    }

    @Test
    void duplicatingAHeaderKeepsBothCopiesAndTheirOrder() {
        String raw =
                "POST / HTTP/1.1\n"
                        + "Host: example.com\n"
                        + "X-Test: one\n"
                        + "X-Other: value\n"
                        + "\n"
                        + "{\"a\":1}";
        String formatted =
                "POST / HTTP/1.1\n"
                        + "Host: example.com\n"
                        + "X-Test: one\n"
                        + "X-Other: value\n"
                        + "\n"
                        + "{\n  \"a\": 1\n}";
        String edited =
                "POST / HTTP/1.1\n"
                        + "Host: example.com\n"
                        + "X-Test: one\n"
                        + "X-Test: one\n"
                        + "X-Other: value\n"
                        + "\n"
                        + "{\n  \"a\": 1\n}";

        assertEquals(
                "POST / HTTP/1.1\n"
                        + "Host: example.com\n"
                        + "X-Test: one\n"
                        + "X-Test: one\n"
                        + "X-Other: value\n"
                        + "\n"
                        + "{\"a\":1}",
                TextAlignment.applyEditToRaw(raw, formatted, edited));
    }

    @Test
    void formBodiesAreNotFormatted() {
        String raw = "a=1&b=2&c=3";
        assertEquals(raw, PrettyPrint.formatBody(ContentType.FORM_STYLE, raw));
        assertEquals(raw, TextAlignment.applyEditToRaw(raw, raw, raw));
    }

    @Test
    void formBodyEditsAreAppliedToTheRawBody() {
        String raw = "a=1&b=2";
        String edited = raw.replace("b=2", "b=9");
        assertEquals("a=1&b=9", TextAlignment.applyEditToRaw(raw, raw, edited));
    }
}
