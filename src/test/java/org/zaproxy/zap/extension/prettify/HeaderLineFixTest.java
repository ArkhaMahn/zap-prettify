package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class HeaderLineFixTest {

    @Test
    void colonLessHeaderLinesGetTheEmptyValueCoreWouldWrite() {
        String text = "POST / HTTP/1.1\nHost: example.test\nxxx\nY: 1\n\nbody line\nno colon here";
        assertEquals(
                "POST / HTTP/1.1\nHost: example.test\nxxx:\nY: 1\n\nbody line\nno colon here",
                HeaderLineFix.fixMalformedHeaderLines(text));
    }

    @Test
    void wellFormedTextIsLeftAlone() {
        String text = "GET / HTTP/1.1\nHost: example.test\n\nplain body";
        assertSame(text, HeaderLineFix.fixMalformedHeaderLines(text));
    }

    @Test
    void whitespaceOnlyAndContinuationLinesAreLeftAlone() {
        String text = "GET / HTTP/1.1\nHost: example.test\n   \nX: a\n  continued\n\nb";
        assertSame(text, HeaderLineFix.fixMalformedHeaderLines(text));
    }

    @Test
    void headerOnlyTextIsFixedUntilTheEnd() {
        String text = "PUT / HTTP/1.1\nHost: example.test\nxxx\nyyy";
        assertEquals(
                "PUT / HTTP/1.1\nHost: example.test\nxxx:\nyyy:",
                HeaderLineFix.fixMalformedHeaderLines(text));
    }

    @Test
    void duplicatesAreKeptInOrder() {
        String text = "GET / HTTP/1.1\naaa\nX: 1\naaa\n\n";
        assertEquals(
                "GET / HTTP/1.1\naaa:\nX: 1\naaa:\n\n",
                HeaderLineFix.fixMalformedHeaderLines(text));
    }

    @Test
    void theRequestLineIsNotGivenAColon() {
        String text = "xxx\nHost: example.test\n\nbody";
        assertEquals(
                "xxx\nHost: example.test\n\nbody",
                HeaderLineFix.fixMalformedHeaderLines(text));
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertEquals(null, HeaderLineFix.fixMalformedHeaderLines(null));
        String empty = "";
        assertSame(empty, HeaderLineFix.fixMalformedHeaderLines(empty));
    }

    @Test
    void lineEndingsWithCarriageReturnAreFixed() {
        String text = "GET / HTTP/1.1\r\nxxx\r\nHost: example.test\r\n\r\nbody";
        assertEquals(
                "GET / HTTP/1.1\r\nxxx:\r\nHost: example.test\r\n\r\nbody",
                HeaderLineFix.fixMalformedHeaderLines(text));
    }
}
