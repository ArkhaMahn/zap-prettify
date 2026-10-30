package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LexerTest {

    @Test
    void keepsMultilineCommentStateAcrossTokenizeChunks() {
        StringBuilder body = new StringBuilder("/*");
        for (int i = 0; i < 20_000; i++) {
            body.append((char) ('a' + i % 26));
        }
        body.append("*/var x=1;");
        String text = body.toString();
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        int commentEnd = text.indexOf("*/");
        for (int i = 0; i < commentEnd; i++) {
            assertTrue(lex.opaque[i], "comment at " + i);
        }
        assertTrue(lex.opaque[commentEnd], "comment end");
        assertTrue(!lex.opaque[commentEnd + 2], "code after the comment");
    }
}
