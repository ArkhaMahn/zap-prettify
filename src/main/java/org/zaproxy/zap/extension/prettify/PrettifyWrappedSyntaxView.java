package org.zaproxy.zap.extension.prettify;

import javax.swing.text.Element;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.WrappedSyntaxView;

/**
 * A word-wrapping view with Burp-style line breaks.
 *
 * <p>RSyntaxTextArea's stock {@code WrappedSyntaxView.calculateBreakPosition} breaks only at token
 * boundaries: when a token does not fit and is not the first one on the row, it moves the whole
 * token to the next row. Because a header value is emitted as a single token, a long value jumps
 * to the next line (leaving the header name stranded with empty space after it). Burp instead fills
 * the row and breaks the value mid-token.
 *
 * <p>This view fills the row with as much of the too-wide token as fits, so long values, URLs and
 * other unbroken runs wrap at the character boundary.
 */
public class PrettifyWrappedSyntaxView extends WrappedSyntaxView {

    public PrettifyWrappedSyntaxView(Element elem) {
        super(elem);
    }

    @Override
    protected int calculateBreakPosition(int p0, Token tokenList, float x0) {
        RSyntaxTextArea textArea = (RSyntaxTextArea) getContainer();
        float currentWidth = getWidth();
        if (currentWidth == Integer.MAX_VALUE) {
            currentWidth = getPreferredSpan(0);
        }
        currentWidth = Math.max(currentWidth, 20.0f);
        int p = p0;
        for (Token t = tokenList; t != null && t.isPaintable(); t = t.getNextToken()) {
            float tokenWidth = t.getWidth(textArea, this, x0);
            if (tokenWidth > currentWidth) {
                // Fill the row with as much of the token as fits instead of moving it wholesale.
                return t.getOffsetBeforeX(textArea, this, 0.0f, currentWidth);
            }
            currentWidth -= tokenWidth;
            x0 += tokenWidth;
            p += t.length();
        }
        return p + 1;
    }
}
