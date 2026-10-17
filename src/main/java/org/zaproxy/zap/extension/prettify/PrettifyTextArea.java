package org.zaproxy.zap.extension.prettify;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.zaproxy.zap.utils.FontUtils;

/**
 * An {@link RSyntaxTextArea} configured with the Prettify token maker factory and Burp's colour
 * scheme.
 */
public class PrettifyTextArea extends RSyntaxTextArea {

    private static final long serialVersionUID = 1L;

    private static final PrettifyTokenMakerFactory TOKEN_MAKER_FACTORY = new PrettifyTokenMakerFactory();

    private boolean dark;

    public PrettifyTextArea(boolean dark) {
        this.dark = dark;
        ((RSyntaxDocument) getDocument()).setTokenMakerFactory(TOKEN_MAKER_FACTORY);
        setSyntaxEditingStyle(ContentType.PLAIN_STYLE);
        applyTheme(dark);
        setEditable(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setAntiAliasingEnabled(true);
        setHighlightCurrentLine(false);
        setFadeCurrentLineHighlight(false);
        setWhitespaceVisible(false);
        setEOLMarkersVisible(false);
        setMarkOccurrences(false);
        setBracketMatchingEnabled(false);
        setAutoIndentEnabled(false);
        setCloseCurlyBraces(false);
        setCloseMarkupTags(false);
        setClearWhitespaceLinesEnabled(false);
    }

    public boolean isDark() {
        return dark;
    }

    public void applyTheme(boolean dark) {
        this.dark = dark;
        BurpTheme theme = dark ? BurpTheme.DARK : BurpTheme.LIGHT;
        setBackground(theme.getBackground());
        setForeground(theme.getText());
        setCaretColor(theme.getText());
        setSelectionColor(theme.getSelectionBackground());
        setSelectedTextColor(theme.getSelectionForeground());
        setSyntaxScheme(new BurpSyntaxScheme(theme));
        setFont(
                FontUtils.getFontWithFallback(
                        FontUtils.FontType.workPanels, getFont().getFontName()));
    }
}
