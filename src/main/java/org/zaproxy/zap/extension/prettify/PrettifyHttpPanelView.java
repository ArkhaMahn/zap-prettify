package org.zaproxy.zap.extension.prettify;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.font.FontRenderContext;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.parosproxy.paros.view.View;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.AbstractStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelEvent;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelListener;
import org.zaproxy.zap.extension.search.SearchMatch;
import org.zaproxy.zap.extension.search.SearchableHttpPanelView;
import org.zaproxy.zap.utils.DisplayUtils;
import org.zaproxy.zap.view.messagecontainer.http.DefaultSingleHttpMessageContainer;

/**
 * An {@link HttpPanelView} that shows an HTTP message (or one of its parts) as a
 * syntax-highlighted, pre-wrapped text area.
 *
 * <p>The raw message is the source of truth. The pretty printer produces a whitespace-only
 * projection for display, and the display text is pre-broken at the viewport width by {@link
 * DisplayTextSplitter}. Pre-breaking keeps the text area on its fast, non-wrapping layout path:
 * real soft wrap makes RSyntaxTextArea re-measure and re-layout every row while painting and
 * scrolling, which is visibly slow even for messages of a few thousand characters.
 *
 * <p>Search, highlighting, copying and saving map between the display and the raw message through
 * {@link TextAlignment}. An untouched view therefore never rewrites the message, and an edit only
 * changes the characters the user actually typed.
 *
 * <p>Formatting, splitting and document construction happen off the Event Dispatch Thread, so a
 * large message never blocks the panel while it is prepared.
 */
