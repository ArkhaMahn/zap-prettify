package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertFalse(lex.opaque[commentEnd + 2], "code after the comment");
    }

    @Test
    void keepsLineCommentStateAcrossTokenizeChunks() {
        String text = "var a=1; // " + "x".repeat(20_000) + "\nvar b=2;";
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        int start = text.indexOf("// ") + 3;
        for (int i = start; i < start + 20_000; i++) {
            assertTrue(lex.opaque[i], "comment at " + i);
        }
        assertFalse(lex.opaque[text.indexOf("var b")], "code after the comment");
    }

    @Test
    void keepsStringStateAcrossTokenizeChunks() {
        String text = "var s=\"" + "x".repeat(20_000) + "\";var b=2;";
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        int start = text.indexOf('"') + 1;
        for (int i = start; i < start + 20_000; i++) {
            assertTrue(lex.opaque[i], "string at " + i);
        }
        assertFalse(lex.opaque[text.indexOf("var b")], "code after the string");
    }

    @Test
    void keepsShortRegexTogetherWhenItStraddlesAChunkBoundary() {
        String text = "x".repeat(8183) + "var r=/abc/;";
        int slash = text.indexOf("/abc");
        assertTrue(slash < TokenizeChunks.SIZE, "regex opens in the first chunk");
        assertTrue(slash + 5 > TokenizeChunks.SIZE, "regex closes in the next chunk");
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        for (int i = slash + 1; i < slash + 4; i++) {
            assertTrue(lex.opaque[i], "regex at " + i);
        }
    }
}
