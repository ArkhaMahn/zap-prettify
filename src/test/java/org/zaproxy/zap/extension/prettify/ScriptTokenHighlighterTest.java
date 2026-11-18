package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

class ScriptTokenHighlighterTest {

    @Test
    void quotedAndUnquotedKeysBecomeVariablesAndMethodShorthandBecomesFunction() {
        String source = "{\"a\":1,b:2,m(){}}";
        Token list = tokenize(source);

        ScriptTokenHighlighter.recolor(list, new int[] {0, source.length()}, false);

        assertEquals(TokenTypes.VARIABLE, tokenAt(list, 1).getType(), "quoted key");
        assertEquals(TokenTypes.VARIABLE, tokenAt(list, 7).getType(), "unquoted key");
        assertEquals(
                TokenTypes.LITERAL_NUMBER_DECIMAL_INT,
                tokenAt(list, 5).getType(),
                "number value");
        assertEquals(
                TokenTypes.LITERAL_NUMBER_DECIMAL_INT,
                tokenAt(list, 9).getType(),
                "unquoted second value");
        assertEquals(TokenTypes.FUNCTION, tokenAt(list, 11).getType(), "method shorthand");
    }

    @Test
    void aParenthesisedValueAfterACommaIsNotAMethodShorthand() {
        String source = "{f(a),g(b){}}";
        Token list = tokenize(source);

        ScriptTokenHighlighter.recolor(list, new int[] {0, source.length()}, false);

        assertEquals(TokenTypes.IDENTIFIER, tokenAt(list, 1).getType(), "call, not a method");
        assertEquals(TokenTypes.FUNCTION, tokenAt(list, 6).getType(), "method shorthand");
    }

    @Test
    void aContinuationLineDoesNotStartInKeyPosition() {
        String source = "x:1";
        Token continuation = tokenize(source);
        ScriptTokenHighlighter.recolor(continuation, new int[] {0, source.length()}, true);
        assertEquals(
                TokenTypes.IDENTIFIER,
                tokenAt(continuation, 0).getType(),
                "a wrapped ternary branch is not a key");

        Token lineStart = tokenize(source);
        ScriptTokenHighlighter.recolor(lineStart, new int[] {0, source.length()}, false);
        assertEquals(TokenTypes.VARIABLE, tokenAt(lineStart, 0).getType());
    }

    @Test
    void tokensOutsideTheScriptRangesAreLeftAlone() {
        String source = "{\"a\":1,b:2}";
        Token list = tokenize(source);

        // Only the first key's offset is inside the range.
        ScriptTokenHighlighter.recolor(list, new int[] {1, 2}, false);

        assertEquals(TokenTypes.VARIABLE, tokenAt(list, 1).getType());
        assertEquals(TokenTypes.IDENTIFIER, tokenAt(list, 7).getType());
    }

    /**
     * Builds the linked token list RSTA hands to the view: one token per interesting character run
     * and the trailing text-less NULL token.
     */
    private static Token tokenize(String source) {
        char[] text = source.toCharArray();
        Token first = null;
        TokenImpl previous = null;
        int i = 0;
        while (i < text.length) {
            int start = i;
            int type;
            if (text[i] == '"' || text[i] == '\'') {
                char quote = text[i++];
                while (i < text.length && text[i] != quote) {
                    i++;
                }
                i++;
                type = TokenTypes.LITERAL_STRING_DOUBLE_QUOTE;
            } else if (text[i] == '{' || text[i] == '}' || text[i] == ',' || text[i] == ':'
                    || text[i] == '(' || text[i] == ')') {
                i++;
                type = TokenTypes.SEPARATOR;
            } else if (text[i] >= '0' && text[i] <= '9') {
                while (i < text.length && text[i] >= '0' && text[i] <= '9') {
                    i++;
                }
                type = TokenTypes.LITERAL_NUMBER_DECIMAL_INT;
            } else {
                while (i < text.length && Character.isLetter(text[i])) {
                    i++;
                }
                type = TokenTypes.IDENTIFIER;
            }
            TokenImpl token = new TokenImpl(text, start, i - 1, start, type, 0);
            if (first == null) {
                first = token;
            } else {
                previous.setNextToken(token);
            }
            previous = token;
        }
        Token end =
                new TokenImpl(
                        text,
                        text.length,
                        text.length - 1,
                        text.length,
                        TokenTypes.NULL,
                        0);
        if (first == null) {
            return end;
        }
        previous.setNextToken(end);
        return first;
    }

    private static Token tokenAt(Token list, int offset) {
        for (Token t = list; t != null && t.getNextToken() != null; t = t.getNextToken()) {
            if (t.getOffset() == offset) {
                return t;
            }
        }
        throw new AssertionError("No token at offset " + offset);
    }
}
