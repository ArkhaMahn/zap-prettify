package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.plaf.TextUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Position.Bias;
import javax.swing.text.View;
import org.fife.ui.rsyntaxtextarea.SyntaxView;
import org.junit.jupiter.api.Test;

/**
 * The fast view of a pre-split, read-only document: its X span comes from the document's
 * measurement instead of a row-by-row scan on the Event Dispatch Thread, and its positions map
 * like the stock ones do.
 */
class PrettifyRowsViewTest {

    private static final String TEXT =
            "{\n"
                    + "  \"short\": 1,\n"
                    + "  \"a much longer row to make widest\": 222,\n"
                    + "  \"x\": 3\n"
                    + "}\n";

    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    private static final java.awt.Rectangle AREA = new java.awt.Rectangle(0, 0, 800, 400);

    /** Text is plain with no wrapping configured, so rows are the text's own lines. */
    private static int rows() {
        return TEXT.split("\n", -1).length;
    }

    private static FontMetrics metrics() {
        Graphics2D graphics =
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        return graphics.getFontMetrics(FONT);
    }

    private static PrettifyDocument measuredDocument() {
        FontMetrics metrics = metrics();
        return (PrettifyDocument)
                ChunkedTextLoader.buildDocument(
                        TEXT,
                        ContentType.PLAIN_STYLE,
                        DisplayTextSplitter.split(TEXT),
                        codePoint -> (double) metrics.charWidth(codePoint),
                        () -> false,
                        false);
    }

    /** The one row view of the document, as the UI builds it for the set document. */
    private static View rootViewOf(PrettifyTextArea textArea) {
        TextUI ui = textArea.getUI();
        return ui.getRootView(textArea).getView(0);
    }

    @Test
    void aMeasuredDocumentGetsTheFastViewAndAnUnmeasuredOneTheStockView() {
        PrettifyDocument document = measuredDocument();
        assertTrue(document.maxRowWidthPx() > 0f);

        PrettifyTextArea textArea = new PrettifyTextArea(false);
        textArea.setFont(FONT);
        textArea.setDocument(document);
        assertTrue(rootViewOf(textArea) instanceof PrettifyRowsView);

        // Unmeasured (no widths were given to the build): the stock view, whose own
        // measurement is exact for a document it can still measure.
        textArea.setDocument(
                ChunkedTextLoader.buildDocument(
                        TEXT,
                        ContentType.PLAIN_STYLE,
                        DisplayTextSplitter.split(TEXT),
                        () -> false,
                        false));
        assertFalse(rootViewOf(textArea) instanceof PrettifyRowsView);
    }

    @Test
    void theFastViewSpansTheMeasuredWidthWithoutTouchingEveryRow() {
        PrettifyTextArea textArea = new PrettifyTextArea(false);
        textArea.setFont(FONT);
        PrettifyDocument document = measuredDocument();
        textArea.setDocument(document);
        textArea.setSize(textArea.getPreferredSize());

        PrettifyRowsView view = (PrettifyRowsView) rootViewOf(textArea);

        assertEquals(
                document.maxRowWidthPx() + textArea.getRightHandSideCorrection(),
                view.getPreferredSpan(View.X_AXIS),
                0.5f,
                "the X span reads the measured widest row");
        assertEquals(
                rows() * (float) textArea.getLineHeight(),
                view.getPreferredSpan(View.Y_AXIS),
                0.5f,
                "the Y span is one row height per row, with no per-row scan");
    }

    @Test
    void aCaretPathGoesThroughTheRowsLikeTheStockOne() throws BadLocationException {
        PrettifyTextArea textArea = new PrettifyTextArea(false);
        textArea.setFont(FONT);
        PrettifyDocument document = measuredDocument();
        textArea.setDocument(document);
        textArea.setSize(textArea.getPreferredSize());
        View view = rootViewOf(textArea);

        Element map = document.getDefaultRootElement();
        int lineHeight = textArea.getLineHeight();
        int row = 2;
        int offset = map.getElement(row).getStartOffset();

        java.awt.Rectangle rect = view.modelToView(offset, AREA, Bias.Forward).getBounds();
        assertEquals(row * lineHeight, rect.y, "the row's Y is its index times the row height");

        Bias[] bias = new Bias[1];
        int mapped = view.viewToModel(rect.x, rect.y, AREA, bias);
        assertEquals(offset, mapped, "the row's own start maps back onto the same offset");

        // The start of the text maps to its first offset, like the stock view.
        assertEquals(0, view.viewToModel(0, 0, AREA, bias));
    }

    @Test
    void anEditedDocumentFallsBackToTheStockViewAndExactMeasurement() throws BadLocationException {
        PrettifyTextArea textArea = new PrettifyTextArea(false);
        textArea.setFont(FONT);

        // A document whose text changed before it is rendered: the stock view, built fresh,
        // whose own measurement describes its rows exactly.
        PrettifyDocument edited = measuredDocument();
        edited.insertString(0, TEXT.substring(0, 10), null);
        assertTrue(edited.isEdited());
        textArea.setDocument(edited);
        assertFalse(rootViewOf(textArea) instanceof PrettifyRowsView);

        // A fresh render of a measured document gets the fast view; if the document is edited
        // in place while the view stays installed, the same view answers with the exact width
        // the stock scan would answer: it measures once (the fallback) across all rows.
        PrettifyDocument document = measuredDocument();
        textArea.setDocument(document);
        View view = rootViewOf(textArea);
        assertTrue(view instanceof PrettifyRowsView);
        document.insertString(4, "zz", null);
        float fastSpan = view.getPreferredSpan(View.X_AXIS);

        // The stock reference: the same edited text, shown unmeasured, so the stock view runs
        // its own widest-row scan over the same rows.
        StringBuilder straggler = new StringBuilder(TEXT);
        straggler.insert(4, "zz");
        String editedText = straggler.toString();
        PrettifyTextArea stockArea = new PrettifyTextArea(false);
        stockArea.setFont(FONT);
        stockArea.setDocument(
                ChunkedTextLoader.buildDocument(
                        editedText,
                        ContentType.PLAIN_STYLE,
                        DisplayTextSplitter.split(editedText),
                        () -> false,
                        false));
        float stockSpan = rootViewOf(stockArea).getPreferredSpan(View.X_AXIS);

        assertEquals(stockSpan, fastSpan, 0.01f, "the fallback answers like the stock scan");
    }
}
