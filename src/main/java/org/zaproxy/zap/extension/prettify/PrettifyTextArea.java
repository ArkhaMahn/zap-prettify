package org.zaproxy.zap.extension.prettify;

import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.font.FontRenderContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JViewport;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rtextarea.RTextAreaUI;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.zaproxy.zap.utils.DisplayUtils;
import org.zaproxy.zap.view.HighlightSearchEntry;
import org.zaproxy.zap.view.HighlighterManager;

/**
 * An {@link RSyntaxTextArea} configured with the Prettify token maker factory and Burp's colour
 * scheme.
 *
 * <p>The row height is padded (see {@link #getLineHeight()}) so that a token font taller than the
 * base font (for example a bold markup tag name) cannot overlap the next wrapped row.
 *
 * <p>The area keeps the raw message backing the current document, so a copy yields the characters
 * that were received rather than the formatter's whitespace. It also participates in ZAP's {@link
 * HighlighterManager}, so user-defined highlights are painted like in the core text views.
 */
public class PrettifyTextArea extends RSyntaxTextArea {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LogManager.getLogger(PrettifyTextArea.class);

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
    private boolean initialized;

    /**
     * Whether the user asked for word wrap. It only takes effect while the view is editable: a
     * read-only view is pre-split to the viewport width, so real soft wrap would only make the
     * layout slower without changing what is shown.
     */
    private boolean configuredWrap = true;

    /** The raw message text the view is showing, used to map a copy back to the received bytes. */
    private String rawText;

    /** Notified when the Look and Feel (and therefore the colour scheme) changed. */
    private final List<Runnable> themeListeners = new CopyOnWriteArrayList<>();

    /** The font {@link #cachedAdvance} was measured for, so a font change remeasures it. */
    private Font advanceFont;

    private double cachedAdvance;

    /**
     * The width of one character of the font, as the text is painted (the text area paints with
     * fractional font metrics). It is 0 when the font is not monospaced.
     */
    private double advance() {
        Font font = getFont();
        if (font == null) {
            return 0;
        }
        if (font == advanceFont) {
            return cachedAdvance;
        }
        FontMetrics metrics = getFontMetrics(font);
        int charWidth = metrics.charWidth('M');
        if (metrics.charWidth('i') != charWidth || metrics.charWidth('W') != charWidth) {
            advanceFont = font;
            cachedAdvance = 0;
            return 0;
        }
        FontRenderContext frc = new FontRenderContext(null, true, true);
        double advance = font.createGlyphVector(frc, "M").getGlyphPosition(1).getX();
        advanceFont = font;
        cachedAdvance = advance;
        return advance;
    }

    private static final int HAS_TAB = 1;
    private static final int HAS_NON_ASCII = 2;

    /** The token whose characters {@link #analyzedFlags} describes, and the flags found in it. */
    private Token analyzedToken;

    private int analyzedFlags;

    /**
     * Notes whether {@code token} contains a tab or a character the monospaced advance cannot
     * describe. Cached because the wrap view asks for a token's width and then, when it does not
     * fit, for the offset inside the same token.
     */
    private int analyze(Token token) {
        if (token == analyzedToken) {
            return analyzedFlags;
        }
        int flags = 0;
        int length = token.length();
        for (int i = 0; i < length; i++) {
            char c = token.charAt(i);
            if (c == '\t') {
                flags |= HAS_TAB;
            } else if (c > 0x7F) {
                flags |= HAS_NON_ASCII;
            }
        }
        analyzedToken = token;
        analyzedFlags = flags;
        return flags;
    }

    /**
     * The offset in {@code token} of the character that is at {@code maxX} pixels from its start,
     * for a plain ASCII token; -1 when the font has to measure the token instead.
     */
    int offsetBeforeX(Token token, double maxX) {
        double advance = advance();
        int length = token.length();
        if (advance <= 0 || length <= 0) {
            return -1;
        }
        if (analyze(token) != 0) {
            // Tabs advance to the next tab stop and wide characters are not one advance wide, so
            // the text area has to measure them.
            return -1;
        }
        int fits = (int) (maxX / advance);
        if (fits < 0) {
            fits = 0;
        }
        if (fits > length) {
            fits = length;
        }
        return token.getOffset() + fits;
    }

    /**
     * The width of {@code token}; -1 when the font is not monospaced (or the token contains a tab
     * or a wide character) and the width has to be measured.
     */
    float widthOf(Token token) {
        double advance = advance();
        if (advance <= 0 || analyze(token) != 0) {
            return -1;
        }
        return (float) (token.length() * advance);
    }

