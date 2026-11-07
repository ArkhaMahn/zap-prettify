package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

class HttpTokenMakerTest {

    @Test
    void bodyOfAnUnstatedRequestHighlightsAsFormParameters() {
        List<Token> body =
                lastLineOf(
                        new HttpRequestTokenMaker(),
                        "POST / HTTP/1.1",
                        "Host: example.test",
                        "",
                        "k=v&k2=v2");
        assertFirstMeaningfulTokenIs(body, PrettifyTokenTypes.HTTP_PARAMETER_NAME);
    }

    @Test
    void bodyOfAStatedRequestHighlightsWithTheStatedType() {
        List<Token> body =
                lastLineOf(
                        new HttpRequestTokenMaker(),
                        "PATCH / HTTP/1.1",
                        "Content-Type: application/json",
                        "",
                        "{\"k\": 1}");
        assertFirstMeaningfulTokenIs(body, TokenTypes.SEPARATOR);
    }

    @Test
    void bodyOfARequestWhoseTypeStylesNothingHighlightsAsFormParameters() {
        List<Token> body =
                lastLineOf(
                        new HttpRequestTokenMaker(),
                        "PUT / HTTP/1.1",
                        "Host: example.test",
                        "Content-Type: text/plain",
                        "",
                        "k=v&k2=v2");
        assertFirstMeaningfulTokenIs(body, PrettifyTokenTypes.HTTP_PARAMETER_NAME);
    }

    @Test
    void bodyOfAnUnstatedResponseStaysUnhighlighted() {
        List<Token> body =
                lastLineOf(new HttpResponseTokenMaker(), "HTTP/1.1 200 OK", "", "plain text");
        assertFirstMeaningfulTokenIs(body, TokenTypes.IDENTIFIER);
    }

    @Test
    void bodyResolvedAsFormIsRehighlightedWhenAStatedTypeFollows() {
        HttpRequestTokenMaker tm = new HttpRequestTokenMaker();
        // The body is typed before its Content-Type header exists: the urlencoded form default.
        List<Token> form = lastLineOf(tm, "POST / HTTP/1.1", "Host: example.test", "", "foo=bar");
        assertFirstMeaningfulTokenIs(form, PrettifyTokenTypes.HTTP_PARAMETER_NAME);

        // The same maker then tokenizes the request with the header present, as the document's
        // re-tokenization of the edited message does: the stated type supersedes the form default
        // with the next body line.
        List<Token> json =
                lastLineOf(
                        tm,
                        "PATCH / HTTP/1.1",
                        "Content-Type: application/json",
                        "",
                        "{\"k\": 1}");
        assertFirstMeaningfulTokenIs(json, TokenTypes.SEPARATOR);
    }

    /**
     * Tokenizes every line through one token maker, carrying the state each line leaves as the
     * document does ({@link HttpTokenMaker#resetAfterLine}), and returns the last line's tokens.
     */
    private List<Token> lastLineOf(HttpTokenMaker tm, String... lines) {
        List<Token> lastLine = new ArrayList<>();
        int state = 0;
        for (String line : lines) {
            char[] arr = line.toCharArray();
            Token t = tm.getTokenList(new Segment(arr, 0, arr.length), state, 0);
            lastLine = new ArrayList<>();
            int left = 0;
            for (Token x = t; x != null; x = x.getNextToken()) {
                if (x.length() > 0) {
                    lastLine.add(x);
                }
                left = x.getType();
            }
            state = HttpTokenMaker.resetAfterLine(left);
        }
        return lastLine;
    }

    private void assertFirstMeaningfulTokenIs(List<Token> tokens, int expectedType) {
        for (Token t : tokens) {
            if (t.length() > 0 && t.getType() != TokenTypes.WHITESPACE) {
                assertEquals(expectedType, t.getType(), "type of [" + t + "]");
                return;
            }
        }
        fail("no token in the line");
    }
}
