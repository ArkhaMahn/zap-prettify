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

    @Test
    void keepsLongRegexTogetherAcrossTokenizeChunks() {
        String text = "var r=/" + "x".repeat(20_000) + "/;var b=2;";
        int start = text.indexOf('/') + 1;
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        for (int i = start; i < start + 20_000; i++) {
            assertTrue(lex.opaque[i], "regex at " + i);
        }
        assertFalse(lex.opaque[text.indexOf("var b")], "code after the regex");
    }

    @Test
    void keepsLongRegexTogetherWhenTheChunkEndsOnAnEscapedSlash() {
        String text =
                "var r=/" + "x".repeat(8185) + "\\/" + "y".repeat(20_000) + "/;var b=2;";
        int start = text.indexOf('/') + 1;
        int end = text.indexOf("/;var b");
        assertTrue(end > start + 20_000, "the escaped slash is inside the regex");
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        for (int i = start; i <= end; i++) {
            assertTrue(lex.opaque[i], "regex at " + i);
        }
        assertFalse(lex.opaque[text.indexOf("var b")], "code after the regex");
    }

    @Test
    void marksAnUnclosedLongRegexToTheEndOfTheLine() {
        String text = "var r=/" + "x".repeat(20_000);
        int start = text.indexOf('/') + 1;
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        for (int i = start; i < text.length(); i++) {
            assertTrue(lex.opaque[i], "unclosed regex at " + i);
        }
    }

    @Test
    void keepsLineCommentWhoseOpenerIsAtAChunkBoundary() {
        String text = "x".repeat(TokenizeChunks.SIZE) + "// comment\nvar b=2;";
        int start = TokenizeChunks.SIZE;
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        for (int i = start; i < text.indexOf('\n'); i++) {
            assertTrue(lex.opaque[i], "comment at " + i);
            assertTrue(lex.lineComment[i], "line comment at " + i);
        }
        assertFalse(lex.opaque[text.indexOf("var b")], "code after the comment");
    }

    @Test
    void keepsLineCommentAtAChunkBoundaryWhenAnEarlierSlashIsInTheChunk() {
        String text = "var r=/abc/;" + "x".repeat(8180) + "// comment\nvar b=2;";
        int start = TokenizeChunks.SIZE;
        assertTrue(text.startsWith("//", start), "comment opens at the chunk boundary");
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        for (int i = start; i < text.indexOf('\n'); i++) {
            assertTrue(lex.opaque[i], "comment at " + i);
            assertTrue(lex.lineComment[i], "line comment at " + i);
        }
        assertFalse(lex.opaque[text.indexOf("var b")], "code after the comment");
    }

    @Test
    void keepsLineCommentWhoseOpenerIsSplitAtAChunkBoundary() {
        String text = "x".repeat(TokenizeChunks.SIZE - 1) + "// " + "y".repeat(100) + "\nvar b=2;";
        int start = TokenizeChunks.SIZE - 1;
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        for (int i = start; i < text.indexOf('\n'); i++) {
            assertTrue(lex.opaque[i], "comment at " + i);
            assertTrue(lex.lineComment[i], "line comment at " + i);
        }
        assertFalse(lex.opaque[text.indexOf("var b")], "code after the comment");
    }

    @Test
    void keepsBlockCommentWhoseOpenerIsSplitAtAChunkBoundary() {
        String text =
                "x".repeat(TokenizeChunks.SIZE - 1)
                        + "/*"
                        + "y".repeat(20_000)
                        + "*/var b=2;";
        int start = TokenizeChunks.SIZE - 1;
        int end = text.indexOf("*/");
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        for (int i = start; i <= end + 1; i++) {
            assertTrue(lex.opaque[i], "comment at " + i);
        }
        assertFalse(lex.opaque[text.indexOf("var b")], "code after the comment");
    }

    @Test
    void keepsBlockCommentWhoseCloserIsSplitAtAChunkBoundary() {
        String text = "/*" + "y".repeat(TokenizeChunks.SIZE - 3) + "*/var b=2;";
        int end = text.indexOf("*/");
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        assertTrue(lex.opaque[end + 1], "comment end");
        assertFalse(lex.opaque[text.indexOf("var b")], "code after the comment");
    }

    @Test
    void doesNotTreatDivisionAtAChunkBoundaryAsRegex() {
        String text = "x".repeat(TokenizeChunks.SIZE) + "/ y;";
        int slash = TokenizeChunks.SIZE;
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        assertFalse(lex.opaque[slash], "division at the boundary");
    }

    @Test
    void doesNotTreatDivisionAfterWhitespaceAtAChunkBoundaryAsRegex() {
        String text = "x".repeat(TokenizeChunks.SIZE) + "   / y;";
        int slash = text.indexOf('/');
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        assertFalse(lex.opaque[slash], "division after the boundary");
    }

    @Test
    void keepsRegexWhoseOpenerIsAtAChunkBoundary() {
        String text = "x".repeat(TokenizeChunks.SIZE - 1) + "=/abc/;";
        int start = text.indexOf('/') + 1;
        Lexer lex = Lexer.of("text/javascript", text);
        assertNotNull(lex);
        for (int i = start; i < start + 3; i++) {
            assertTrue(lex.opaque[i], "regex at " + i);
        }
    }
}
