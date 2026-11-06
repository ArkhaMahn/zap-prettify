package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrettyPrintTest {

    @Test
    void unclosedInlineTagDoesNotThrowAndKeepsItsText() {
        String formatted =
                assertDoesNotThrow(() -> PrettyPrint.formatBody("text/html", "<p>unclosed"));
        assertEquals("<p>unclosed\n", formatted);
    }

    @Test
    void deeplyNestedMarkupStopsRecursingAtTheRenderLimit() {
        int depth = 5_000;
        StringBuilder html = new StringBuilder(depth * 11 + 16);
        for (int i = 0; i < depth; i++) {
            html.append("<div>");
        }
        html.append("deep");
        for (int i = 0; i < depth; i++) {
            html.append("</div>");
        }
        String formatted =
                assertDoesNotThrow(
                        () -> PrettyPrint.formatBody("text/html", html.toString()));
        assertTrue(formatted.contains("deep"), "the content survives the depth limit");
        assertEquals(depth, count(formatted, "<div>"), "every open tag is emitted once");
        assertEquals(depth, count(formatted, "</div>"), "every close tag is emitted once");
        assertTrue(
                formatted.lines().count() < depth,
                "the truncated subtree is emitted flat instead of one line per level");
    }

    private static int count(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    @Test
    void keepsLineCommentAfterAClosingBraceOnItsOwnLine() {
        String formatted =
                PrettyPrint.formatBody(
                        "text/javascript",
                        "function f() {\n  if (x) {\n    y();\n  }\n  // note\n  return 1;\n}\n");
        assertEquals(
                "function f() {\n  if (x) {\n    y();\n  }\n  // note\n  return 1;\n}",
                formatted);
    }

    @Test
    void keepsMultiLineDocCommentVerbatimAndSeparatesItFromWhatFollows() {
        String formatted =
                PrettyPrint.formatBody(
                        "text/javascript",
                        "/**\n * @param {ArrayBuffer} bytes\n * @see https://example.com\n"
                                + " */\nfunction f(bytes) {\n  return 1;\n}\n");
        assertEquals(
                "/**\n * @param {ArrayBuffer} bytes\n * @see https://example.com\n"
                        + " */\nfunction f(bytes) {\n  return 1;\n}",
                formatted);
    }

    @Test
    void keepsMultiLineBlockCommentBetweenStatements() {
        String formatted =
                PrettyPrint.formatBody(
                        "text/javascript",
                        "var a = 1;\n/* multi\nline */\nvar b = 2;\n");
        assertEquals("var a = 1;\n/* multi\nline */\nvar b = 2;", formatted);
    }

    @Test
    void keepsTrailingBlockCommentOnOneLine() {
        String formatted =
                PrettyPrint.formatBody("text/javascript", "{ a(); } /* trailing */ b();\n");
        assertEquals("{\n  a();\n} /* trailing */ b();", formatted);
    }
}
