package org.zaproxy.zap.extension.prettify;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.font.FontRenderContext;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rtextarea.RTextAreaUI;

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

    /**
     * Burp Suite's default HTTP message font family. Burp does not bundle a font and instead uses
     * this system monospaced font.
     */
    private static final String BURP_FONT_FAMILY = "Courier New";

    /** Burp Suite's default HTTP message font size, in points. */
    private static final int BURP_FONT_SIZE = 13;

    private static final TokenMakerFactory TOKEN_MAKER_FACTORY =
            new ContinuedStringTokenMakerFactory(new PrettifyTokenMakerFactory());

    private boolean dark;

    /** Maps display offsets (which contain injected wrap breaks) back to the original text. */
    private DisplayTextSplitter.Split split;

    /**
     * The width of one character of the font, as the text is painted (the text area paints with
     * fractional font metrics). It is 0 when the font is not monospaced.
     */
    private double advance() {
        Font font = getFont();
        if (font == null) {
            return 0;
        }
        FontMetrics metrics = getFontMetrics(font);
        int charWidth = metrics.charWidth('M');
        if (metrics.charWidth('i') != charWidth || metrics.charWidth('W') != charWidth) {
            return 0;
        }
        FontRenderContext frc = new FontRenderContext(null, true, true);
        return font.createGlyphVector(frc, "M").getGlyphPosition(1).getX();
    }

    /**
     * The offset in {@code token} of the character that is at {@code maxX} pixels from its start,
     * for a monospaced font; -1 when the font is not monospaced.
     */
    int offsetBeforeX(org.fife.ui.rsyntaxtextarea.Token token, double maxX) {
        double advance = advance();
        int length = token.length();
        if (advance <= 0 || length <= 0) {
            return -1;
        }
        int fits = (int) (maxX / advance);
        if (fits < 0) {
            fits = 0;
        }
        if (fits > length) {
            fits = length;
        }
        return token.getEndOffset() - length + fits;
    }

    /**
     * The width of a token of {@code length} characters; -1 when the font is not monospaced and
     * the width has to be measured.
     */
    float widthOf(int length) {
        double advance = advance();
        if (advance <= 0) {
            return -1;
        }
        return (float) (length * advance);
    }

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

    /**
     * Keeps the {@link DisplayTextSplitter.Split} that backs the current document, so a copy can be
     * mapped back to the untouched message.
     */
    void setSplit(DisplayTextSplitter.Split split) {
        this.split = split;
    }

    /**
     * Copying must yield the text that was received, not the display text, so the synthetic line
     * breaks inserted for wrapping are stripped: the selected display range is mapped back to the
     * original text before it reaches the clipboard.
     */
    @Override
    public String getSelectedText() {
        DisplayTextSplitter.Split activeSplit = split;
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (activeSplit == null || start >= end) {
            return super.getSelectedText();
        }
        String original = activeSplit.originalText();
        int originalStart = activeSplit.toOriginalOffset(start);
        int originalEnd = activeSplit.toOriginalOffset(end);
        if (originalStart < 0) {
            originalStart = 0;
        }
        if (originalEnd > original.length()) {
            originalEnd = original.length();
        }
        if (originalStart >= originalEnd) {
            return "";
        }
        return original.substring(originalStart, originalEnd);
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
        setFont(burpFont());
    }

    /**
     * Burp Suite's default HTTP message font, independent of ZAP's configured "work panels" font.
     * Plain style is forced because the wrapped syntax view overlaps its rows (and so garbles long
     * wrapped lines) when the text area font is bold. Falls back to the platform monospaced font
     * when {@value #BURP_FONT_FAMILY} is not installed.
     */
    private static Font burpFont() {
        Font font = new Font(BURP_FONT_FAMILY, Font.PLAIN, BURP_FONT_SIZE);
        if (!BURP_FONT_FAMILY.equalsIgnoreCase(font.getFamily())) {
            font = new Font(Font.MONOSPACED, Font.PLAIN, BURP_FONT_SIZE);
        }
        return font;
    }
}