    public PrettifyTextArea(boolean dark) {
        this.dark = dark;
        ((RSyntaxDocument) getDocument()).setTokenMakerFactory(TOKEN_MAKER_FACTORY);
        setSyntaxEditingStyle(ContentType.PLAIN_STYLE);
        applyTheme(dark);
        setEditable(false);
        // Read-only views are pre-split at the viewport width (see DisplayTextSplitter), so they
        // do not soft-wrap: laying out a wrapping document is what made scrolling slow on messages
        // of a few thousand characters. Editable views still wrap (see applyWrap()).
        applyWrap();
        setAntiAliasingEnabled(true);
        setHyperlinksEnabled(false);
        setTabsEmulated(true);
        setTabSize(TAB_SIZE);
        setFractionalFontMetricsEnabled(true);
        setHighlightCurrentLine(false);
        setFadeCurrentLineHighlight(false);
        setWhitespaceVisible(false);
        setEOLMarkersVisible(false);
        setMarkOccurrences(false);
        setBracketMatchingEnabled(false);
        setAnimateBracketMatching(false);
        setAutoIndentEnabled(false);
        setCloseCurlyBraces(false);
        setCloseMarkupTags(false);
        setClearWhitespaceLinesEnabled(false);
        setHighlightSecondaryLanguages(false);
        setUseFocusableTips(false);
        // Use ZAP's message popup menu (installed by the view) instead of RSyntaxTextArea's own
        // cut/copy/paste menu, which otherwise replaces it as soon as the text is not editable.
        setPopupMenu(null);
        initialized = true;
        initHighlighter();
    }

    /** Turns word wrap on or off as the user configured it; applied only while editable. */
    void setWordWrapConfigured(boolean wrap) {
        if (configuredWrap == wrap) {
            return;
        }
        configuredWrap = wrap;
        applyWrap();
    }

    /** Whether word wrap is configured, regardless of whether the current view is editable. */
    boolean isWordWrapConfigured() {
        return configuredWrap;
    }

    /**
     * Applies the effective wrap mode: an editable view follows the user's setting, a read-only
     * view never soft-wraps because its text is already pre-split to the viewport width.
     */
    private void applyWrap() {
        setLineWrap(isEditable() && configuredWrap);
    }

    @Override
    public void setEditable(boolean editable) {
        super.setEditable(editable);
        applyWrap();
    }

    /**
     * Keeps the raw message backing the current document, so a copy can be mapped back to the
     * untouched message.
     */
    void setMessageText(String rawText) {
        this.rawText = rawText;
    }

