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
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.extension.httppanel.view.AbstractStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelEvent;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelViewModelListener;
import org.zaproxy.zap.extension.search.SearchMatch;
import org.zaproxy.zap.extension.search.SearchableHttpPanelView;
import org.zaproxy.zap.utils.DisplayUtils;

/**
 * An {@link HttpPanelView} that shows the raw HTTP message (or one of its parts) as a Burp-style,
 * syntax-highlighted, word-wrapped text area.
 *
 * <p>The message is shown exactly as sent/received, with content-type based syntax highlighting
 * always on. Overlong single lines are pre-broken at the wrap column (see {@link
 * DisplayTextSplitter}) so the wrapped view stays fast; the original text and the exact break
 * offsets are kept so search can map back to the untouched message.
 *
 * <p>Text preparation happens off the Event Dispatch Thread and is cancelled when a newer message
 * arrives.
 */
public class PrettifyHttpPanelView
        implements HttpPanelView, HttpPanelViewModelListener, SearchableHttpPanelView {

    private static final Logger LOGGER = LogManager.getLogger(PrettifyHttpPanelView.class);

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

    /** Current display/original offset mapping; null until the first document is installed. */
    private DisplayTextSplitter.Split split;

    /** Incremented on every request; stale background results are dropped against it. */
    private volatile long generation;

    /** Wrap width (in pixels) the current document was split for; -1 means "unknown". */
    private volatile int wrappedWidthPx = -1;

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

    /**
     * Re-splits spent on correcting the wrap width after a document was laid out. A layout can
     * hand the view less room than the viewport extent suggests (the viewport is sized before the
     * gutter and the scrollbar settle), which would leave the rows short of the right edge. Bounded
     * so a scrollbar appearing/disappearing cannot make the two widths trade places forever.
     */
    private int widthCorrections;

    /** Debounces re-splitting while the panel is being resized. */
    private Timer resplitTimer;

    /** Whether the panel this view is mounted in allows the message to be edited. */
    private volatile boolean editable;

    /** Whether the user changed the displayed text since it was loaded or last saved. */
    private boolean changed;

    /**
     * Where the caret is, counted in the characters that are not whitespace. The count survives the
     * re-formatting of the text, which moves the caret but not the characters around it.
     */
    private int pendingCaretCount = -1;

    /** Re-formats the text shortly after the user typed, the way Burp does. */
    private Timer reformatTimer;

    /**
     * Start offsets of the rows the gutter numbers (the rows that are a line of the message).
     * Kept while the user edits, so the numbering follows the text as it is typed.
     */
    private int[] numberedOffsets = new int[0];

    /**
     * Start offsets of the rows the gutter leaves blank: the rows a wrapped line continues in and
     * the rows the pretty printer added.
     */
    private int[] blankOffsets = new int[0];

    /** The display text the row offsets above belong to. */
    private String gutterRowsText = "";

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
        this.scrollPane = new RTextScrollPane(textArea, true);
        applyGutterTheme();
        // The text is pre-wrapped to fit the viewport, so a horizontal scrollbar could never reveal
        // anything: its thumb would always span the full track. Disable it so the wrap appears
        // contained instead of showing a redundant bar.
        this.scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        this.mainPanel.add(scrollPane, BorderLayout.CENTER);
        this.textArea.addComponentListener(
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
        // the real drawable width and no horizontal scrollbar is produced.
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
        BurpTheme theme = textArea.isDark() ? BurpTheme.DARK : BurpTheme.LIGHT;
        gutter.setLineNumberColor(theme.getLineNumbers());
        gutter.setBorderColor(theme.getLineNumbers());
    }

    private void update() {
        widthCorrections = 0;
        int widthPx = predictedWrapWidthPx();
        update(widthPx > 0 ? widthPx : wrapMaxWidthPx());
    }

    /**
     * Starts a background split for {@code widthPx}. The width usually comes from the viewport, but
     * a layout can hand the text area less room than the viewport suggests; {@link #install} then
     * corrects it from the width the text area really has.
     */
    private void update(final int widthPx) {
        Message message = model.getMessage();
        final String data = model.getData();
        final String contentType = contentTypeOf(message);
        final boolean stripTrailing = shouldStripTrailingNewlines(message);
        final long gen = ++generation;
        wrappedWidthPx = widthPx;

        PrettifyExecutor.get()
                .submit(
                        () -> {
                            String text = data;
                            String numbering = data;
                            if (data != null) {
                                text = format(data, contentType, stripTrailing);
                                // The gutter keeps the original message line numbers: pass the text
                                // as it is without formatting (only the header-block cleanup) so
                                // reformat-inserted lines are not numbered.
                                numbering = stripTrailing ? stripTrailingNewlines(data) : data;
                            }
                            prepareAndInstall(
                                    text, numbering, styleFor(contentType, text), widthPx, gen);
                        });
    }

    /**
     * Burp-style pretty-printing, best-effort and never throwing: the All views reformat the body
     * that follows the header block, the body-only views reformat the whole (body) text, and the
     * header-only views just drop the blank line that terminates the header block.
     */
    private String format(String text, String contentType, boolean stripTrailing) {
        switch (target) {
            case REQUEST_ALL:
            case RESPONSE_ALL:
                return PrettyPrint.formatMessageFor(contentType, text);
            case REQUEST_BODY:
            case RESPONSE_BODY:
                return PrettyPrint.formatBody(ContentType.styleKeyFor(contentType, text), text);
            default:
                return stripTrailing ? stripTrailingNewlines(text) : text;
        }
    }

    /**
     * Off the EDT: pre-breaks the text at the wrap width, then builds the syntax document in slices
     * (see {@link ChunkedTextLoader}) so the UI thread never tokenises a huge payload. The finished
     * document is then installed on the EDT.
     */
    private void prepareAndInstall(
            final String text,
            final String numberingSource,
            final String style,
            int widthPx,
            final long gen) {
        final DisplayTextSplitter.Split prepared =
                DisplayTextSplitter.split(text, numberingSource, style, widthPx, charWidths());
        if (prepared == null) {
            return;
        }
        wrappedWidthPx = widthPx;
        final RSyntaxDocument document =
                ChunkedTextLoader.buildDocument(
                        prepared.displayText(), style, prepared, () -> gen != generation, editable);
        if (document == null) {
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (gen != generation) {
                        return;
                    }
                    install(prepared, document, gen);
                });
    }

    /** Swaps in the prepared document. Must run on the EDT. */
    private void install(DisplayTextSplitter.Split newSplit, RSyntaxDocument document, long gen) {
        split = newSplit;
        ChunkedTextLoader.installDocument(textArea, document, textArea::discardAllEdits);
        trackUserEdits();
        textArea.setSplit(newSplit);
        captureGutterRows(newSplit);
        applyLineNumbering(newSplit);
        installedDigits = digits(newSplit.originalLineCount());
        // The text the user sees is the text we installed, so any earlier edit is gone.
        changed = false;
        restoreCaret();
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
                        textEdited(e.getOffset(), 0, e.getLength());
                        scheduleReformat();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        changed = true;
                        textEdited(e.getOffset(), e.getLength(), 0);
                        scheduleReformat();
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
            String text = textArea.getText();
            int offset = offsetAfterNonWhitespace(text, count);
            textArea.setCaretPosition(Math.min(offset, text.length()));
        } catch (Exception ignored) {
            // The document shrank; leave the caret where it is.
        }
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
        if (editable || gen != generation || widthCorrections >= 2 || wrappedWidthPx <= 0 || changed) {
            return;
        }
        int widthPx = drawableWidthPx();
        if (widthPx <= 0) {
            return;
        }
        int target = widthPx;
        int advance = Math.max(1, textArea.getFontMetrics(textArea.getFont()).charWidth('M'));
        // Ignore sub-character differences (rounding), but both a gap of a whole character and a
        // row that paints past the visible edge are worth one more split.
        if (target - wrappedWidthPx < advance && wrappedWidthPx - target < 2 * advance) {
            return;
        }
        widthCorrections++;
        update(target);
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

    /**
     * Notes which rows the gutter numbers and which it leaves blank. The rows are found by their
     * offset in the text, so they can be followed when the user edits the text.
     */
    private void captureGutterRows(DisplayTextSplitter.Split newSplit) {
        if (!editable) {
            numberedOffsets = new int[0];
            blankOffsets = new int[0];
            gutterRowsText = "";
            return;
        }
        String displayText = newSplit.displayText();
        if (displayText.equals(gutterRowsText)) {
            // The text did not change, so the rows are the ones that are already known.
            return;
        }
        int[] numbers = newSplit.displayLineNumbers();
        int[] starts = rowStarts(displayText);
        int count = Math.min(numbers.length, starts.length);
        int numbered = 0;
        int blank = 0;
        for (int i = 0; i < count; i++) {
            if (numbers[i] > 0) {
                numbered++;
            } else {
                blank++;
            }
        }
        numberedOffsets = new int[numbered];
        blankOffsets = new int[blank];
        int at = 0;
        int blankAt = 0;
        for (int i = 0; i < count; i++) {
            if (numbers[i] > 0) {
                numberedOffsets[at++] = starts[i];
            } else {
                blankOffsets[blankAt++] = starts[i];
            }
        }
        gutterRowsText = displayText;
    }

    /**
     * Schedules the re-formatting of the text. Burp re-formats the message while it is typed in an
     * editable message; the re-formatting waits for a short pause, so that it does not happen in
     * the middle of a word.
     */
    private void scheduleReformat() {
        if (!editable) {
            return;
        }
        if (reformatTimer == null) {
            reformatTimer =
                    new Timer(
                            400,
                            e -> {
                                try {
                                    reformat();
                                } catch (RuntimeException ex) {
                                    LOGGER.warn("Could not re-format the message.", ex);
                                }
                            });
            reformatTimer.setRepeats(false);
        }
        reformatTimer.restart();
    }

    /**
     * Re-formats the text the user typed. The message keeps the text it was received with, with the
     * change applied to it, and the view is rendered again from it, which shows the re-formatted
     * text.
     */
    private void reformat() {
        DisplayTextSplitter.Split activeSplit = split;
        if (activeSplit == null) {
            return;
        }
        String edited = textArea.getText();
        String raw = rawText();
        String formatted = projection(raw);
        String editedFormatted = activeSplit.toMessageText(edited);
        if (editedFormatted.equals(formatted)) {
            return;
        }
        String newRaw = applyEditToRaw(raw, formatted, editedFormatted);
        if (newRaw.equals(raw)) {
            return;
        }
        pendingCaretCount = countNonWhitespace(edited, 0, textArea.getCaretPosition());
        try {
            model.setData(newRaw);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not re-format the message.", e);
            return;
        }
        update();
    }

    /**
     * Follows an edit made in the text area: the rows after it move, and the numbering is brought
     * up to date so the lines the user added are numbered like the ones that were there before.
     */
    private void textEdited(int offset, int removed, int inserted) {
        if (!editable) {
            return;
        }
        // The rows the edit creates are part of the row they were typed in, so they are numbered
        // like it.
        int[] created = inserted > 0 ? createdRowStarts(offset, inserted) : new int[0];
        boolean blank = isBlankRow(rowStartAt(offset));
        numberedOffsets =
                DisplayTextSplitter.shiftOffsets(numberedOffsets, offset, removed, inserted);
        blankOffsets = DisplayTextSplitter.shiftOffsets(blankOffsets, offset, removed, inserted);
        if (blank) {
            for (int start : created) {
                blankOffsets = insertOffset(blankOffsets, start);
            }
        }
        updateEditableNumbering();
    }

    /** Start offsets of the rows the inserted text created. */
    private int[] createdRowStarts(int offset, int inserted) {
        Document document = textArea.getDocument();
        String text;
        try {
            text = document.getText(offset, inserted);
        } catch (Exception e) {
            return new int[0];
        }
        int rows = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                rows++;
            }
        }
        int[] starts = new int[rows];
        int at = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[at++] = offset + i + 1;
            }
        }
        return starts;
    }

    /** Start offset of the row the given offset is in. */
    private int rowStartAt(int offset) {
        String text = textArea.getText();
        int start = Math.min(offset, text.length());
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }

    /** Adds an offset to a sorted list of offsets. */
    private static int[] insertOffset(int[] offsets, int offset) {
        int[] grown = new int[offsets.length + 1];
        int at = 0;
        while (at < offsets.length && offsets[at] < offset) {
            grown[at] = offsets[at];
            at++;
        }
        grown[at] = offset;
        System.arraycopy(offsets, at, grown, at + 1, offsets.length - at);
        return grown;
    }

    /** Start offsets of the rows of {@code text}. */
    private static int[] rowStarts(String text) {
        int rows = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                rows++;
            }
        }
        int[] starts = new int[rows];
        int row = 0;
        starts[0] = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                row++;
                starts[row] = i + 1;
            }
        }
        return starts;
    }

    /**
     * Numbers the rows the gutter numbers and leaves the rest blank. The numbers are the ones of
     * the message, so they follow the text while it is edited and do not change when the message
     * is sent.
     */
    private void updateEditableNumbering() {
        Gutter gutter = scrollPane.getGutter();
        if (gutter == null) {
            return;
        }
        int[] starts = rowStarts(textArea.getText());
        int[] numbers = new int[starts.length];
        int number = 0;
        for (int i = 0; i < starts.length; i++) {
            numbers[i] = isBlankRow(starts[i]) ? 0 : ++number;
        }
        try {
            gutter.setLineNumberFormatter(new PrettifyLineNumberFormatter(numbers, number));
        } catch (Exception ignored) {
            // Gutter not available (e.g. line numbers disabled) - keep the default numbering.
        }
    }

    /** Whether the row starting at {@code offset} is one the gutter leaves blank. */
    private boolean isBlankRow(int offset) {
        return java.util.Arrays.binarySearch(blankOffsets, offset) >= 0;
    }

    /**
     * Numbers only the rows that start a real message line; the rows produced by our breaks (and
     * any soft-wrapped continuation rows) are left blank.
     */
    private void applyLineNumbering(DisplayTextSplitter.Split newSplit) {
        if (editable) {
            // The numbering follows the text the user is editing, not the message it came from.
            updateEditableNumbering();
            return;
        }
        try {
            Gutter gutter = scrollPane.getGutter();
            if (gutter == null) {
                return;
            }
            gutter.setLineNumberFormatter(
                    new PrettifyLineNumberFormatter(
                            newSplit.displayLineNumbers(), newSplit.originalLineCount()));
        } catch (Exception ignored) {
            // Gutter not available (e.g. line numbers disabled) - keep the default numbering.
        }
    }

    /** Width in pixels that a wrapped row may use, or 0 if the width is not known yet. */
    private int wrapMaxWidthPx() {
        return wrapWidthPx();
    }

    /** Width, in pixels, that a wrapped row may use, or 0 if the width is not known yet. */
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

    /**
     * Per-code-point rendered widths for the wrap. Characters the font does not have a monospaced
     * glyph for (binary bodies, control pictures, CJK fallback, ...) are wider than {@code 'M'}, so
     * wrapping by character count would overflow the viewport; the splitter wraps by these measured
     * widths instead.
     */
    private DisplayTextSplitter.CharWidths charWidths() {
        final Font font = textArea.getFont();
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
        return new DisplayTextSplitter.CharWidths() {
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
                        w = measuredWidth(codePoint, font, frc, metrics, monospaceAdvance);
                        cache[codePoint] = w;
                    }
                    return w;
                }
                Double w = wide.get(codePoint);
                if (w == null) {
                    w = measuredWidth(codePoint, font, frc, metrics, monospaceAdvance);
                    wide.put(codePoint, w);
                }
                return w;
            }
        };
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
            int codePoint, Font font, FontRenderContext frc, FontMetrics metrics,
            double monospaceAdvance) {
        if (monospaceAdvance > 0
                && font.canDisplay(codePoint)
                && Math.abs(metrics.charWidth(codePoint) - Math.round(monospaceAdvance)) <= 1) {
            return monospaceAdvance;
        }
        return font.getStringBounds(new String(Character.toChars(codePoint)), frc).getWidth();
    }

    private void resplitIfWidthChanged() {
        if (editable || changed) {
            // The user is editing: re-splitting would replace the text they are working on.
            return;
        }
        int widthPx = currentWrapWidthPx();
        if (widthPx <= 0 || widthPx == wrappedWidthPx) {
            return;
        }
        if (resplitTimer == null) {
            resplitTimer =
                    new Timer(
                            150,
                            e -> {
                                widthCorrections = 0;
                                update(currentWrapWidthPx());
                            });
            resplitTimer.setRepeats(false);
        }
        resplitTimer.restart();
    }

    /** Wrap width measured from the laid-out text area, falling back to the viewport. */
    private int currentWrapWidthPx() {
        int widthPx = drawableWidthPx();
        return widthPx > 0 ? widthPx : wrapMaxWidthPx();
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
        // What is shown is what is sent: the body stays formatted, as the view shows it.
        String text = messageText();
        try {
            model.setData(text);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not save the edited message.", e);
            return;
        }
        changed = false;
        rememberBody();
        // The body is in the message now, so the request can be given the content type it has.
        setContentTypeIfMissing();
        // The message now holds the text the user typed, so the view is rendered again from it;
        // that also rebuilds the wrap breaks, so a further edit is unwrapped correctly.
        pendingCaretCount =
                countNonWhitespace(textArea.getText(), 0, textArea.getCaretPosition());
        update();
    }

    /**
     * The message text to save: the message as it is, with the change the user made in the
     * formatted view applied to it. The message keeps the whitespace it was received with, the
     * formatted view is only a view of it.
     */
    private String messageText() {
        String edited = textArea.getText();
        DisplayTextSplitter.Split activeSplit = split;
        if (activeSplit == null) {
            return edited;
        }
        String raw = rawText();
        return applyEditToRaw(raw, projection(raw), activeSplit.toMessageText(edited));
    }

    /**
     * The formatted text the Pretty view shows for the given message text. It is worked out again
     * when the message is saved, because the view may not have been rendered since the last change.
     */
    private String projection(String raw) {
        Message message = model.getMessage();
        return format(raw, contentTypeOf(message), shouldStripTrailingNewlines(message));
    }

    /** The message as it is, without the formatting the Pretty view shows. */
    private String rawText() {
        return model.getData();
    }

    /**
     * Applies the change the user made in the formatted text to the message text. The two texts
     * hold the same characters apart from the whitespace the formatter changed, so the changed
     * range is found by counting the characters that are not whitespace.
     */
    static String applyEditToRaw(String raw, String formatted, String edited) {
        if (raw == null || edited == null || formatted == null) {
            return edited;
        }
        if (formatted.equals(edited)) {
            return raw;
        }
        int prefix = 0;
        int limit = Math.min(formatted.length(), edited.length());
        while (prefix < limit && formatted.charAt(prefix) == edited.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && formatted.charAt(formatted.length() - 1 - suffix)
                        == edited.charAt(edited.length() - 1 - suffix)) {
            suffix++;
        }
        int total = countNonWhitespace(formatted, 0, formatted.length());
        int inSuffix = countNonWhitespace(formatted, formatted.length() - suffix, formatted.length());
        if (countNonWhitespace(raw, 0, raw.length()) != total) {
            // The formatter changed more than the whitespace: the change cannot be placed.
            return edited;
        }
        int before = countNonWhitespace(formatted, 0, prefix);
        int after = total - inSuffix;
        int rawStart = offsetOfNonWhitespace(raw, before + 1);
        int rawEnd = offsetOfNonWhitespace(raw, after + 1);
        if (rawStart < 0 || rawEnd < rawStart || rawEnd > raw.length()) {
            return edited;
        }
        int editedEnd = edited.length() - suffix;
        if (rawStart == rawEnd) {
            // Nothing was there: the whitespace that follows the inserted text belongs with it, or
            // the line the text was inserted in would be joined with the next one.
            editedEnd += leadingWhitespace(edited, editedEnd);
        }
        return raw.substring(0, rawStart)
                + edited.substring(prefix, editedEnd)
                + raw.substring(rawEnd);
    }

    /** The offset of the {@code count}th character that is not whitespace, or the text length. */
    private static int offsetOfNonWhitespace(String text, int count) {
        if (count <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                seen++;
                if (seen == count) {
                    return i;
                }
            }
        }
        return text.length();
    }

    /** The number of whitespace characters at the given offset. */
    private static int leadingWhitespace(String text, int offset) {
        int count = 0;
        while (offset + count < text.length() && Character.isWhitespace(text.charAt(offset + count))) {
            count++;
        }
        return count;
    }

    /** The number of characters that are not whitespace in {@code text[from, to)}. */
    private static int countNonWhitespace(String text, int from, int to) {
        int count = 0;
        int end = Math.min(to, text.length());
        for (int i = Math.max(0, from); i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * The offset in {@code text} just after the {@code count}th character that is not whitespace, or
     * the length of the text when it holds fewer.
     */
    private static int offsetAfterNonWhitespace(String text, int count) {
        if (count <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                seen++;
                if (seen == count) {
                    return i + 1;
                }
            }
        }
        return text.length();
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
        // The view always shows the message in its formatted form, so saving it always writes that
        // form back - the same as the core text view, which also always saves what it shows.
        return true;
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
        // The rows of a view that is only read are broken up to the width of the viewport; an
        // editable view is wrapped by the text area, which re-wraps what the user types.
        textArea.setLineWrap(editable);
        if (!editable) {
            // A view that cannot be edited must not carry changes into the message.
            changed = false;
        }
        if (!changed && model.getMessage() != null) {
            // The request may carry a body that says what it is; the header is filled in before
            // the view is rendered, so that it is shown with the content type.
            setContentTypeIfMissing();
            // The document of an editable view is a plain one (the wrapped rows of a read-only
            // document carry the tokens of the unwrapped text, which an edit would invalidate), so
            // the view is rendered again to get the right kind of document.
            update();
        }
    }

    @Override
    public void setParentConfigurationKey(String configurationKey) {
        this.configurationKey = configurationKey + name + ".";
    }

    @Override
    public void loadConfiguration(FileConfiguration fileConfiguration) {
        // No configurable state.
    }

    @Override
    public void saveConfiguration(FileConfiguration fileConfiguration) {
        // No configurable state.
    }

    @Override
    public void dataChanged(HttpPanelViewModelEvent e) {
        undoZapBodyChange();
        setContentTypeIfMissing();
        update();
    }

    /**
     * Gives a request that carries a body the content type it has, when it does not have one: the
     * type is taken from the body, so that a request sent with a JSON body says that it is JSON.
     * Only a request that is being edited is touched.
     */
    private void setContentTypeIfMissing() {
        if (!editable) {
            return;
        }
        if (target != Target.REQUEST_BODY && target != Target.REQUEST_ALL) {
            return;
        }
        Message message = model.getMessage();
        if (!(message instanceof HttpMessage)) {
            return;
        }
        HttpMessage httpMessage = (HttpMessage) message;
        HttpRequestHeader header = httpMessage.getRequestHeader();
        if (header == null) {
            return;
        }
        if (!methodCarriesBody(header.getMethod())) {
            return;
        }
        String body =
                httpMessage.getRequestBody() == null
                        ? ""
                        : httpMessage.getRequestBody().toString();
        if (body.trim().isEmpty()) {
            return;
        }
        String contentType = ContentType.contentTypeFor(ContentType.sniff(body));
        if (contentType == null) {
            // The body does not say what it is, so nothing is guessed.
            return;
        }
        String current = header.getHeader("Content-Type");
        if (current == null) {
            // The request does not say what it carries.
            header.setHeader("Content-Type", contentType);
            return;
        }
        if (contentType.equals(current)) {
            return;
        }
        if (isFormContentType(current) && !isFormContentType(contentType)) {
            // ZAP labels a request as a form when it moves the query string back into the body;
            // the body says what it is, so the label is corrected.
            header.setHeader("Content-Type", contentType);
        }
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
        if (!editable) {
            return;
        }
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
                // ZAP moved the body into the query string and back again: the body it puts back is
                // not the body that was there (it re-encodes a parameter without a value as
                // "name=").
                setBodyOf(data, remembered);
            }
            rememberBody();
        }
        // An empty body is not remembered: the body is empty while it is in the query string, and
        // it is the body that was there before that has to be remembered.
        lastShownMethod = method;
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
        DisplayTextSplitter.Split activeSplit = split;
        Matcher matcher = pattern.matcher(textArea.getText());
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (activeSplit != null) {
                start = activeSplit.toOriginalOffset(start);
                end = activeSplit.toOriginalOffset(end);
            }
            if (start >= end) {
                continue;
            }
            matches.add(new SearchMatch(location(), start, end));
        }
    }

    @Override
    public void highlight(SearchMatch searchMatch) {
        int start = searchMatch.getStart();
        int end = searchMatch.getEnd();
        DisplayTextSplitter.Split activeSplit = split;
        if (activeSplit != null) {
            start = activeSplit.toDisplayOffset(start);
            end = activeSplit.toDisplayOffset(end);
        }
        int length = textArea.getDocument().getLength();
        if (start < 0 || end > length || start >= end) {
            return;
        }
        try {
            Highlighter highlighter = textArea.getHighlighter();
            highlighter.removeAllHighlights();
            highlighter.addHighlight(
                    start,
                    end,
                    new DefaultHighlighter.DefaultHighlightPainter(DisplayUtils.getHighlightColor()));
            textArea.setCaretPosition(start);
        } catch (Exception ignored) {
            // Invalid offsets - nothing to highlight.
        }
    }

    private SearchMatch.Location location() {
        switch (target) {
            case REQUEST_BODY:
                return SearchMatch.Location.REQUEST_BODY;
            case RESPONSE_HEAD:
                return SearchMatch.Location.RESPONSE_HEAD;
            case RESPONSE_BODY:
                return SearchMatch.Location.RESPONSE_BODY;
            default:
                return SearchMatch.Location.REQUEST_HEAD;
        }
    }
}
