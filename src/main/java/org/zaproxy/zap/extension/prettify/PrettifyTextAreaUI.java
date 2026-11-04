package org.zaproxy.zap.extension.prettify;

import javax.swing.JComponent;
import javax.swing.text.Element;
import javax.swing.text.View;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaUI;

/**
 * The RSyntaxTextArea UI for {@link PrettifyTextArea}. Identical to {@link RSyntaxTextAreaUI}
 * except for the views: wrapped lines use {@link PrettifyWrappedSyntaxView}, which breaks at
 * character boundaries, and pre-split documents use {@link PrettifyRowsView}, whose width comes
 * measured from the render pipeline instead of being scanned row by row on the EDT.
 */
public class PrettifyTextAreaUI extends RSyntaxTextAreaUI {

    public PrettifyTextAreaUI(JComponent textArea) {
        super(textArea);
    }

    @Override
    public View create(Element elem) {
        if (elem.getDocument() instanceof PrettifyDocument) {
            PrettifyDocument document = (PrettifyDocument) elem.getDocument();
            if (!document.isEdited() && document.maxRowWidthPx() >= 0f) {
                // The rows are the pre-split display rows, and the widest one is already known:
                // the stock width scan over every row never runs.
                return new PrettifyRowsView(elem);
            }
        }
        if (getRTextArea() instanceof RSyntaxTextArea
                && ((RSyntaxTextArea) getRTextArea()).getLineWrap()) {
            return new PrettifyWrappedSyntaxView(elem);
        }
        return super.create(elem);
    }
}
