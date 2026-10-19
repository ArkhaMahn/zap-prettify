package org.zaproxy.zap.extension.prettify;

import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * An {@link RSyntaxDocument} whose line states are computed with knowledge of the breaks inserted
 * by {@link DisplayTextSplitter}.
 *
 * <p>RSTA carries a tokenizer state from one physical line to the next and assumes every line is a
 * self-contained logical line. Our splitter violates that assumption: a wrapping row that continues
 * an overlong line must resume the HTTP construct (a header value, a cookie, a request target, ...)
 * rather than be re-read as a new header line. This document supplies that knowledge by:
 *
 * <ul>
 *   <li>letting the token maker carry the in-construct state between continuation rows, and
 *   <li>resetting that state to a fresh header line at every real (non-continuation) line boundary.
 * </ul>
 *
 * <p>Only HTTP styles need this; every other style is delegated to the base class, whose
 * {@code ContinuedStringTokenMaker} already handles multi-line strings.
 */
public class PrettifyDocument extends RSyntaxDocument {

    private final boolean http;
    private final int[] continuationStarts;
    private final TokenMaker stateTokenMaker;
    private final Segment segment = new Segment();

    private int[] lineState;
    private int computedUpTo = -1;

    private Token cachedTokenList;
    private int cachedTokenLine = -1;

    public PrettifyDocument(
            TokenMakerFactory tokenMakerFactory,
            String styleKey,
            DisplayTextSplitter.Split split) {
        super(tokenMakerFactory, styleKey);
        this.http = PrettifyTokenMakerFactory.isHttpStyle(styleKey);
        this.stateTokenMaker = http ? tokenMakerFactory.getTokenMaker(styleKey) : null;
        this.continuationStarts = continuationStarts(split);
        this.lineState = new int[Math.max(16, lineCount())];
    }

    private static int[] continuationStarts(DisplayTextSplitter.Split split) {
        if (split == null) {
            return new int[0];
        }
        int[] breaks = split.breakOffsets();
        int[] starts = new int[breaks.length];
        for (int i = 0; i < breaks.length; i++) {
            starts[i] = breaks[i] + 1;
        }
        return starts;
    }

    @Override
    public Token getTokenListForLine(int line) {
        if (!http) {
            return super.getTokenListForLine(line);
        }
        if (line == cachedTokenLine && cachedTokenList != null) {
            return cachedTokenList;
        }
        Element root = getDefaultRootElement();
        if (line < 0 || line >= root.getElementCount()) {
            return super.getTokenListForLine(line);
        }
        Element lineElement = root.getElement(line);
        int start = lineElement.getStartOffset();
        int length = lineElement.getEndOffset() - 1 - start;
        if (length < 0) {
            length = 0;
        }
        int initialTokenType = line == 0 ? 0 : getLastTokenTypeOnLine(line - 1);
        try {
            getText(start, length, segment);
        } catch (BadLocationException e) {
            return super.getTokenListForLine(line);
        }
        // Painting goes through our own state-aware token maker so that the header state it built
        // during precomputeLineStates() (including the body Content-Type) is the one used here.
        cachedTokenLine = line;
        cachedTokenList = stateTokenMaker.getTokenList(segment, initialTokenType, start);
        return cachedTokenList;
    }

    @Override
    public int getLastTokenTypeOnLine(int line) {
        if (!http) {
            return super.getLastTokenTypeOnLine(line);
        }
        if (line < 0) {
            return 0;
        }
        advanceTo(line);
        return line < lineState.length ? lineState[line] : 0;
    }

    /**
     * Computes the carried state for every line. Called once, off the Event Dispatch Thread, right
     * after the text has been inserted, so that painting never has to tokenize from the top of a
     * large document.
     */
    public void precomputeLineStates() {
        if (http) {
            advanceTo(lineCount() - 1);
        }
    }

    private int lineCount() {
        return getDefaultRootElement().getElementCount();
    }

    private void advanceTo(int line) {
        int count = lineCount();
        if (line >= count) {
            line = count - 1;
        }
        if (lineState.length < count) {
            int[] grown = new int[count];
            System.arraycopy(lineState, 0, grown, 0, lineState.length);
            lineState = grown;
        }
        Element root = getDefaultRootElement();
        for (int i = computedUpTo + 1; i <= line; i++) {
            int init = (i == 0) ? 0 : lineState[i - 1];
            int construct = constructStateAtEnd(root.getElement(i), init);
            lineState[i] =
                    isContinuation(i + 1)
                            ? construct
                            : HttpTokenMaker.resetAfterLine(construct);
        }
        if (line > computedUpTo) {
            computedUpTo = line;
        }
    }

    private int constructStateAtEnd(Element lineElement, int initialTokenType) {
        int start = lineElement.getStartOffset();
        int length = lineElement.getEndOffset() - 1 - start;
        if (length < 0) {
            length = 0;
        }
        try {
            getText(start, length, segment);
        } catch (BadLocationException e) {
            return initialTokenType;
        }
        return stateTokenMaker.getLastTokenTypeOnLine(segment, initialTokenType);
    }

    private boolean isContinuation(int line) {
        if (line <= 0 || line >= lineCount()) {
            return false;
        }
        int start = getDefaultRootElement().getElement(line).getStartOffset();
        return contains(continuationStarts, start);
    }

    private static boolean contains(int[] sorted, int value) {
        int lo = 0;
        int hi = sorted.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = sorted[mid];
            if (v == value) {
                return true;
            }
            if (v < value) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return false;
    }
}
