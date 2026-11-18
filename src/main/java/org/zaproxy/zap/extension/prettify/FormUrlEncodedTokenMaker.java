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
                // Find the next '=' or '&' in one pass. Scanning separately for each made a
                // parameter list without any value ("a&b&c&...") quadratic: every '&' segment
                // rescanned the whole rest of the line looking for a '=' that was not there.
                int q = p;
                while (q < end && a[q] != '=' && a[q] != '&') {
                    q++;
                }
                int stop = q;
                int kind = q == end ? 0 : (a[q] == '=' ? 1 : 2);
                if (stop > p) {
                    addToken(
                            a,
                            p,
                            stop - 1,
                            PrettifyTokenTypes.HTTP_PARAMETER_NAME,
                            startOffset + (p - off));
                }
                if (kind == 1) {
                    addToken(a, q, q, TokenTypes.OPERATOR, startOffset + (q - off));
                    p = q + 1;
                    expectValue = true;
                } else if (kind == 2) {
                    addToken(a, q, q, TokenTypes.SEPARATOR, startOffset + (q - off));
                    p = q + 1;
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
                            PrettifyTokenTypes.HTTP_PARAMETER_VALUE,
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