    /**
     * Copying yields the text that was received, not the formatter's projection: the selected
     * display range is mapped back through the non-whitespace alignment before it reaches the
     * clipboard.
     */
    @Override
    public String getSelectedText() {
        String selected = super.getSelectedText();
        String display = getText();
        if (selected == null
                || selected.isEmpty()
                || rawText == null
                || display == null
                || rawText.equals(display)) {
            return selected;
        }
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start >= end) {
            return selected;
        }
        int rawStart = TextAlignment.displayToRaw(display, rawText, start);
        int rawEnd = TextAlignment.displayToRawEnd(display, rawText, end);
        if (rawStart < 0 || rawEnd < 0 || rawStart >= rawEnd) {
            return selected;
        }
        return rawText.substring(rawStart, Math.min(rawEnd, rawText.length()));
    }

    /** Removes every highlight and paints the given range with ZAP's search highlight colour. */
    void highlightRange(int start, int end) {
        Highlighter highlighter = getHighlighter();
        if (highlighter == null) {
            return;
        }
        try {
            highlighter.removeAllHighlights();
            highlighter.addHighlight(
                    start,
                    end,
                    new DefaultHighlighter.DefaultHighlightPainter(
                            DisplayUtils.getHighlightColor()));
            setCaretPosition(start);
        } catch (BadLocationException | RuntimeException e) {
            LOGGER.debug("Could not highlight the requested range.", e);
        }
    }

    /** Paints the highlights configured in ZAP's {@link HighlighterManager}. */
    void applyManagerHighlights() {
        Highlighter highlighter = getHighlighter();
        if (highlighter != null) {
            highlighter.removeAllHighlights();
        }
        highlightAll();
    }

    private void initHighlighter() {
        HighlighterManager highlighter = HighlighterManager.getInstance();
        if (highlighter == null) {
            return;
        }
        highlighter.addHighlighterManagerListener(
                e -> {
                    switch (e.getType()) {
                        case HIGHLIGHTS_SET:
                        case HIGHLIGHT_REMOVED:
                            getHighlighter().removeAllHighlights();
                            highlightAll();
                            break;
                        case HIGHLIGHT_ADDED:
                            highlightEntry(e.getHighlight());
                            break;
                        default:
                            break;
                    }
                    repaint();
                });
    }

    private void highlightAll() {
        HighlighterManager highlighter = HighlighterManager.getInstance();
        if (highlighter == null) {
            return;
        }
        for (HighlightSearchEntry entry : highlighter.getHighlights()) {
            highlightEntry(entry);
        }
    }

    private void highlightEntry(HighlightSearchEntry entry) {
        if (entry == null
                || entry.getToken() == null
                || entry.getToken().isEmpty()
                || Boolean.FALSE.equals(entry.isActive())) {
            return;
        }
        String token = entry.getToken();
        String text = getText();
        Highlighter highlighter = getHighlighter();
        if (text == null || highlighter == null) {
            return;
        }
        DefaultHighlighter.DefaultHighlightPainter painter =
                new DefaultHighlighter.DefaultHighlightPainter(
                        entry.getColor() != null
                                ? entry.getColor()
                                : DisplayUtils.getHighlightColor());
        int at = 0;
        while ((at = text.indexOf(token, at)) >= 0) {
            try {
                highlighter.addHighlight(at, at + token.length(), painter);
            } catch (BadLocationException e) {
                LOGGER.debug("Could not paint a highlight.", e);
                return;
            }
            at += token.length();
        }
    }

    /** Adds a listener that runs after the Look and Feel colour scheme was refreshed. */
    void addThemeListener(Runnable listener) {
        if (listener != null) {
            themeListeners.add(listener);
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (!initialized) {
            return;
        }
        // super.updateUI() installs the Look and Feel defaults, which would undo the Burp colours
        // and font; reapply them even when the light/dark mode did not change.
        applyTheme(DisplayUtils.isDarkLookAndFeel());
        applyWrap();
        for (Runnable listener : themeListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOGGER.debug("Could not refresh a theme-dependent component.", e);
            }
        }
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

    // -- Persisted view configuration (mirrors the core syntax view) --------------------------

    void loadConfiguration(String key, FileConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        setAntiAliasingEnabled(
                configuration.getBoolean(key + "aa", getAntiAliasingEnabled()));
        Container parent = getParent();
        if (parent instanceof JViewport && parent.getParent() instanceof RTextScrollPane) {
            RTextScrollPane scrollPane = (RTextScrollPane) parent.getParent();
            scrollPane.setLineNumbersEnabled(
                    configuration.getBoolean(
                            key + "linenumbers", scrollPane.getLineNumbersEnabled()));
        }
        setWordWrapConfigured(
                configuration.getBoolean(key + "wordwrap", isWordWrapConfigured()));
        setHighlightCurrentLine(
                configuration.getBoolean(key + "highlightline", getHighlightCurrentLine()));
        setFadeCurrentLineHighlight(
                configuration.getBoolean(
                        key + "fadehighlightline", getFadeCurrentLineHighlight()));
        setWhitespaceVisible(
                configuration.getBoolean(key + "whitespaces", isWhitespaceVisible()));
        setEOLMarkersVisible(
                configuration.getBoolean(key + "newlines", getEOLMarkersVisible()));
        setMarkOccurrences(
                configuration.getBoolean(key + "markoccurrences", getMarkOccurrences()));
        setRoundedSelectionEdges(
                configuration.getBoolean(key + "roundedselection", getRoundedSelectionEdges()));
        setBracketMatchingEnabled(
                configuration.getBoolean(key + "bracketmatch", isBracketMatchingEnabled()));
        setAnimateBracketMatching(
                configuration.getBoolean(
                        key + "animatedbracketmatch", getAnimateBracketMatching()));
        revalidate();
        repaint();
    }

    void saveConfiguration(String key, FileConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        configuration.setProperty(key + "aa", getAntiAliasingEnabled());
        Container parent = getParent();
        if (parent instanceof JViewport && parent.getParent() instanceof RTextScrollPane) {
            RTextScrollPane scrollPane = (RTextScrollPane) parent.getParent();
            configuration.setProperty(key + "linenumbers", scrollPane.getLineNumbersEnabled());
        }
        configuration.setProperty(key + "wordwrap", isWordWrapConfigured());
        configuration.setProperty(key + "highlightline", getHighlightCurrentLine());
        configuration.setProperty(key + "fadehighlightline", getFadeCurrentLineHighlight());
        configuration.setProperty(key + "whitespaces", isWhitespaceVisible());
        configuration.setProperty(key + "newlines", getEOLMarkersVisible());
        configuration.setProperty(key + "markoccurrences", getMarkOccurrences());
        configuration.setProperty(key + "roundedselection", getRoundedSelectionEdges());
        configuration.setProperty(key + "bracketmatch", isBracketMatchingEnabled());
        configuration.setProperty(key + "animatedbracketmatch", getAnimateBracketMatching());
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
