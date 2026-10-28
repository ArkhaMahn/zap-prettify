package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MessageOffsetsTest {

    private static final String HEADER = "GET / HTTP/1.1\nHost: example.com\n\n";

    @Test
    void theBodyStartsAfterTheFirstBlankLine() {
        String message = "GET / HTTP/1.1\nHost: example.com\n\nbody\n\nmore";
        assertEquals(
                message.indexOf("\n\n") + 2, PrettifyHttpPanelView.bodyStartInLf(message));
    }

    @Test
    void aMessageWithoutABodyHasNoBodyOffset() {
        assertEquals(HEADER.length(), PrettifyHttpPanelView.bodyStartInLf(HEADER));
    }

    @Test
    void aMessageWithoutASeparatorHasNoBodyOffset() {
        String message = "GET / HTTP/1.1\nHost: example.com";
        assertEquals(message.length(), PrettifyHttpPanelView.bodyStartInLf(message));
    }
}
