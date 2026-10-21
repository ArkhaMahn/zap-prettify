package org.zaproxy.zap.extension.prettify;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.font.FontRenderContext;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import org.apache.commons.configuration.FileConfiguration;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.network.HttpMessage;
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
    private int wrappedWidthPx = -1;

    /** Debounces re-splitting while the panel is being resized. */
    private Timer resplitTimer;

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
        Message message = model.getMessage();
        final String data = model.getData();
        final String contentType = contentTypeOf(message);
        final boolean stripTrailing = shouldStripTrailingNewlines(message);
        final int widthPx = wrapMaxWidthPx();
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
                                numbering =
                                        stripTrailing ? stripTrailingNewlines(data) : data;
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
            final int widthPx,
            final long gen) {
        final DisplayTextSplitter.Split newSplit =
                DisplayTextSplitter.split(text, numberingSource, style, widthPx, charWidths());
        final RSyntaxDocument document =
                ChunkedTextLoader.buildDocument(
                        newSplit.displayText(), style, newSplit, () -> gen != generation);
        if (document == null) {
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (gen != generation) {
                        return;
                    }
                    install(newSplit, document);
                });
    }

    /** Swaps in the prepared document. Must run on the EDT. */
    private void install(DisplayTextSplitter.Split newSplit, RSyntaxDocument document) {
        split = newSplit;
        ChunkedTextLoader.installDocument(textArea, document, textArea::discardAllEdits);
        textArea.setSplit(newSplit);
        applyLineNumbering(newSplit);
    }

    /**
     * Numbers only the rows that start a real message line; the rows produced by our breaks (and
     * any soft-wrapped continuation rows) are left blank.
     */
    private void applyLineNumbering(DisplayTextSplitter.Split newSplit) {
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
     * Width in pixels of the widest common character, using the same fractional metrics the text
     * area paints with. The integral {@code FontMetrics.charWidth} rounds down (7 vs 7.22 for
     * DejaVu Sans Mono), which would let a row overflow the viewport.
     */
    private double charAdvance() {
        Font font = textArea.getFont();
        if (font == null) {
            return 0;
        }
        FontRenderContext frc = new FontRenderContext(null, true, true);
        double widest = font.getStringBounds("M", frc).getWidth();
        widest = Math.max(widest, font.getStringBounds("W", frc).getWidth());
        return widest;
    }

    /**
     * Per-code-point rendered widths for the wrap. Characters the font does not have a monospaced
     * glyph for (binary bodies, control pictures, CJK fallback, ...) are wider than {@code 'M'}, so
     * wrapping by character count would overflow the viewport; the splitter wraps by these measured
     * widths instead.
     */
    private DisplayTextSplitter.CharWidths charWidths() {
        final Font font = textArea.getFont();
        final FontRenderContext frc = new FontRenderContext(null, true, true);
        // A tab advances to the next tab stop, i.e. at most TAB_SIZE columns; upper-bound it so a
        // tab can never make a row overflow.
        final double tabWidth = charAdvance() * PrettifyTextArea.TAB_SIZE;
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
                        w = measure(codePoint, font, frc);
                        cache[codePoint] = w;
                    }
                    return w;
                }
                Double w = wide.get(codePoint);
                if (w == null) {
                    w = measure(codePoint, font, frc);
                    wide.put(codePoint, w);
                }
                return w;
            }
        };
    }

    private static double measure(int codePoint, Font font, FontRenderContext frc) {
        String s = new String(Character.toChars(codePoint));
        return font.getStringBounds(s, frc).getWidth();
    }

    private void resplitIfWidthChanged() {
        int widthPx = wrapMaxWidthPx();
        if (widthPx <= 0 || widthPx == wrappedWidthPx) {
            return;
        }
        if (resplitTimer == null) {
            resplitTimer = new Timer(150, e -> update());
            resplitTimer.setRepeats(false);
        }
        resplitTimer.restart();
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
        // Read-only view.
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
        return false;
    }

    @Override
    public boolean isEditable() {
        return false;
    }

    @Override
    public void setEditable(boolean editable) {
        // Read-only view.
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
        update();
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
