package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                DisplayTextSplitter.split(raw, 5, ONE_PIXEL);
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
    void capsContinuationIndentOnAMostlyWhitespaceLine() {
        String text = " ".repeat(1_000) + "x".repeat(1_000);
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
        assertTrue(
                split.displayText().length() < text.length() * 4,
                "the display stays proportional to the input");
        for (int indent : split.breakIndents()) {
            assertTrue(indent <= 50, "the continuation indent is at most half a row");
        }
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
                DisplayTextSplitter.split(text, "text/json", 100, ONE_PIXEL);
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
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
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
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
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

    @Test
    void coloursLongRegexSplitAcrossTokenizerChunks() {
        String text = "var r=/" + "x".repeat(20_000) + "/;var b=2;";
        int start = text.indexOf('/') + 1;
        int end = start + 20_000;
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
        int coloured = 0;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                int original = split.toOriginalOffset(k);
                if (original >= start && original < end) {
                    assertEquals(TokenTypes.REGEX, type);
                    coloured++;
                }
            }
        }
        assertEquals(20_000, coloured);
    }

    @Test
    void coloursLongRegexWhenTheChunkEndsOnAnEscapedSlash() {
        String text =
                "var r=/" + "x".repeat(8185) + "\\/" + "y".repeat(20_000) + "/;var b=2;";
        int start = text.indexOf('/') + 1;
        int end = text.indexOf("/;var b");
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
        int coloured = 0;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                int original = split.toOriginalOffset(k);
                if (original >= start && original <= end) {
                    assertEquals(TokenTypes.REGEX, type);
                    coloured++;
                }
            }
        }
        assertEquals(end - start + 1, coloured);
    }

    @Test
    void coloursAnUnclosedLongRegexToTheEndOfTheLine() {
        String text = "var r=/" + "x".repeat(20_000);
        int start = text.indexOf('/') + 1;
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
        int coloured = 0;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                int original = split.toOriginalOffset(k);
                if (original >= start) {
                    assertEquals(TokenTypes.REGEX, type);
                    coloured++;
                }
            }
        }
        assertEquals(text.length() - start, coloured);
    }

    @Test
    void coloursLineCommentWhoseOpenerIsSplitAtAChunkBoundary() {
        String text = "x".repeat(TokenizeChunks.SIZE - 1) + "// " + "y".repeat(100);
        int start = TokenizeChunks.SIZE - 1;
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
        int coloured = 0;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                int original = split.toOriginalOffset(k);
                if (original >= start) {
                    assertEquals(TokenTypes.COMMENT_EOL, type);
                    coloured++;
                }
            }
        }
        assertEquals(text.length() - start, coloured);
    }

    @Test
    void coloursLineCommentAtAChunkBoundaryWhenAnEarlierSlashIsInTheChunk() {
        String text = "var r=/abc/;" + "x".repeat(8180) + "// comment";
        int start = TokenizeChunks.SIZE;
        assertTrue(text.startsWith("//", start), "comment opens at the chunk boundary");
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
        int coloured = 0;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                int original = split.toOriginalOffset(k);
                if (original >= start) {
                    assertEquals(TokenTypes.COMMENT_EOL, type);
                    coloured++;
                }
            }
        }
        assertEquals(text.length() - start, coloured);
    }

    @Test
    void doesNotColourDivisionAfterWhitespaceAtAChunkBoundaryAsRegex() {
        String text = "x".repeat(TokenizeChunks.SIZE) + "   / y;";
        int slash = text.indexOf('/');
        DisplayTextSplitter.Split split =
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
        boolean seen = false;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                if (split.toOriginalOffset(k) == slash) {
                    assertNotEquals(TokenTypes.REGEX, type);
                    seen = true;
                }
            }
        }
        assertTrue(seen, "the slash is tokenized");
    }

    /**
     * The wrap-off, read-only view splits through {@link DisplayTextSplitter#split(String, String)}:
     * a style with a token maker records its spans even when no row needed a break, so the document
     * replays them instead of tokenizing the text while it is filled, and the display is what the
     * chunked split always made of it.
     */
    @Test
    void theChunkedSplitRecordsSpansForTheDocumentToReplay() {
        String text = "var a=\"x\";\nvar b=2;\n";
        DisplayTextSplitter.Split split = DisplayTextSplitter.split(text, "text/javascript");
        assertTrue(split.tokenTypes().length > 0, "spans exist without wrap breaks");
        assertEquals(
                DisplayTextSplitter.split(text).displayText(),
                split.displayText(),
                "chunked breaks unchanged from the unstyled split");
    }

    /** No style (or a style with no token maker): the spans stay empty and the document tokenizes. */
    @Test
    void theChunkedSplitWithoutAStyleLeavesTheTokensToTheDocument() {
        String text = "var a=\"x\";\nvar b=2;";
        assertEquals(0, DisplayTextSplitter.split(text, null).tokenTypes().length);
    }

    /** A line nothing can open a construct with stays spanless and is tokenized lazily. */
    @Test
    void theChunkedSplitLeavesStatelessLinesUntokenized() {
        assertEquals(0, DisplayTextSplitter.split("var b = 2;", "text/javascript").tokenTypes().length);
    }

    /** A token a chunk break cut in half (a long string) keeps the colour of the original token. */
    @Test
    void theChunkedSplitKeepsTheColourOfATokenItsBreakCut() {
        String text = "var s=\"" + "a".repeat(6000) + "\";";
        DisplayTextSplitter.Split split = DisplayTextSplitter.split(text, "text/javascript");
        int valueStart = text.indexOf('"') + 1;
        int coloured = 0;
        for (int i = 0; i < split.tokenStarts().length; i++) {
            int type = split.tokenTypes()[i];
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                int original = split.toOriginalOffset(k);
                if (original >= valueStart && original < valueStart + 6000) {
                    assertEquals(
                            TokenTypes.LITERAL_STRING_DOUBLE_QUOTE,
                            type,
                            "the value keeps the string colour");
                    coloured++;
                }
            }
        }
        assertEquals(6000, coloured);
    }

    /**
     * The chunked split and the width-aware split tokenize the same text with the same machinery:
     * recorded for the whole text of the one and for the cut rows of the other, their
     * per-character types over the original text must agree, so a row colours identically however
     * the view pre-broke the text.
     */
    @Test
    void theChunkedAndWrappedSplitsColourTheOriginalTextAlike() {
        String text = "var s=\"" + "a".repeat(3000) + "\";var t='b'.repeat(2);";
        DisplayTextSplitter.Split wrapped =
                DisplayTextSplitter.split(text, "text/javascript", 100, ONE_PIXEL);
        DisplayTextSplitter.Split chunked = DisplayTextSplitter.split(text, "text/javascript");
        assertArrayEquals(
                typesByOriginalOffset(wrapped, text.length()),
                typesByOriginalOffset(chunked, text.length()),
                "per-character recorded types agree between the wrap-aware and chunked split");
    }

    /**
     * The recorded type for every offset of the original text, zero outside any span. No leading
     * whitespace in the input: a wrap continuation row copies its line's indent onto itself, and
     * that copy shares original offsets with content, which would make the map ambiguous.
     */
    private static int[] typesByOriginalOffset(DisplayTextSplitter.Split split, int length) {
        int[] types = new int[length];
        for (int i = 0; i < split.tokenStarts().length; i++) {
            for (int k = split.tokenStarts()[i]; k < split.tokenEnds()[i]; k++) {
                types[split.toOriginalOffset(k)] = split.tokenTypes()[i];
            }
        }
        return types;
    }
}
