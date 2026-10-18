package org.zaproxy.zap.extension.prettify;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rtextarea.RTextAreaUI;
import org.zaproxy.zap.utils.FontUtils;

/**
 * An {@link RSyntaxTextArea} configured with the Prettify token maker factory and Burp's colour
 * scheme.
 *
 * <p>The row height is padded (see {@link #getLineHeight()}) so that a token font taller than the
 * base font (for example a bold markup tag name) cannot overlap the next wrapped row.
 */
public class PrettifyTextArea extends RSyntaxTextArea {

    private static final long serialVersionUID = 1L;

    /**
     * Spaces per tab. Applied to the text area and re-applied whenever a freshly built document is
     * swapped in, because {@link RSyntaxDocument} resets its {@code tabSize} property to 5.
     */
    public static final int TAB_SIZE = 2;

    /**
     * Extra vertical space added to every row, as a fraction of the line height, so that wrapped
     * rows never overlap when a token is painted with a taller font. Mirrors the Pretty View
     * add-on.
     */
    private static final float EXTRA_LINE_HEIGHT_RATIO = 0.35f;

    private static final int MIN_EXTRA_LINE_HEIGHT = 2;

    private static final TokenMakerFactory TOKEN_MAKER_FACTORY =
            new ContinuedStringTokenMakerFactory(new PrettifyTokenMakerFactory());

    private boolean dark;

    public PrettifyTextArea(boolean dark) {
        this.dark = dark;
        ((RSyntaxDocument) getDocument()).setTokenMakerFactory(TOKEN_MAKER_FACTORY);
        setSyntaxEditingStyle(ContentType.PLAIN_STYLE);
        applyTheme(dark);
        setEditable(false);
        // Wrapping is done by the view itself (column-aligned breaks in the text), not by the text
        // area, so the gutter can number only real message lines. Disable the built-in soft wrap.
        setLineWrap(false);
        setAntiAliasingEnabled(true);
        setTabsEmulated(true);
        setTabSize(TAB_SIZE);
        setFractionalFontMetricsEnabled(true);
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
        setHighlightSecondaryLanguages(false);
        setUseFocusableTips(false);
    }

    @Override
    public int getLineHeight() {
        int lineHeight = super.getLineHeight();
        return lineHeight
                + Math.max(
                        MIN_EXTRA_LINE_HEIGHT,
                        Math.round(lineHeight * EXTRA_LINE_HEIGHT_RATIO));
    }

    @Override
    protected RTextAreaUI createRTextAreaUI() {
        return new PrettifyTextAreaUI(this);
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
        // Derive the plain style: the wrapped syntax view overlaps its rows (and so
        // garbles long wrapped lines) when the text area font is bold.
        setFont(
                FontUtils.getFontWithFallback(
                                FontUtils.FontType.workPanels, getFont().getFontName())
                        .deriveFont(java.awt.Font.PLAIN));
    }
}
