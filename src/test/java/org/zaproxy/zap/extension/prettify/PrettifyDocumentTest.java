package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

class PrettifyDocumentTest {

    private static PrettifyDocument editableDocument(String message) throws Exception {
        return editableDocument(message, new PrettifyTokenMakerFactory());
    }

    private static PrettifyDocument editableDocument(
            String message, org.fife.ui.rsyntaxtextarea.TokenMakerFactory factory)
            throws Exception {
        DisplayTextSplitter.Split split =
                new DisplayTextSplitter.Split(message, message, new int[0]);
        PrettifyDocument document =
                new PrettifyDocument(
                        factory,
                        PrettifyTokenMakerFactory.STYLE_HTTP_REQUEST,
                        split,
                        false);
        document.insertString(0, message, null);
        document.watchForEdits();
        document.precomputeLineStates();
        return document;
    }

    private static String textOf(Token tokenList) {
        StringBuilder text = new StringBuilder();
        for (Token token = tokenList; token != null; token = token.getNextToken()) {
            if (token.getType() >= 0 && token.length() > 0) {
                text.append(token.getLexeme());
            }
        }
        return text.toString();
    }

    private static boolean hasTokenType(Token tokenList, int type) {
        for (Token token = tokenList; token != null; token = token.getNextToken()) {
            if (token.getType() == type) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anEditedLineIsRepaintedFromTheDocument() throws Exception {
        String message = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\nbody";
        PrettifyDocument document = editableDocument(message);

        int bodyLine = 3;
        assertEquals("body", textOf(document.getTokenListForLine(bodyLine)));

        int offset = message.indexOf("body");
        document.remove(offset, 4);
        document.insertString(offset, "replacement", null);

        String actual = textOf(document.getTokenListForLine(bodyLine));
        assertEquals("replacement", actual, "actual=" + actual);
    }

    @Test
    void contentTypeComesFromAHeaderLineNotAnotherHeaderValue() throws Exception {
        String message =
                "GET / HTTP/1.1\r\nX-Note: content-type: text/html\r\nContent-Type: application/xml\r\n\r\nbody";
        PrettifyDocument document =
                new PrettifyDocument(
                        new PrettifyTokenMakerFactory(),
                        PrettifyTokenMakerFactory.STYLE_HTTP_REQUEST,
                        new DisplayTextSplitter.Split(message, message, new int[0]),
                        false);
        document.insertString(0, message, null);

        assertEquals(
                "application/xml",
                document.bodyContentType(),
                "the Content-Type comes from its own header line, not from another header's value");
    }

    @Test
    void changingTheContentTypeChangesTheBodyDelegate() throws Exception {
        String message =
                "POST / HTTP/1.1\r\n"
                        + "Content-Type: application/xml\r\n"
                        + "\r\n"
                        + "{\"a\":1}";
        PrettifyDocument document = editableDocument(message);

        int bodyLine = 3;
        Token xml = document.getTokenListForLine(bodyLine);
        assertTrue(
                !hasTokenType(xml, TokenTypes.VARIABLE),
                "an XML body is not coloured as a JSON object");

        int offset = message.indexOf("application/xml");
        document.remove(offset, "application/xml".length());
        document.insertString(offset, "application/json", null);

        Token json = document.getTokenListForLine(bodyLine);
        assertTrue(
                hasTokenType(json, TokenTypes.VARIABLE),
                "the edited content type re-resolves the body token maker; got="
                        + textOf(json));
    }

    @Test
    void aBodyEditKeepsTheResolvedBodyDelegate() throws Exception {
        // Declared as JavaScript, but the body would sniff as plain text if the delegate were
        // dropped and re-resolved from the edited line's own text. A body edit must keep the
        // declared delegate, so the key stays a string.
        String message =
                "POST / HTTP/1.1\r\n"
                        + "Content-Type: text/javascript\r\n"
                        + "\r\n"
                        + "head\r\n"
                        + "{\"a\":1}";
        PrettifyDocument document = editableDocument(message);

        int jsonLine = 4;
        assertTrue(
                hasTokenType(
                        document.getTokenListForLine(jsonLine),
                        TokenTypes.LITERAL_STRING_DOUBLE_QUOTE),
                "the declared JavaScript delegate colours the key as a string");

        // Editing the first body line makes the states from that line on be recomputed, which is
        // where a wrongly cleared delegate would be re-resolved.
        int offset = message.indexOf("head");
        document.insertString(offset, "X", null);

        Token afterEdit = document.getTokenListForLine(jsonLine);
        assertTrue(
                hasTokenType(afterEdit, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE),
                "a body edit keeps the resolved delegate; got=" + textOf(afterEdit));
    }

    @Test
    void aBodyEditOnlyRecomputesStatesFromTheEditedLineOn() throws Exception {
        // The header block carries the Content-Type, so only a header edit may rebuild it from the
        // top. Rebuilding it for every body keystroke would make editing a large message walk the
        // whole header block on the Event Dispatch Thread.
        String message = messageWithManyHeaders();
        PrettifyDocument document = editableDocument(message, new CountingFactory());

        int lastLine = lineCount(message) - 1;
        document.getTokenListForLine(lastLine);

        CountingFactory.TOKENIZATIONS.set(0);
        int offset = message.indexOf("b0") + 1;
        document.insertString(offset, "X", null);
        CountingFactory.TOKENIZATIONS.set(0);
        document.getTokenListForLine(lastLine);

        int recomputed = CountingFactory.TOKENIZATIONS.get();
        assertTrue(
                recomputed <= 10,
                "a body edit recomputes only from the edited line, not the whole message; got "
                        + recomputed
                        + " tokenizations");
    }

    @Test
    void aHeaderEditRecomputesStatesFromTheTop() throws Exception {
        String message = messageWithManyHeaders();
        PrettifyDocument document = editableDocument(message, new CountingFactory());

        int lastLine = lineCount(message) - 1;
        document.getTokenListForLine(lastLine);

        CountingFactory.TOKENIZATIONS.set(0);
        int offset = message.indexOf("H20:");
        document.insertString(offset, "X", null);
        document.getTokenListForLine(lastLine);

        int recomputed = CountingFactory.TOKENIZATIONS.get();
        assertTrue(
                recomputed >= 40,
                "a header edit rebuilds the header states from the top; got " + recomputed
                        + " tokenizations");
    }

    /** A request with 40 header lines and 3 body lines. */
    private static String messageWithManyHeaders() {
        StringBuilder message = new StringBuilder("GET / HTTP/1.1\r\n");
        for (int i = 1; i <= 40; i++) {
            message.append("H").append(i).append(": v\r\n");
        }
        return message.append("\r\nb0\r\nb1\r\nb2").toString();
    }

    private static int lineCount(String message) {
        return TextAlignment.countLines(message.replace("\r\n", "\n"));
    }

    private static final class CountingFactory extends PrettifyTokenMakerFactory {
        static final java.util.concurrent.atomic.AtomicInteger TOKENIZATIONS =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        protected org.fife.ui.rsyntaxtextarea.TokenMaker getTokenMakerImpl(String key) {
            if (STYLE_HTTP_REQUEST.equals(key)) {
                return new CountingTokenMaker();
            }
            return super.getTokenMakerImpl(key);
        }
    }

    private static final class CountingTokenMaker extends HttpRequestTokenMaker {
        @Override
        public Token getTokenList(
                javax.swing.text.Segment text, int initialTokenType, int startOffset) {
            CountingFactory.TOKENIZATIONS.incrementAndGet();
            return super.getTokenList(text, initialTokenType, startOffset);
        }
    }
}
