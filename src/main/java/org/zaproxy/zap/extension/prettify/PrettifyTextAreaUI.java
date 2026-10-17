package org.zaproxy.zap.extension.prettify;

import javax.swing.JComponent;
import javax.swing.text.Element;
import javax.swing.text.View;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaUI;
import org.fife.ui.rsyntaxtextarea.SyntaxView;

/**
 * The RSyntaxTextArea UI for {@link PrettifyTextArea}. Identical to {@link RSyntaxTextAreaUI}
 * except that wrapped lines use {@link PrettifyWrappedSyntaxView}, which breaks at character
 * boundaries.
 */
public class PrettifyTextAreaUI extends RSyntaxTextAreaUI {

    public PrettifyTextAreaUI(JComponent textArea) {
        super(textArea);
    }

    @Override
    public View create(Element elem) {
        if (getRTextArea() instanceof RSyntaxTextArea
                && ((RSyntaxTextArea) getRTextArea()).getLineWrap()) {
            return new PrettifyWrappedSyntaxView(elem);
        }
        return new SyntaxView(elem);
    }
}
