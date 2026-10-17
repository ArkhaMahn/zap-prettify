package org.zaproxy.zap.extension.prettify;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/** Token maker for {@code application/x-www-form-urlencoded} (and multipart) bodies. */
public class FormUrlEncodedTokenMaker extends AbstractTokenMaker {

    public FormUrlEncodedTokenMaker() {}

    @Override
    public TokenMap getWordsToHighlight() {
        return null;
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        char[] a = text.array;
        int off = text.offset;
        int end = off + text.count;
        int p = off;
        boolean expectValue = false;

        while (p < end) {
            if (!expectValue) {
                int eq = indexOf(a, '=', p, end);
                int amp = indexOf(a, '&', p, end);
                int stop = end;
                int kind = 0;
                if (eq >= 0 && (amp < 0 || eq < amp)) {
                    stop = eq;
                    kind = 1;
                } else if (amp >= 0) {
                    stop = amp;
                    kind = 2;
                }
                if (stop > p) {
                    addToken(
                            a,
                            p,
                            stop - 1,
                            BurpTokenTypes.HTTP_PARAMETER_NAME,
                            startOffset + (p - off));
                }
                if (kind == 1) {
                    addToken(a, eq, eq, TokenTypes.OPERATOR, startOffset + (eq - off));
                    p = eq + 1;
                    expectValue = true;
                } else if (kind == 2) {
                    addToken(a, amp, amp, TokenTypes.SEPARATOR, startOffset + (amp - off));
                    p = amp + 1;
                } else {
                    p = end;
                }
            } else {
                int amp = indexOf(a, '&', p, end);
                int stop = (amp >= 0) ? amp : end;
                if (stop > p) {
                    addToken(
                            a,
                            p,
                            stop - 1,
                            BurpTokenTypes.HTTP_PARAMETER_VALUE,
                            startOffset + (p - off));
                }
                if (amp >= 0) {
                    addToken(a, amp, amp, TokenTypes.SEPARATOR, startOffset + (amp - off));
                    p = amp + 1;
                } else {
                    p = end;
                }
                expectValue = false;
            }
        }

        addNullToken();
        return firstToken;
    }

    private static int indexOf(char[] a, char c, int start, int end) {
        for (int p = start; p < end; p++) {
            if (a[p] == c) {
                return p;
            }
        }
        return -1;
    }
}