public class PrettifyHttpPanelView
        implements HttpPanelView, HttpPanelViewModelListener, SearchableHttpPanelView {

    private static final Logger LOGGER = LogManager.getLogger(PrettifyHttpPanelView.class);

    /** Name used by ZAP's message menu container. */
    private static final String MESSAGE_CONTAINER_NAME = "HttpMessagePanel";

    /** Which part of an HTTP message this view shows. */
    public enum Target {
        REQUEST_HEAD,
        REQUEST_BODY,
        RESPONSE_HEAD,
        RESPONSE_BODY,
        REQUEST_ALL,
        RESPONSE_ALL
    }

    private final String name;
    private final String captionKey;
    private final String fixedStyleKey;
    private final Target target;
    private final AbstractStringHttpPanelViewModel model;
    private final JPanel mainPanel;
    private final PrettifyTextArea textArea;
    private final RTextScrollPane scrollPane;

    /** Incremented on every request; stale background results are dropped against it. */
    private volatile long generation;

    /** Whether the panel this view is mounted in allows the message to be edited. */
    private volatile boolean editable;

    /** Whether the user changed the displayed text since it was loaded or last saved. */
    private volatile boolean changed;

    /** The raw message text the current document was built from. */
    private volatile String rawText = "";

    /** The exact formatted text installed in the document, including the wrap breaks. */
    private volatile String displaySource = "";

    /** Wrap width (in pixels) the current document was split for; -1 means "unknown". */
    private volatile int wrappedWidthPx = -1;

    /**
     * Re-splits spent on correcting the wrap width after a document was laid out. A layout can hand
     * the view less room than the viewport extent suggests (the viewport is sized before the gutter
     * and the scrollbar settle), which would leave the rows short of the right edge. Bounded so a
     * scrollbar appearing/disappearing cannot make the two widths trade places forever.
     */
    private int widthCorrections;

    /** Width of one digit in the gutter font, or -1 until measured. */
    private int gutterDigitWidth = -1;

    /**
     * Gutter width minus the digits it reserves ({@code digitWidth * (digits + 1)}), measured from
     * a laid-out gutter; -1 until measured. The rest (borders, padding) is constant for a given
     * Look and Feel, so the width for another line count can be predicted before the new document
     * is installed.
     */
    private int gutterFixedWidth = -1;

    /** Line-number digits of the installed document, used to calibrate {@link #gutterFixedWidth}. */
    private int installedDigits = -1;

    /** The message the installed document was built from, to tell a new message from a change. */
    private Message shownMessage;

    /** Where the caret should be restored after a re-render, counted in non-whitespace characters. */
    private int pendingCaretCount = -1;

    /** Scrollbar position to restore after a re-render; -1 when the view should stay at the top. */
    private int pendingScrollValue = -1;

    /** Guards against re-entering {@link #dataChanged} while a save is being applied. */
    private boolean saving;

    /** Guards against re-entering {@link #dataChanged} while ZAP's body fix-up is applied. */
    private boolean adjustingBody;

    /**
     * The request body the view last showed. ZAP moves the body into the query string when the
     * method changes away from POST and moves it back when it changes to POST; on the way back a
     * parameter that has no value is re-encoded as {@code name=}, so the body comes back with a
     * "=" it never had. The body is remembered so that this can be undone.
     */
    private String lastShownBody = "";

    /** The request method the view last showed, to notice a change of the verb. */
    private String lastShownMethod = "";

    private String configurationKey = "";

    /** The per-code-point widths of the last font, so a resize does not re-measure the font. */
    private Font charWidthsFont;

    private DisplayTextSplitter.CharWidths charWidths;

    public PrettifyHttpPanelView(
            String name,
            String captionKey,
            String fixedStyleKey,
            Target target,
            AbstractStringHttpPanelViewModel model) {
        this.name = name;
        this.captionKey = captionKey;
        this.fixedStyleKey = fixedStyleKey;
        this.target = target;
        this.model = model;

        this.mainPanel = new JPanel(new BorderLayout());
        this.textArea = new PrettifyTextArea(DisplayUtils.isDarkLookAndFeel());
        this.textArea.setPasteFormatter(this::formatPastedText);
        this.scrollPane = new RTextScrollPane(textArea, true);
        applyGutterTheme();
        textArea.addThemeListener(this::applyGutterTheme);
        // The text is pre-wrapped to fit the viewport, so a horizontal scrollbar could never reveal
        // anything: its thumb would always span the full track. Disable it so the wrap appears
        // contained instead of showing a redundant bar.
        this.scrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        this.mainPanel.add(scrollPane, BorderLayout.CENTER);
        textArea.setComponentPopupMenu(new CustomPopupMenu());
        textArea.addComponentListener(
                new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent e) {
                        resplitIfWidthChanged();
                    }

                    @Override
                    public void componentShown(ComponentEvent e) {
                        resplitIfWidthChanged();
                    }
                });
        // The text area does not resize when the vertical scrollbar appears/disappears (its width
        // follows the content), but the viewport does. Watch the viewport so the wrap width tracks
        // the real drawable width.
        this.scrollPane
                .getViewport()
                .addComponentListener(
                        new ComponentAdapter() {
                            @Override
                            public void componentResized(ComponentEvent e) {
                                resplitIfWidthChanged();
                            }
                        });

        this.model.addHttpPanelViewModelListener(this);
        update();
    }

    /** Dims the gutter so the line numbers and their separator do not compete with the message. */
    private void applyGutterTheme() {
        Gutter gutter = scrollPane.getGutter();
        if (gutter == null) {
            return;
        }
        PrettifyTheme theme = textArea.isDark() ? PrettifyTheme.DARK : PrettifyTheme.LIGHT;
        gutter.setLineNumberColor(theme.getLineNumbers());
        gutter.setBorderColor(theme.getLineNumbers());
    }

    /** Re-renders the current message, starting at the top. */
    private void update() {
        pendingCaretCount = -1;
        pendingScrollValue = -1;
        widthCorrections = 0;
        updateInternal();
    }

    /** Re-renders the current message and restores the caret and scroll position afterwards. */
    private void resplit() {
        // A resize drag can ask for many splits before one finishes. Capture the position once
        // and let the following splits reuse it, so a drag does not scan the whole document per
        // pixel.
        if (pendingCaretCount < 0) {
            capturePosition();
        }
        updateInternal();
    }

    /** Remembers the caret and scroll position in the text that is about to be replaced. */
    private void capturePosition() {
        pendingCaretCount =
                TextAlignment.nonWhitespaceBefore(textArea.getText(), textArea.getCaretPosition());
        pendingScrollValue = scrollPane.getVerticalScrollBar().getValue();
    }

    private void updateInternal() {
        Message message = model.getMessage();
        String data = model.getData();
        if (data == null) {
            data = "";
        }
        final String raw = data;
        final String contentType = contentTypeOf(message);
        final boolean stripTrailing = shouldStripTrailingNewlines(message);
        // The gutter keeps the original message line numbers, so it aligns with the text before
        // the pretty printer reflows it.
        final String numbering = stripTrailing ? stripTrailingNewlines(raw) : raw;
        // Prefer the width predicted for the gutter this message will need: the currently
        // laid-out text area still reserves the previous message's gutter, which can be a digit
        // narrower or wider.
        final int predictedWidthPx = predictedWrapWidthPx();
        final int widthPx = predictedWidthPx > 0 ? predictedWidthPx : currentWrapWidthPx();
        final long gen = ++generation;
        wrappedWidthPx = widthPx;
        final DisplayTextSplitter.CharWidths widths = charWidths();

        PrettifyExecutor.get()
                .submit(
                        () -> {
                            // A resize can queue several splits. A superseded one returns before
                            // doing any work, and the checks between the expensive steps stop one
                            // that is already running as soon as a newer split arrives.
                            if (gen != generation) {
                                return;
                            }
                            String display = projection(raw, contentType, stripTrailing);
                            if (gen != generation) {
                                return;
                            }
                            String style = styleFor(contentType, display);
                            DisplayTextSplitter.Split split =
                                    editable
                                            ? new DisplayTextSplitter.Split(
                                                    display, display, new int[0], numbering)
                                            : DisplayTextSplitter.split(
                                                    display, numbering, style, widthPx, widths);
                            if (gen != generation) {
                                return;
                            }
                            // An editable view numbers its rows from the text as it is typed (see
                            // updateEditableNumbering), so it does not need this full walk.
                            int[] lineNumbers =
                                    editable
                                            ? null
                                            : LineNumbers.forDisplay(
                                                    numbering, split.displayText());
                            int maxLineNumber = 1;
                            if (lineNumbers != null) {
                                for (int lineNumber : lineNumbers) {
                                    if (lineNumber > maxLineNumber) {
                                        maxLineNumber = lineNumber;
                                    }
                                }
                            }
                            if (gen != generation) {
                                return;
                            }
                            RSyntaxDocument document =
                                    ChunkedTextLoader.buildDocument(
                                            split.displayText(),
                                            style,
                                            split,
                                            () -> gen != generation,
                                            editable);
                            if (document == null) {
                                return;
                            }
                            final int installedLineCount = maxLineNumber;
                            SwingUtilities.invokeLater(
                                    () -> {
                                        if (gen != generation) {
                                            return;
                                        }
                                        install(
                                                raw,
                                                split,
                                                lineNumbers,
                                                installedLineCount,
                                                document,
                                                gen);
                                    });
                        });
    }

    /**
     * The text to show. Pretty-printing is whitespace-only; if a formatter ever changes a
     * non-whitespace character the raw text is shown instead, which keeps every offset mapping
     * exact. There is no size cutoff: formatting runs off the EDT.
     */
    private String projection(String raw, String contentType, boolean stripTrailing) {
        String text = format(raw, contentType, stripTrailing);
        if (!TextAlignment.sameNonWhitespace(raw, text)) {
            LOGGER.debug("Formatter changed non-whitespace; showing the raw message instead.");
            text = stripTrailing ? stripTrailingNewlines(raw) : raw;
        }
        return text;
    }

    /**
     * Formats a body that is pasted into this view. Header views and pastes into the header block
     * of an All view are left exactly as pasted; a body is formatted with the message's content
     * type, or with a sniff of the pasted text when the content type is not decisive.
     */
    private String formatPastedText(String text) {
        if (text == null || text.isEmpty() || isHeaderTarget()) {
            return text;
        }
        if (target == Target.REQUEST_ALL || target == Target.RESPONSE_ALL) {
            // The All view shows the header block and the body in one document. Only a paste that
            // starts and ends after the blank-line separator is a body paste.
            String display = textArea.getText();
            int separator = display.indexOf("\n\n");
            if (separator < 0
                    || textArea.getSelectionStart() <= separator + 1
                    || textArea.getSelectionEnd() <= separator + 1) {
                return text;
            }
        }
        return PrettyPrint.formatPastedBody(contentTypeOf(model.getMessage()), text);
    }

    /**
     * Body pretty-printing, best-effort and never throwing: the All views reformat the body
     * that follows the header block, the body-only views reformat the whole (body) text, and the
     * header-only views just drop the blank line that terminates the header block.
     */
    private String format(String text, String contentType, boolean stripTrailing) {
        switch (target) {
            case REQUEST_ALL:
            case RESPONSE_ALL:
                return PrettyPrint.formatMessageFor(contentType, text, true);
            case REQUEST_BODY:
            case RESPONSE_BODY:
                return PrettyPrint.formatBody(ContentType.styleKeyFor(contentType, text), text);
            default:
                return stripTrailing ? stripTrailingNewlines(text) : text;
        }
    }

    /** Swaps in the prepared document. Must run on the EDT. */
    private void install(
            String raw,
            DisplayTextSplitter.Split split,
            int[] lineNumbers,
            int maxLineNumber,
            RSyntaxDocument document,
            long gen) {
        rawText = raw;
        displaySource = split.displayText();
        shownMessage = model.getMessage();
        ChunkedTextLoader.installDocument(
                textArea,
                document,
                () -> {
                    textArea.discardAllEdits();
                    textArea.setMessageText(raw);
                });
        textArea.applyManagerHighlights();
        int numberedLines = applyLineNumbering(split, lineNumbers, maxLineNumber);
        trackUserEdits();
        installedDigits = digits(numberedLines);
        // The document is the message as it was loaded, so an earlier edit is gone.
        changed = false;
        restoreCaret();
        restoreScroll();
        rememberBody();
        // The document is laid out once the pending revalidation has run; only then does the text
        // area report the width it can really paint in.
        Timer check =
                new Timer(
                        30,
                        e -> {
                            calibrateGutter();
                            correctWrapWidth(gen);
                        });
        check.setRepeats(false);
        check.start();
    }

    /**
     * Watches the installed document so that a user edit is noticed. Only insertions and removals
     * count: the syntax highlighting changes styles (not content) while the text is painted.
     */
    private void trackUserEdits() {
        Document document = textArea.getDocument();
        if (document == null) {
            return;
        }
        document.addDocumentListener(
                new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        changed = true;
                        if (editable) {
                            updateEditableNumbering();
                        }
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        changed = true;
                        if (editable) {
                            updateEditableNumbering();
                        }
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        // Style change only, the text is the same.
                    }
                });
    }

    /** Puts the caret back where the user left it after the view was re-rendered. */
    private void restoreCaret() {
        if (pendingCaretCount < 0) {
            return;
        }
        int count = pendingCaretCount;
        pendingCaretCount = -1;
        try {
            int offset = TextAlignment.offsetAfterNonWhitespace(textArea.getText(), count);
            textArea.setCaretPosition(Math.min(offset, textArea.getDocument().getLength()));
        } catch (Exception ignored) {
            // The document changed; leave the caret where it is.
        }
    }

    /** Puts the scrollbar back after the view was re-rendered for the same message. */
    private void restoreScroll() {
        if (pendingScrollValue < 0) {
            return;
        }
        int value = pendingScrollValue;
        pendingScrollValue = -1;
        SwingUtilities.invokeLater(
                () -> {
                    try {
                        scrollPane.getVerticalScrollBar().setValue(value);
                    } catch (RuntimeException ignored) {
                        // The view was removed; nothing to restore.
                    }
                });
    }

    /**
     * Numbers the displayed lines with the message's own line numbers. While the view is editable
     * the numbers follow the logical lines as they are typed; a read-only view keeps the raw
     * message numbers, with formatter-inserted lines left blank.
     */
    private int applyLineNumbering(
            DisplayTextSplitter.Split split, int[] lineNumbers, int maxLineNumber) {
        Gutter gutter = scrollPane.getGutter();
        if (gutter == null) {
            return maxLineNumber;
        }
        try {
            if (editable) {
                return updateEditableNumbering();
            }
            gutter.setLineNumberFormatter(
                    new PrettifyLineNumberFormatter(lineNumbers, maxLineNumber));
        } catch (Exception ignored) {
            // Gutter not available (e.g. line numbers disabled) - keep the default numbering.
        }
        return maxLineNumber;
    }

    /**
     * Numbers the non-blank rows of the text being edited, so the gutter keeps following the
     * message's logical lines while the user types. Formatter-inserted blank rows stay unnumbered.
     */
    private int updateEditableNumbering() {
        Gutter gutter = scrollPane.getGutter();
        if (gutter == null) {
            return 1;
        }
        String text = textArea.getText();
        int lines = TextAlignment.countLines(text);
        int[] numbers = new int[lines];
        int number = 0;
        int start = 0;
        for (int i = 0; i < lines; i++) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            boolean blank = true;
            for (int j = start; j < end; j++) {
                if (!Character.isWhitespace(text.charAt(j))) {
                    blank = false;
                    break;
                }
            }
            numbers[i] = blank ? 0 : ++number;
            start = end + 1;
        }
        gutter.setLineNumberFormatter(new PrettifyLineNumberFormatter(numbers, number));
        return number;
    }

    /**
     * Wrap width predicted from the current viewport and the line-number gutter the new message
     * will need, or 0 while either is unknown. Using the current extent directly would size the
     * split for the previous message's gutter (its digit count differs), and the installed
     * document would then have to be re-wrapped once the new gutter had been laid out. The vertical
     * scrollbar is always shown, so the extent minus the gutter is the only changing part.
     */
    private int predictedWrapWidthPx() {
        int extentWidth = scrollPane.getViewport().getExtentSize().width;
        Gutter gutter = scrollPane.getGutter();
        int currentGutterWidth = gutter == null ? 0 : gutter.getWidth();
        if (extentWidth <= 0 || currentGutterWidth <= 0) {
            return 0;
        }
        int gutterWidth = predictedGutterWidth(predictedLineNumberDigits());
        if (gutterWidth <= 0) {
            return 0;
        }
        int width = extentWidth - gutterWidth + currentGutterWidth;
        Insets insets = textArea.getInsets();
        if (insets != null) {
            width -= insets.left + insets.right;
        }
        Insets margin = textArea.getMargin();
        if (margin != null) {
            width -= margin.left + margin.right;
        }
        return Math.max(0, width);
    }

    /** Width the gutter will have for a document whose last line number has {@code digits}. */
    private int predictedGutterWidth(int digits) {
        int step = gutterDigitWidth();
        if (step <= 0 || gutterFixedWidth < 0) {
            return -1;
        }
        return gutterFixedWidth + step * digits;
    }

    /** Width of one digit in the gutter's own (smaller) line-number font. */
    private int gutterDigitWidth() {
        if (gutterDigitWidth > 0) {
            return gutterDigitWidth;
        }
        Gutter gutter = scrollPane.getGutter();
        if (gutter == null || gutter.getComponentCount() == 0) {
            return -1;
        }
        Component numbers = gutter.getComponent(0);
        Font font = numbers.getFont();
        if (font == null) {
            return -1;
        }
        int step = numbers.getFontMetrics(font).charWidth('9');
        if (step > 0) {
            gutterDigitWidth = step;
        }
        return gutterDigitWidth;
    }

    /**
     * Learns the gutter's fixed part (borders and padding) from a laid-out gutter, so the width for
     * another digit count can be predicted exactly before installing its document.
     */
    private void calibrateGutter() {
        Gutter gutter = scrollPane.getGutter();
        int step = gutterDigitWidth();
        if (gutter == null || step <= 0 || installedDigits <= 0 || gutter.getWidth() <= 0) {
            return;
        }
        int fixed = gutter.getWidth() - step * installedDigits;
        if (fixed >= 0 && fixed < 64) {
            gutterFixedWidth = fixed;
        }
    }

    /** Line-number digits the message being shown will need. */
    private int predictedLineNumberDigits() {
        String data = model.getData();
        if (data == null) {
            return 1;
        }
        if (shouldStripTrailingNewlines(model.getMessage())) {
            data = stripTrailingNewlines(data);
        }
        int lines = 1;
        for (int i = 0; i < data.length(); i++) {
            if (data.charAt(i) == '\n') {
                lines++;
            }
        }
        return digits(lines);
    }

    private static int digits(int value) {
        return Math.max(1, Integer.toString(Math.max(1, value)).length());
    }

    /**
     * Fills the rows to the width the view can really paint in. The viewport extent can be wider
     * than the space the text area ends up with (row header and scrollbar widths are only settled
     * after a layout pass); splitting for the stale, narrower width would otherwise leave a few
     * characters unused at the right edge.
     */
    private void correctWrapWidth(long gen) {
        if (editable || gen != generation || widthCorrections >= 2 || changed) {
            return;
        }
        int widthPx = drawableWidthPx();
        if (widthPx <= 0) {
            return;
        }
        int advance = charAdvance();
        if (wrappedWidthPx > 0 && Math.abs(widthPx - wrappedWidthPx) < advance) {
            return;
        }
        widthCorrections++;
        resplit();
    }

    /**
     * Width, in pixels, the text area can draw in right now, or 0 if it has not been laid out yet.
     * Uses the visible part of the component, so a text area wider than its viewport (a long line
     * before the split) is clipped to what is actually shown.
     */
    private int drawableWidthPx() {
        int width = textArea.getVisibleRect().width;
        if (width <= 0) {
            width = textArea.getWidth();
        }
        if (width <= 0) {
            width = scrollPane.getViewport().getExtentSize().width;
        }
        if (width <= 0) {
            return 0;
        }
        Insets insets = textArea.getInsets();
        if (insets != null) {
            width -= insets.left + insets.right;
        }
        Insets margin = textArea.getMargin();
        if (margin != null) {
            width -= margin.left + margin.right;
        }
        return Math.max(0, width);
    }

    /** Width in pixels that a wrapped row may use, or 0 if the width is not known yet. */
    private int wrapWidthPx() {
        // The viewport extent already excludes the vertical scrollbar when it is shown, so no
        // scrollbar width is reserved; using the extent directly fills the row.
        int width = scrollPane.getViewport().getExtentSize().width;
        if (width <= 0) {
            width = scrollPane.getWidth();
        }
        if (width <= 0) {
            return 0;
        }
        Insets insets = textArea.getInsets();
        if (insets != null) {
            width -= insets.left + insets.right;
        }
        Insets margin = textArea.getMargin();
        if (margin != null) {
            width -= margin.left + margin.right;
        }
        return Math.max(0, width);
    }

    /** Wrap width measured from the laid-out text area, falling back to the viewport. */
    private int currentWrapWidthPx() {
        int widthPx = drawableWidthPx();
        return widthPx > 0 ? widthPx : wrapWidthPx();
    }

    /** The width of one character of the message font, at least one pixel. */
    private int charAdvance() {
        Font font = textArea.getFont();
        if (font == null) {
            return 1;
        }
        return Math.max(1, textArea.getFontMetrics(font).charWidth('M'));
    }

    /**
     * Per-code-point rendered widths for the wrap. Characters the font does not have a monospaced
     * glyph for (binary bodies, control pictures, CJK fallback, ...) are wider than {@code 'M'}, so
     * wrapping by character count would overflow the viewport; the splitter wraps by these measured
     * widths instead. Cached for the current font, because a resize asks for the same widths again.
     */
    private DisplayTextSplitter.CharWidths charWidths() {
        final Font font = textArea.getFont();
        if (font != null && font == charWidthsFont && charWidths != null) {
            return charWidths;
        }
        // RSTA paints with fractional font metrics enabled (see PrettifyTextArea), so the advance
        // per character is the fractional one, which can differ from the rounded FontMetrics
        // charWidth() by a noticeable fraction. Measure with the same fractional context the
        // painter uses, otherwise a row is broken a few characters before the right edge.
        final FontMetrics metrics = textArea.getFontMetrics(font);
        final FontRenderContext frc = new FontRenderContext(null, true, true);
        final double monospaceAdvance = monospaceAdvance(font, frc, metrics);
        // A tab advances to the next tab stop, i.e. at most TAB_SIZE columns; upper-bound it so a
        // tab can never make a row overflow.
        final double tabWidth =
                (monospaceAdvance > 0 ? monospaceAdvance : metrics.charWidth(' '))
                        * PrettifyTextArea.TAB_SIZE;
        DisplayTextSplitter.CharWidths measured =
                new DisplayTextSplitter.CharWidths() {
                    private final double[] cache = new double[0x10000];
                    private final java.util.HashMap<Integer, Double> wide =
                            new java.util.HashMap<>();

                    @Override
                    public double widthOf(int codePoint) {
                        if (codePoint == '\t') {
                            return tabWidth;
                        }
                        if (codePoint >= 0 && codePoint < 0x10000) {
                            double w = cache[codePoint];
                            if (w <= 0) {
                                w =
                                        measuredWidth(
                                                codePoint,
                                                font,
                                                frc,
                                                metrics,
                                                monospaceAdvance);
                                cache[codePoint] = w;
                            }
                            return w;
                        }
                        Double w = wide.get(codePoint);
                        if (w == null) {
                            w =
                                    measuredWidth(
                                            codePoint, font, frc, metrics, monospaceAdvance);
                            wide.put(codePoint, w);
                        }
                        return w;
                    }
                };
        charWidthsFont = font;
        charWidths = measured;
        return measured;
    }

    /**
     * The font's exact per-character advance when it is monospaced, or -1 otherwise. RSTA rounds the
     * width of a whole token run, so this (rather than the rounded {@code charWidth}) is the width a
     * row really paints.
     */
    private static double monospaceAdvance(Font font, FontRenderContext frc, FontMetrics metrics) {
        int charWidth = metrics.charWidth('M');
        if (metrics.charWidth('i') != charWidth || metrics.charWidth('W') != charWidth) {
            return -1;
        }
        return font.createGlyphVector(frc, "M").getGlyphPosition(1).getX();
    }

    private static double measuredWidth(
            int codePoint,
            Font font,
            FontRenderContext frc,
            FontMetrics metrics,
            double monospaceAdvance) {
        if (monospaceAdvance > 0
                && font.canDisplay(codePoint)
                && Math.abs(metrics.charWidth(codePoint) - Math.round(monospaceAdvance)) <= 1) {
            return monospaceAdvance;
        }
        return font.getStringBounds(new String(Character.toChars(codePoint)), frc).getWidth();
    }

    /**
     * Re-splits as soon as the panel has really changed width. A change smaller than one character
     * is ignored because it cannot move a break. The split runs off the EDT, and a newer width
     * supersedes an older one, so there is no need to wait for a resize to settle.
     */
    private void resplitIfWidthChanged() {
        if (editable || changed) {
            // The user is editing: re-splitting would replace the text they are working on.
            return;
        }
        int widthPx = currentWrapWidthPx();
        if (widthPx <= 0) {
            return;
        }
        if (wrappedWidthPx > 0 && Math.abs(widthPx - wrappedWidthPx) < charAdvance()) {
            return;
        }
        widthCorrections = 0;
        resplit();
    }

    /** Syntax highlighting is always applied, based on the fixed style or the content type. */
    private String styleFor(String contentType, String body) {
        if (fixedStyleKey != null) {
            return fixedStyleKey;
        }
        return ContentType.styleKeyFor(contentType, body);
    }

    private boolean isHeaderTarget() {
        return target == Target.REQUEST_HEAD || target == Target.RESPONSE_HEAD;
    }

    /**
     * Whether trailing newlines should be dropped from the model text. A header block always ends
     * with the blank-line terminator ({@code "\n\n"}); header-only views drop it, and so must an All
     * view whose body is empty - otherwise that terminator trails the view as two blank lines. When
     * a body is present the terminator is kept, as it separates the headers from the body.
     */
    private boolean shouldStripTrailingNewlines(Message message) {
        if (isHeaderTarget()) {
            return true;
        }
        if ((target != Target.REQUEST_ALL && target != Target.RESPONSE_ALL)
                || !(message instanceof HttpMessage)) {
            return false;
        }
        HttpMessage httpMessage = (HttpMessage) message;
        if (target == Target.REQUEST_ALL) {
            return httpMessage.getRequestBody().length() == 0;
        }
        return httpMessage.getResponseBody().length() == 0;
    }

    private String contentTypeOf(Message message) {
        if (!(message instanceof HttpMessage)) {
            return null;
        }
        HttpMessage httpMessage = (HttpMessage) message;
        switch (target) {
            case REQUEST_BODY:
            case REQUEST_ALL:
                return httpMessage.getRequestHeader().getHeader("Content-Type");
            case RESPONSE_BODY:
            case RESPONSE_ALL:
                return httpMessage.getResponseHeader().getHeader("Content-Type");
            default:
                return null;
        }
    }

    /** Removes trailing CR/LF characters so a header block does not show dangling blank lines. */
    private static String stripTrailingNewlines(String s) {
        if (s == null) {
            return null;
        }
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\n' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCaptionName() {
        return Constant.messages.getString(captionKey);
    }

    @Override
    public String getTargetViewName() {
        // Add "Pretty" as its own tab rather than having it replace the core "Text" view.
        // Replacing the text view is destructive (the core view is removed from the panel and
        // never restored), so unloading the add-on could not put the panel back the way it was.
        // Targeting nothing keeps the add-on non-destructive and cleanly removable at runtime.
        return "";
    }

    @Override
    public int getPosition() {
        return Integer.MIN_VALUE;
    }

    @Override
    public JComponent getPane() {
        return mainPanel;
    }

    @Override
    public void setSelected(boolean selected) {
        if (selected) {
            textArea.requestFocusInWindow();
        }
    }

    @Override
    public void save() {
        if (!changed) {
            // Nothing was edited: never rewrite the message with the formatter's whitespace.
            return;
        }
        String edited = textArea.getText();
        String raw = rawText;
        String display = displaySource;
        String newRaw = TextAlignment.applyEditToRaw(raw, display, edited);
        if (newRaw == null) {
            newRaw = edited;
        }
        boolean messageChanged = !newRaw.equals(raw);
        if (messageChanged) {
            saving = true;
            try {
                model.setData(newRaw);
            } catch (RuntimeException e) {
                LOGGER.warn("Could not save the edited message.", e);
                return;
            } finally {
                saving = false;
            }
        }
        changed = false;
        // Remember what the model actually holds, so a later dataChanged for the same message
        // does not rebuild the document and throw away the undo history. The model may normalize
        // trailing header newlines; the texts stay whitespace-equivalent, which is all the offset
        // mapping needs.
        String saved = model.getData();
        rawText =
                saved != null && TextAlignment.sameNonWhitespace(saved, edited) ? saved : newRaw;
        displaySource = edited;
        rememberBody();
        // The body is in the message now, so the request can be given the content type it has.
        boolean headerChanged = setContentTypeIfMissing();
        if (headerChanged && target == Target.REQUEST_ALL) {
            // The All view shows the header block, so the added type has to be rendered. The
            // body view is unaffected by a header-only change and keeps its document (and undo
            // history) instead of being rebuilt after every send.
            pendingCaretCount =
                    TextAlignment.nonWhitespaceBefore(edited, textArea.getCaretPosition());
            pendingScrollValue = scrollPane.getVerticalScrollBar().getValue();
            updateInternal();
        }
    }

    @Override
    public HttpPanelViewModel getModel() {
        return model;
    }

    @Override
    public boolean isEnabled(Message message) {
        return true;
    }

    @Override
    public boolean hasChanged() {
        return changed;
    }

    @Override
    public boolean isEditable() {
        return editable;
    }

    @Override
    public void setEditable(boolean editable) {
        if (this.editable == editable) {
            return;
        }
        this.editable = editable;
        textArea.setEditable(editable);
        applyWrapPolicy();
        if (!editable) {
            // A view that cannot be edited must not carry changes into the message.
            changed = false;
            textArea.discardAllEdits();
        }
        if (model.getMessage() != null) {
            // The request may carry a body that says what it is; the header is filled in before
            // the view is rendered, so that it is shown with the content type. The rendering mode
            // also depends on whether the view is editable.
            setContentTypeIfMissing();
            update();
        }
    }

    /**
     * Keeps the horizontal scrollbar out of the way: pre-split rows fit the viewport, and an
     * editable view wraps them. It is only useful when word wrap is turned off while editing.
     */
    private void applyWrapPolicy() {
        boolean needed = editable && !textArea.getLineWrap();
        scrollPane.setHorizontalScrollBarPolicy(
                needed
                        ? ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                        : ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    }

    @Override
    public void setParentConfigurationKey(String configurationKey) {
        this.configurationKey = configurationKey + name + ".";
    }

    @Override
    public void loadConfiguration(FileConfiguration fileConfiguration) {
        textArea.loadConfiguration(configurationKey, fileConfiguration);
        applyWrapPolicy();
    }

    @Override
    public void saveConfiguration(FileConfiguration fileConfiguration) {
        textArea.saveConfiguration(configurationKey, fileConfiguration);
    }

    @Override
    public void dataChanged(HttpPanelViewModelEvent e) {
        if (saving || adjustingBody) {
            return;
        }
        undoZapBodyChange();
        setContentTypeIfMissing();
        String data = model.getData();
        if (data == null) {
            data = "";
        }
        if (data.equals(rawText)
                || (model.getMessage() == shownMessage
                        && TextAlignment.sameNonWhitespace(data, rawText))) {
            // The model still holds the text the current document was built from. Keeping the
            // document keeps the user's edits and their undo history; rebuilding it here was what
            // made Ctrl+Z stop working after a send. A whitespace-only difference (line endings or
            // a trailing newline the model normalises) does not change what the view shows, but a
            // different message always gets its own document.
            return;
        }
        update();
    }

    /**
     * Gives a request that carries a body the content type it has, when it does not have one: the
     * type is taken from the body, so that a request sent with a JSON body says that it is JSON.
     * Only a request that is being edited is touched.
     *
     * @return whether the request header was changed
     */
    private boolean setContentTypeIfMissing() {
        if (!editable) {
            return false;
        }
        if (target != Target.REQUEST_BODY && target != Target.REQUEST_ALL) {
            return false;
        }
        Message message = model.getMessage();
        if (!(message instanceof HttpMessage)) {
            return false;
        }
        HttpMessage httpMessage = (HttpMessage) message;
        HttpRequestHeader header = httpMessage.getRequestHeader();
        if (header == null) {
            return false;
        }
        if (!methodCarriesBody(header.getMethod())) {
            return false;
        }
        String body =
                httpMessage.getRequestBody() == null
                        ? ""
                        : httpMessage.getRequestBody().toString();
        if (body.trim().isEmpty()) {
            return false;
        }
        String contentType = ContentType.contentTypeFor(ContentType.sniff(body));
        if (contentType == null) {
            // The body does not say what it is, so nothing is guessed.
            return false;
        }
        List<String> currentValues = header.getHeaderValues("Content-Type");
        if (currentValues == null || currentValues.isEmpty()) {
            // The request does not say what it carries.
            header.setHeader("Content-Type", contentType);
            return true;
        }
        if (currentValues.size() > 1) {
            // ZAP's setHeader rewrites every header line with that name. A request can legitimately
            // carry repeated headers, and the user may have added them on purpose, so leave the
            // whole header block (and its order) alone rather than collapsing the values.
            return false;
        }
        String current = currentValues.get(0);
        if (contentType.equals(current)) {
            return false;
        }
        if (isFormContentType(current) && !isFormContentType(contentType)) {
            // ZAP labels a request as a form when it moves the query string back into the body;
            // the body says what it is, so the label is corrected.
            header.setHeader("Content-Type", contentType);
            return true;
        }
        return false;
    }

    /** Whether a content type names a form body. */
    private static boolean isFormContentType(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded");
    }

    /** Whether a request method normally carries a body. */
    private static boolean methodCarriesBody(String method) {
        if (method == null) {
            return false;
        }
        switch (method.toUpperCase(Locale.ROOT)) {
            case "POST":
            case "PUT":
            case "PATCH":
            case "UPDATE":
                return true;
            default:
                return false;
        }
    }

    /**
     * Puts the request body back when ZAP changed it while the method was changed. The body is
     * moved into the query string and back again, and a parameter without a value gains a "=" on
     * the way back, so the body that comes back is not the body that was there.
     */
    private void undoZapBodyChange() {
        if (!editable || adjustingBody) {
            return;
        }
        adjustingBody = true;
        try {
            String data = model.getData();
            String body = bodyOf(data);
            String method = methodOf(data);
            if (body != null && !body.isEmpty()) {
                String remembered = lastShownBody;
                boolean methodChanged = !method.equals(lastShownMethod);
                if (!changed
                        && !remembered.isEmpty()
                        && !body.equals(remembered)
                        && (methodChanged || body.equals(remembered + "="))) {
                    // ZAP moved the body into the query string and back again: the body it puts
                    // back is not the body that was there (it re-encodes a parameter without a
                    // value as "name=").
                    setBodyOf(data, remembered);
                    data = model.getData();
                }
                rememberBody();
            }
            // An empty body is not remembered: the body is empty while it is in the query string,
            // and it is the body that was there before that has to be remembered.
            lastShownMethod = method;
        } finally {
            adjustingBody = false;
        }
    }

    /** The request method of the message, or an empty string when it is not a request. */
    private String methodOf(String data) {
        if (target == Target.RESPONSE_BODY
                || target == Target.RESPONSE_ALL
                || target == Target.RESPONSE_HEAD) {
            return "";
        }
        Message message = model.getMessage();
        if (!(message instanceof HttpMessage)) {
            return "";
        }
        HttpRequestHeader header = ((HttpMessage) message).getRequestHeader();
        return header == null || header.getMethod() == null ? "" : header.getMethod();
    }

    /**
     * Keeps track of the body the view shows. An empty body is not remembered: the body is empty
     * while it is in the query string, and it is the body that was there before that has to be
     * remembered.
     */
    private void rememberBody() {
        String body = bodyOf(model.getData());
        if (body == null || body.isEmpty()) {
            return;
        }
        lastShownBody = body;
    }

    /** The request body of the message data, or null when this view does not show a body. */
    private String bodyOf(String data) {
        if (data == null) {
            return null;
        }
        if (target == Target.REQUEST_BODY) {
            return data;
        }
        if (target != Target.REQUEST_ALL) {
            return null;
        }
        int split = data.indexOf("\n\n");
        return split < 0 ? "" : data.substring(split + 2);
    }

    /** Writes the request body back into the message data. */
    private void setBodyOf(String data, String body) {
        if (target == Target.REQUEST_BODY) {
            model.setData(body);
            return;
        }
        int split = data == null ? -1 : data.indexOf("\n\n");
        String header = split < 0 ? "" : data.substring(0, split);
        model.setData(header + "\n\n" + body);
    }

    @Override
    public void search(Pattern pattern, List<SearchMatch> matches) {
        // The HTTP panel models normalise CRLF to LF, so the displayed text, the raw message and
        // the search offsets all use LF. No header/body CRLF conversion is applied: converting the
        // offsets back and forth was what added line endings the message never had.
        String display = textArea.getText();
        String raw = rawText;
        if (raw == null) {
            raw = model.getData();
        }
        if (raw == null) {
            raw = display;
        }
        if (!TextAlignment.sameNonWhitespace(display, raw)) {
            // The view is showing something that does not align with the raw message (for example
            // while a new document is being prepared); report the matches as they are.
            reportUnmappedMatches(pattern, display, matches);
            return;
        }
        Matcher matcher = pattern.matcher(display);
        while (matcher.find()) {
            int start = TextAlignment.displayToRaw(display, raw, matcher.start());
            int end = TextAlignment.displayToRawEnd(display, raw, matcher.end());
            if (start < 0 || end < 0 || start >= end) {
                continue;
            }
            SearchMatch.Location location = locationFor(raw, start);
            if (location == null) {
                continue;
            }
            boolean combined =
                    target == Target.REQUEST_ALL || target == Target.RESPONSE_ALL;
            if (isHeaderLocation(location)) {
                int headerEnd = combined ? bodyStartInLf(raw) : raw.length();
                int clampedEnd = Math.min(end, headerEnd);
                matches.add(new SearchMatch(location, start, clampedEnd));
            } else {
                int bodyStart = combined ? bodyStartInLf(raw) : 0;
                matches.add(
                        new SearchMatch(location, start - bodyStart, end - bodyStart));
            }
        }
    }

    /** Fallback used only when the displayed text cannot be aligned with the raw message. */
    private void reportUnmappedMatches(
            Pattern pattern, String text, List<SearchMatch> matches) {
        Matcher matcher = pattern.matcher(text);
        SearchMatch.Location location = fixedLocation();
        if (location == null) {
            return;
        }
        while (matcher.find()) {
            if (matcher.start() < matcher.end()) {
                matches.add(new SearchMatch(location, matcher.start(), matcher.end()));
            }
        }
    }

    @Override
    public void highlight(SearchMatch searchMatch) {
        if (searchMatch == null) {
            return;
        }
        String raw = rawText;
        String display = displaySource;
        if (raw == null || display == null || raw.isEmpty() || display.isEmpty()) {
            return;
        }
        int start = searchMatch.getStart();
        int end = searchMatch.getEnd();
        SearchMatch.Location location = searchMatch.getLocation();
        int sourceStart;
        int sourceEnd;
        switch (location) {
            case REQUEST_HEAD:
            case RESPONSE_HEAD:
                sourceStart = start;
                sourceEnd = end;
                break;
            case REQUEST_BODY:
            case RESPONSE_BODY:
                sourceStart = start;
                sourceEnd = end;
                if (target == Target.REQUEST_ALL || target == Target.RESPONSE_ALL) {
                    int bodyStart = bodyStartInLf(raw);
                    sourceStart += bodyStart;
                    sourceEnd += bodyStart;
                }
                break;
            default:
                return;
        }
        int displayStart = TextAlignment.rawToDisplay(raw, display, sourceStart);
        int displayEnd = TextAlignment.rawToDisplayEnd(raw, display, sourceEnd);
        if (displayStart < 0 || displayEnd < 0 || displayStart >= displayEnd) {
            return;
        }
        int length = textArea.getDocument().getLength();
        if (displayStart < 0 || displayEnd > length) {
            return;
        }
        textArea.highlightRange(displayStart, displayEnd);
    }

    /** The location of a match that starts at {@code lfOffset} in the raw LF text. */
    private SearchMatch.Location locationFor(String raw, int lfOffset) {
        switch (target) {
            case REQUEST_HEAD:
                return SearchMatch.Location.REQUEST_HEAD;
            case RESPONSE_HEAD:
                return SearchMatch.Location.RESPONSE_HEAD;
            case REQUEST_BODY:
                return SearchMatch.Location.REQUEST_BODY;
            case RESPONSE_BODY:
                return SearchMatch.Location.RESPONSE_BODY;
            case REQUEST_ALL:
                return lfOffset < bodyStartInLf(raw)
                        ? SearchMatch.Location.REQUEST_HEAD
                        : SearchMatch.Location.REQUEST_BODY;
            case RESPONSE_ALL:
                return lfOffset < bodyStartInLf(raw)
                        ? SearchMatch.Location.RESPONSE_HEAD
                        : SearchMatch.Location.RESPONSE_BODY;
            default:
                return null;
        }
    }

    /** The fixed location of a body/header-only view, or null for the combined views. */
    private SearchMatch.Location fixedLocation() {
        switch (target) {
            case REQUEST_HEAD:
                return SearchMatch.Location.REQUEST_HEAD;
            case RESPONSE_HEAD:
                return SearchMatch.Location.RESPONSE_HEAD;
            case REQUEST_BODY:
                return SearchMatch.Location.REQUEST_BODY;
            case RESPONSE_BODY:
                return SearchMatch.Location.RESPONSE_BODY;
            default:
                return null;
        }
    }

    private static boolean isHeaderLocation(SearchMatch.Location location) {
        return location == SearchMatch.Location.REQUEST_HEAD
                || location == SearchMatch.Location.RESPONSE_HEAD;
    }

    /** Offset of the first body character in the raw LF message, or the text length. */
    static int bodyStartInLf(String raw) {
        if (raw == null) {
            return 0;
        }
        int separator = raw.indexOf("\n\n");
        return separator < 0 ? raw.length() : separator + 2;
    }

    /** The ZAP message menu, with the standard text actions supplied by {@link PrettifyTextActions}. */
    private final class CustomPopupMenu extends JPopupMenu {

        private static final long serialVersionUID = 1L;

        @Override
        public void show(Component invoker, int x, int y) {
            if (!textArea.isFocusOwner()) {
                textArea.requestFocusInWindow();
            }
            Message message = model.getMessage();
            View view = View.getSingleton();
            if (message instanceof HttpMessage && view != null && view.getPopupMenu() != null) {
                DefaultSingleHttpMessageContainer container =
                        new DefaultSingleHttpMessageContainer(
                                MESSAGE_CONTAINER_NAME, textArea, (HttpMessage) message);
                view.getPopupMenu().show(container, x, y);
            } else if (view != null && view.getPopupMenu() != null) {
                view.getPopupMenu().show(textArea, x, y);
            } else {
                super.show(invoker, x, y);
            }
        }
    }
}
