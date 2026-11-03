package org.zaproxy.zap.extension.prettify;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.font.FontRenderContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.Segment;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
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

    /**
     * The message the installed document was built from, so a re-render of the same message can
     * be told apart from ZAP swapping a different message into the panel. The undo history is
     * bound to the message the session shows: a different message starts the edit session over.
     */
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
     * Whether saving an edited message adds the Content-Type header the body implies (the toggle in
     * the Pretty pop-up). On by default, like the documented behavior of the filler.
     */
    private boolean updateContentType = true;

    /**
     * Whether the view pretty-prints the message (the toggle in the Pretty pop-up). Off, the
     * message is shown as received and a paste is inserted exactly as pasted.
     */
    private boolean formatEnabled = true;

    /** Whether tokens are coloured by the theme (the toggle in the Pretty pop-up). */
    private boolean highlightEnabled = true;

    /** Whether a coalesced editable-gutter rebuild is already queued on the EDT. */
    private boolean editableNumberingPending;

    /** One-shot check that re-validates the wrap width after a new document has been laid out. */
    private Timer installCheck;

    /**
     * The request body the view last showed. ZAP moves the body into the query string when the
     * method changes away from POST and moves it back when it changes to POST; on the way back a
     * parameter that has no value is re-encoded as {@code name=}, so the body comes back with a
     * "=" it never had. The body is remembered so that this can be undone.
     */
    private String lastShownBody = "";

    /**
     * The query string of the request URI the view last showed together with the body in
     * {@link #lastShownBody}. The return trip of the method change is not body-only: when the
     * method changes to POST, ZAP moves the whole query string into the body, taking the parameters
     * the URL had before the body was merged into it with it. The query that was there before is
     * remembered so that it can be put back with the body.
     */
    private String lastShownQuery = "";

    /**
     * The message the pair in {@link #lastShownBody} and {@link #lastShownQuery} was remembered
     * from. The pair must not carry between messages: when another request is shown, what the
     * previous request had is no longer this request's and is reset.
     */
    private Message lastShownMessage;

    /** The request method the view last showed, to notice a change of the verb. */
    private String lastShownMethod = "";

    /**
     * The document of the current edit session, and the body style {@code sessionStyle} it was
     * built for. Keeping one document over the re-renders, and replacing only its content (exactly
     * how the plain text views update their one document), is what keeps the undo history going
     * for the body and the headers alike: RTextArea installs a fresh undo manager whenever a
     * document is swapped in, so a rebuilt document throws the history away.
     *
     * <p>The history is the history of one message, like it is the history of one request in
     * every tab of Repeater and the manual request editor. The session starts at the message:
     * there is nothing to undo beyond it, so the walk of a Ctrl+Z stays between the user's
     * edits and the rendered states of the message - an empty text area appears in no part of
     * it. When ZAP swaps a different message into the panel, the session is started over for
     * the new message: nothing an earlier request or its session had on record is reinserted in
     * this editor.
     *
     * <p>A change of the body style (the content type a view without a fixed style is showing)
     * rebuilds the document, and so starts the history over. Only editable views keep a session.
     */
    private RSyntaxDocument sessionDocument;

    private String sessionStyle;

    private String configurationKey = "";

    /**
     * Per-code-point rendered widths shared by every view that paints with the same font. A font
     * table is expensive to measure and identical across views, so the few fonts in use are kept
     * in a small access-ordered cache instead of allocating one table per view.
     */
    private static final int CHAR_WIDTH_CACHE_SIZE = 8;

    private static final Map<Font, FontCharWidths> CHAR_WIDTHS =
            new LinkedHashMap<Font, FontCharWidths>(4, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<Font, FontCharWidths> eldest) {
                    return size() > CHAR_WIDTH_CACHE_SIZE;
                }
            };

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
        this.textArea.setOptions(
                new PrettifyTextArea.Options() {
                    @Override
                    public boolean isOn(PrettifyTextArea.Option option) {
                        switch (option) {
                            case UPDATE_CONTENT_TYPE:
                                return updateContentType;
                            case WRAP:
                                return textArea.isWordWrapConfigured();
                            case FORMAT:
                                return formatEnabled;
                            case SYNTAX_HIGHLIGHT:
                                return highlightEnabled;
                        }
                        return true;
                    }

                    @Override
                    public void setOn(PrettifyTextArea.Option option, boolean on) {
                        switch (option) {
                            case UPDATE_CONTENT_TYPE:
                                updateContentType = on;
                                return;
                            case WRAP:
                                setWrapEnabled(on);
                                return;
                            case FORMAT:
                                setFormatEnabled(on);
                                return;
                            case SYNTAX_HIGHLIGHT:
                                setHighlightEnabled(on);
                                return;
                        }
                    }

                    @Override
                    public boolean isApplicable(PrettifyTextArea.Option option) {
                        if (option != PrettifyTextArea.Option.UPDATE_CONTENT_TYPE) {
                            // Wrap, formatting and highlighting apply to every part of the message.
                            return true;
                        }
                        return editable
                                && (target == Target.REQUEST_BODY || target == Target.REQUEST_ALL);
                    }
                });
        this.scrollPane = new RTextScrollPane(textArea, true);
        applyGutterTheme();
        textArea.addThemeListener(this::applyGutterTheme);
        applyWrapPolicy();
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

    /**
     * Applies the wrap decision to both rendering modes: the editor follows the setting while
     * editable, a read-only view switches between the width pre-split and the raw rows (which is
     * why a flip while read-only re-renders), and the scrollbar policy follows the wrap state.
     */
    private void setWrapEnabled(boolean wrap) {
        textArea.setWordWrapConfigured(wrap);
        applyWrapPolicy();
        if (!editable && model.getMessage() != null) {
            SwingUtilities.invokeLater(this::update);
        }
    }

    /** Applies the formatting decision to the message being shown (a live re-render). */
    private void setFormatEnabled(boolean format) {
        if (formatEnabled == format) {
            return;
        }
        formatEnabled = format;
        if (model.getMessage() != null) {
            SwingUtilities.invokeLater(this::update);
        }
    }

    /** Forwards the highlighting decision to the text area, which repaints with a new scheme. */
    private void setHighlightEnabled(boolean highlight) {
        highlightEnabled = highlight;
        textArea.setSyntaxHighlightingEnabled(highlight);
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
        int caret = textArea.getCaretPosition();
        // A read-only view's document is exactly displaySource, so mapping the caret does not need
        // a full copy of the document. An editable view may hold unsaved text, so it still reads
        // the document. The caret is usually at the top, where no scan is needed.
        String source = editable ? textArea.getText() : displaySource;
        pendingCaretCount = caret <= 0 ? 0 : TextAlignment.nonWhitespaceBefore(source, caret);
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
                            try {
                                prepareAndInstall(
                                        raw, contentType, stripTrailing, numbering, widthPx, widths, gen);
                            } catch (RuntimeException | OutOfMemoryError e) {
                                // Preparing a huge message can fail (for example when the heap cannot
                                // hold the spans, the formatted text and the document at once). Keep
                                // the previous document instead of letting the executor swallow it.
                                LOGGER.warn(
                                        "Could not prepare the message view; keeping the previous document.",
                                        e);
                            }
                        });
    }

    /** Does the off-EDT formatting, splitting and document build for one generation. */
    private void prepareAndInstall(
            String raw,
            String contentType,
            boolean stripTrailing,
            String numbering,
            int widthPx,
            DisplayTextSplitter.CharWidths widths,
            long gen) {
        // A resize can queue several splits. A superseded one returns before doing any work, and
        // the checks between the expensive steps stop one that is already running as soon as a
        // newer split arrives.
        if (gen != generation) {
            return;
        }
        String display = projection(raw, contentType, stripTrailing);
        if (gen != generation) {
            return;
        }
        String style = styleFor(contentType, display);
        DisplayTextSplitter.Split split;
        if (editable) {
            split = new DisplayTextSplitter.Split(display, display, new int[0]);
        } else if (textArea.isWordWrapConfigured()) {
            split = DisplayTextSplitter.split(display, style, widthPx, widths);
        } else {
            // Wrap off, read-only: the received rows are kept, so the viewport scrolls past long
            // rows instead of the text being reflowed to fit it.
            split = DisplayTextSplitter.split(display);
        }
        if (gen != generation) {
            return;
        }
        // An editable view numbers its rows from the text as it is typed (see
        // updateEditableNumbering), so it does not need this full walk.
        int[] lineNumbers =
                editable ? null : LineNumbers.forDisplay(numbering, split.displayText());
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
        RSyntaxDocument document;
        if (editable && sessionDocument != null && style.equals(sessionStyle)) {
            // The edit session keeps its document: the re-render replaces its content in install
            // instead of swapping a new one (and the undo history) in. The style has to be the
            // session's because PrettifyDocument tokenizes it; a style change is an epoch that
            // rebuilds the session. The identity of the document in the text area is re-checked
            // on the EDT in install; the fields are read here across threads without a lock,
            // which is safe because a decision taken on stale values builds a fresh document.
            document = sessionDocument;
        } else if (editable) {
            // A session starts with the empty document: the message is filled in through the text
            // area in install, so that the fill is the first edit of the session - like the plain
            // text views, a Ctrl+Z then takes the fresh view back to the empty text area.
            document =
                    ChunkedTextLoader.buildDocument(
                            "", style, split, () -> gen != generation, editable);
            if (document == null) {
                return;
            }
        } else {
            document =
                    ChunkedTextLoader.buildDocument(
                            split.displayText(), style, split, () -> gen != generation, editable);
            if (document == null) {
                return;
            }
        }
        final int installedLineCount = maxLineNumber;
        SwingUtilities.invokeLater(
                () -> {
                    if (gen != generation) {
                        return;
                    }
                    install(raw, split, lineNumbers, installedLineCount, document, style);
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
        if (!formatEnabled) {
            // The view shows the message as received; a paste is kept exactly as pasted.
            return text;
        }
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
        if (!formatEnabled) {
            // The formatting toggle is off: show the received text. Only the dangling blank
            // line of a header block is dropped (a whitespace-only change, so the display stays
            // aligned with the received text and the gutter keeps the received line numbers).
            return stripTrailing ? stripTrailingNewlines(text) : text;
        }
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
            String style) {
        rawText = raw;
        displaySource = split.displayText();
        // The message shown last decides whether this install refreshes the session of the
        // message already shown, or whether ZAP swapped a different message into the panel.
        Message previouslyShownMessage = shownMessage;
        shownMessage = model.getMessage();
        if (editable && document == sessionDocument && textArea.getDocument() == document) {
            if (previouslyShownMessage != shownMessage) {
                // A different message is shown now, so the entries of an earlier session must
                // never resurface in this editor: the edit session starts over, like every tab
                // of Repeater or the manual request editor owns the history of its request
                // alone. The session starts with the new request shown and nothing on record,
                // so undo has no history of the earlier request and no empty text area in it.
                startSession();
            } else {
                // The same message is re-rendered (a header was mutated, the message was
                // echoed): the whole render is recorded as one atomic edit with the state of
                // the document around it, so undo walks between the rendered states of this
                // message and the user's edits, without ever emptying the text area on the way.
                fillSession();
            }
        } else {
            ChunkedTextLoader.installDocument(
                    textArea, document, () -> textArea.discardAllEdits());
            trackUserEdits();
            if (editable) {
                // A new editable document is the start (or the epoch) of an edit session: the
                // document was built empty and the message is filled in now, which is not part
                // of the history - the session starts at the message, with no state before it
                // to undo back to, and with no earlier session to carry anything over from.
                sessionDocument = document;
                sessionStyle = style;
                startSession();
            }
        }
        textArea.setMessageText(raw);
        textArea.applyManagerHighlights();
        int numberedLines = applyLineNumbering(split, lineNumbers, maxLineNumber);
        installedDigits = digits(numberedLines);
        // The document shows the message as the model has it: nothing is waiting to be saved back.
        // In the session path of a same-message re-render the earlier user edits are not lost
        // either - they are retrievable with undo - but they no longer differ from the shown
        // message after the re-render. In the session path of a message swap the session is
        // fresh, so an unsaved edit made in the earlier request is gone with its history.
        changed = false;
        restoreCaret();
        restoreScroll();
        rememberBody();
        // The document is laid out once the pending revalidation has run; only then does the text
        // area report the width it can really paint in. One reusable timer avoids leaving a burst
        // of installs with an equal burst of pending timers.
        if (installCheck == null) {
            installCheck =
                    new Timer(
                            30,
                            e -> {
                                calibrateGutter();
                                correctWrapWidth(generation);
                            });
            installCheck.setRepeats(false);
        }
        installCheck.restart();
    }

    /**
     * Starts the edit session on the document the session keeps. Must run on the Event Dispatch
     * Thread.
     *
     * <p>The session starts with the message shown and nothing on record: whatever another
     * request or an earlier state of this one had - however it was rendered or edited - is not
     * in the history, and no Ctrl+Z can bring it or any empty text area back into this editor.
     * Undo becomes usable with the first edit the user makes on the message.
     */
    private void startSession() {
        textArea.setText(displaySource);
        textArea.discardAllEdits();
    }

    /**
     * Refreshes the session document through the text area. Must run on the Event Dispatch
     * Thread.
     *
     * <p>The whole re-render is one undoable edit: the swap runs inside an atomic edit, which the
     * undo manager keeps as a single step with the state of the document around it, so a Ctrl+Z
     * of a fill takes the state the view displayed before the re-render back directly - never
     * through any emptied text area and never merged into one step with the edit the user made
     * right before it. A re-render that shows what the document already shows is no state at all
     * and records nothing. An empty message wipes the history, like the core plain text views
     * discard it around their empty fill: there is nothing documented state left that the earlier
     * history could return to. A re-render that starts the fill from the emptied document keeps
     * the transition out of the history too: there is no earlier state to walk back to.
     */
    private void fillSession() {
        String before = textArea.getText();
        String after = displaySource;
        if (before.equals(after)) {
            return;
        }
        if (after.isEmpty()) {
            textArea.setText("");
            textArea.discardAllEdits();
            return;
        }
        if (before.isEmpty()) {
            textArea.setText(after);
            textArea.discardAllEdits();
            return;
        }
        textArea.beginAtomicEdit();
        try {
            textArea.setText(after);
        } finally {
            textArea.endAtomicEdit();
        }
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
                            scheduleEditableNumbering();
                        }
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        changed = true;
                        if (editable) {
                            scheduleEditableNumbering();
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
            int offset =
                    count <= 0
                            ? 0
                            : TextAlignment.offsetAfterNonWhitespace(displaySource, count);
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
        editableNumberingPending = false;
        Gutter gutter = scrollPane.getGutter();
        if (gutter == null) {
            return 1;
        }
        Document document = textArea.getDocument();
        if (document == null) {
            return 1;
        }
        Element root = document.getDefaultRootElement();
        int lines = root.getElementCount();
        int[] numbers = new int[lines];
        int number = 0;
        Segment segment = new Segment();
        for (int i = 0; i < lines; i++) {
            Element line = root.getElement(i);
            int start = line.getStartOffset();
            int end = Math.max(start, line.getEndOffset() - 1);
            boolean blank = true;
            try {
                if (end > start) {
                    // Reading through the document's own segment avoids copying the whole document
                    // on every keystroke.
                    document.getText(start, end - start, segment);
                    for (int k = segment.offset; k < segment.offset + segment.count; k++) {
                        if (!Character.isWhitespace(segment.array[k])) {
                            blank = false;
                            break;
                        }
                    }
                }
            } catch (BadLocationException | RuntimeException e) {
                blank = false;
            }
            numbers[i] = blank ? 0 : ++number;
        }
        gutter.setLineNumberFormatter(new PrettifyLineNumberFormatter(numbers, number));
        return number;
    }

    /**
     * Coalesces the gutter rebuild for a burst of document events (and for a single edit that
     * produces insert+remove events) into one rebuild once the current event has been handled.
     */
    private void scheduleEditableNumbering() {
        if (editableNumberingPending) {
            return;
        }
        editableNumberingPending = true;
        SwingUtilities.invokeLater(
                () -> {
                    if (!editableNumberingPending) {
                        return;
                    }
                    if (editable) {
                        updateEditableNumbering();
                    } else {
                        // The view became read-only while the rebuild was queued; clear the flag so
                        // a later editable install can schedule again.
                        editableNumberingPending = false;
                    }
                });
    }

    /**
     * Wrap width predicted from the current viewport and the line-number gutter the current
     * document has, or 0 while either is unknown. Using the current extent directly would size the
     * split for the previous message's gutter (its digit count differs). The digit count is taken
     * from the installed document rather than from a full scan of the new message: crossing a digit
     * boundary is corrected by {@link #correctWrapWidth} after layout, which is much cheaper than
     * scanning a potentially huge message on the Event Dispatch Thread. The vertical scrollbar is
     * always shown, so the extent minus the gutter is the only changing part.
     */
    private int predictedWrapWidthPx() {
        int extentWidth = scrollPane.getViewport().getExtentSize().width;
        Gutter gutter = scrollPane.getGutter();
        int currentGutterWidth = gutter == null ? 0 : gutter.getWidth();
        if (extentWidth <= 0 || currentGutterWidth <= 0) {
            return 0;
        }
        int digits = installedDigits > 0 ? installedDigits : 1;
        int gutterWidth = predictedGutterWidth(digits);
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
     * widths instead. The table is shared by every view using the same font and filled lazily, so a
     * view that never shows a wide character does not pay for a large table.
     */
    private DisplayTextSplitter.CharWidths charWidths() {
        Font font = textArea.getFont();
        if (font == null) {
            return null;
        }
        synchronized (CHAR_WIDTHS) {
            FontCharWidths cached = CHAR_WIDTHS.get(font);
            if (cached != null) {
                return cached;
            }
            // RSTA paints with fractional font metrics enabled (see PrettifyTextArea), so the
            // advance per character is the fractional one, which can differ from the rounded
            // FontMetrics charWidth() by a noticeable fraction. Measure with the same fractional
            // context the painter uses, otherwise a row is broken a few characters before the
            // right edge.
            FontMetrics metrics = textArea.getFontMetrics(font);
            FontRenderContext frc = new FontRenderContext(null, true, true);
            double monospaceAdvance = monospaceAdvance(font, frc);
            // A tab advances to the next tab stop, i.e. at most TAB_SIZE columns; upper-bound it so
            // a tab can never make a row overflow.
            double tabWidth =
                    (monospaceAdvance > 0 ? monospaceAdvance : metrics.charWidth(' '))
                            * PrettifyTextArea.TAB_SIZE;
            FontCharWidths measured =
                    new FontCharWidths(font, frc, metrics, monospaceAdvance, tabWidth);
            CHAR_WIDTHS.put(font, measured);
            return measured;
        }
    }

    /** The measured widths of one font, with lazily-grown tables. */
    private static final class FontCharWidths implements DisplayTextSplitter.CharWidths {

        private final Font font;
        private final FontRenderContext frc;
        private final FontMetrics metrics;
        private final double monospaceAdvance;
        private final double tabWidth;

        /**
         * Whether every printable ASCII character is exactly {@link #monospaceAdvance} wide. The
         * wrap loop asks for a width per character; for such a font the answer needs neither a
         * table lookup nor the (shared) lock, which keeps splitting an HTTP message contention-free.
         */
        private final boolean fastAscii;

        /** Widths for code points below {@link #cacheLimit}; grown only as wide characters appear. */
        private double[] cache = new double[0x100];

        private int cacheLimit = 0x100;

        /** Widths for supplementary code points (and any code point above {@link #cacheLimit}). */
        private final Map<Integer, Double> wide = new HashMap<>();

        FontCharWidths(
                Font font,
                FontRenderContext frc,
                FontMetrics metrics,
                double monospaceAdvance,
                double tabWidth) {
            this.font = font;
            this.frc = frc;
            this.metrics = metrics;
            this.monospaceAdvance = monospaceAdvance;
            this.tabWidth = tabWidth;
            this.fastAscii = isFastAscii(font, metrics, monospaceAdvance);
        }

        /**
         * Whether the printable ASCII range ({@code 0x20} to {@code 0x7E}) renders at exactly the
         * monospace advance. Same rule {@link #measuredWidth} applies, checked once for the range.
         */
        private static boolean isFastAscii(Font font, FontMetrics metrics, double monospaceAdvance) {
            if (monospaceAdvance <= 0) {
                return false;
            }
            int expected = (int) Math.round(monospaceAdvance);
            for (int codePoint = 0x20; codePoint <= 0x7E; codePoint++) {
                if (!font.canDisplay(codePoint)
                        || Math.abs(metrics.charWidth(codePoint) - expected) > 1) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public double widthOf(int codePoint) {
            if (fastAscii && codePoint >= 0x20 && codePoint <= 0x7E) {
                // No lookup and no lock: immutable state only touched here, so the wrap loop
                // never contends on the shared table for an ordinary message.
                return monospaceAdvance;
            }
            return widthOfMeasured(codePoint);
        }

        private synchronized double widthOfMeasured(int codePoint) {
            if (codePoint == '\t') {
                return tabWidth;
            }
            if (codePoint >= 0 && codePoint < cacheLimit) {
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
                // Small BMP code points are cheaper in the array; grow it only when one appears.
                if (codePoint >= cacheLimit && codePoint < 0x10000 && codePoint < cacheLimit * 8) {
                    int grown = Math.min(0x10000, Math.max(codePoint + 1, cacheLimit * 2));
                    cache = Arrays.copyOf(cache, grown);
                    cacheLimit = grown;
                }
            }
            return w;
        }
    }

    /**
     * The font's exact per-character advance when it is monospaced, or -1 otherwise. RSTA rounds the
     * width of a whole token run, so this (rather than the rounded {@code charWidth}) is the width a
     * row really paints. The monospace test is the shared {@link PrettifyTextArea#isMonospaced} - the
     * font selection, the text area's own advance and this table all accept the same fonts.
     */
    private static double monospaceAdvance(Font font, FontRenderContext frc) {
        return PrettifyTextArea.isMonospaced(font)
                ? font.createGlyphVector(frc, "M").getGlyphPosition(1).getX()
                : -1;
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
        if (editable || changed || !textArea.isWordWrapConfigured()) {
            // The user is editing: re-splitting would replace the text they are working on.
            // Without wrap there is no viewport width to conform to either.
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
        // The text area keeps its own copy of the raw message for selection-to-received-text
        // mapping; an editable view is not recompiled here, so mirror the saved raw manually to
        // keep the two in step (a reinstall would do it, but a body view keeps its document).
        textArea.setMessageText(rawText);
        displaySource = edited;
        rememberBody();
        // The body is in the message being saved now, so the request gets the content type it
        // has. This is the only path that writes the header: showing or refreshing the editable
        // view never rewrites the message.
        boolean headerChanged = setContentTypeIfMissing();
        if (headerChanged && target == Target.REQUEST_ALL) {
            // The All view shows the header block, so the added type has to be rendered. The
            // body view is unaffected by a header-only change and keeps its document (and undo
            // history) instead of being rebuilt after every send.
            pendingCaretCount =
                    TextAlignment.nonWhitespaceBefore(edited, textArea.getCaretPosition());
            pendingScrollValue = scrollPane.getVerticalScrollBar().getValue();
            updateInternal();
        } else if (sessionDocument != null && textArea.getDocument() == sessionDocument) {
            // The core plain text views re-read the model into their text area on the
            // notification of the save itself; the notification of this save is swallowed (the
            // saving flag, see dataChanged), so the re-read is done here directly. The view
            // already shows the text that was saved, so the render adds nothing - no state and
            // no undo step round the send.
            fillSession();
            changed = false;
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
            // The edit session ends with the editability (RTextArea installs a fresh undo manager
            // for the document of the read-only view anyway): a view that becomes editable again
            // starts a new session instead of carrying one that is not behind the document shown.
            sessionDocument = null;
            sessionStyle = null;
        }
        if (model.getMessage() != null) {
            // The rendering mode depends on whether the view is editable. The message itself is
            // not touched here: a Content-Type the body implies is only written when the message
            // is saved (see save()), so opening or refreshing an editable view never edits it.
            update();
        }
    }

    /**
     * Keeps the horizontal scrollbar out of the way while the rows cannot exceed the viewport:
     * a read-only view with wrap on is pre-split to fit it, an editing view with wrap on
     * soft-wraps. When wrap is off, in either mode, a row can run past the viewport, so the
     * scrollbar is needed.
     */
    private void applyWrapPolicy() {
        boolean needed = !textArea.isWordWrapConfigured();
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
        updateContentType = fileConfiguration.getBoolean(configurationKey + "updatecontenttype", true);
        formatEnabled = fileConfiguration.getBoolean(configurationKey + "format", true);
        highlightEnabled = fileConfiguration.getBoolean(configurationKey + "highlight", true);
        textArea.setSyntaxHighlightingEnabled(highlightEnabled);
        applyWrapPolicy();
    }

    @Override
    public void saveConfiguration(FileConfiguration fileConfiguration) {
        textArea.saveConfiguration(configurationKey, fileConfiguration);
        fileConfiguration.setProperty(configurationKey + "updatecontenttype", updateContentType);
        fileConfiguration.setProperty(configurationKey + "format", formatEnabled);
        fileConfiguration.setProperty(configurationKey + "highlight", highlightEnabled);
    }

    @Override
    public void dataChanged(HttpPanelViewModelEvent e) {
        if (saving || adjustingBody) {
            return;
        }
        undoZapBodyChange();
        String data = model.getData();
        if (data == null) {
            data = "";
        }
        if (data.equals(rawText)
                && editable
                && sessionDocument != null
                && textArea.getDocument() == sessionDocument
                && model.getMessage() == shownMessage) {
            // The model still holds the text the session document shows, and shows the message
            // the session is about. The view is refreshed with what it shows anyway (like the
            // plain text views re-read their document on every notification); when the text is
            // what the document already has, the refresh records nothing - there is no state to
            // step back to in it. A different message goes through the install, which starts the
            // session over so nothing of the earlier session resurfaces. A whole re-render would
            // rebuild the split and the scroll state around the same document; the fill of the
            // session document is all the notification adds to the view.
            fillSession();
            changed = false;
            rememberBody();
            return;
        }
        update();
    }

    /**
     * Gives a request being saved that carries a body the content type it has: the type is taken
     * from the body, so that a request sent with a JSON body says that it is JSON. The header is
     * only ever written on this save path - merely opening or refreshing an editable view never
     * mutates the message.
     *
     * @return whether the request header was changed
     */
    private boolean setContentTypeIfMissing() {
        if (!editable) {
            return false;
        }
        if (!updateContentType) {
            // The view's pop-up toggle turns the filler off: the message keeps exactly the headers
            // it has, with no Content-Type added on save.
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
                if (model.getMessage() == lastShownMessage) {
                    String remembered = lastShownBody;
                    boolean methodChanged = !method.equals(lastShownMethod);
                    if (!changed
                            && !remembered.isEmpty()
                            && !body.equals(remembered)
                            && (methodChanged || body.equals(remembered + "="))) {
                        // ZAP moved the body into the query string and back again: the body it
                        // puts back is not the body that was there (it re-encodes a parameter
                        // without a value as "name=").
                        setBodyOf(data, remembered);
                        if (methodChanged && !lastShownQuery.isEmpty()) {
                            // The query ZAP moves back into the body is not only the body that was
                            // moved out: it takes the parameters that were in the URL before (for
                            // example a key in the query string of an API endpoint) with it, so the
                            // query that was there before is put back.
                            setQueryOf(lastShownQuery);
                        }
                        data = model.getData();
                    }
                    rememberBody();
                }
                // A different message is being shown: the body the pair carries belongs to another
                // request and the change of the method (for all that is known here) happened while
                // another message was shown. Its install re-pairs the state with this message, so
                // nothing is restored from an earlier one here.
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
        if (model.getMessage() != lastShownMessage) {
            // A different message is being shown: what was remembered for the previous request is
            // not this one's. The reset also stores an empty body, so that the next request without
            // a body (for example a GET) does not get the previous request's body back from here
            // when the verb is changed to one that carries a body.
            lastShownMessage = model.getMessage();
            lastShownBody = body == null ? "" : body;
            lastShownQuery = queryOf();
            lastShownMethod = methodOf(model.getData());
            return;
        }
        if (body == null || body.isEmpty()) {
            return;
        }
        lastShownBody = body;
        lastShownQuery = queryOf();
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

    /**
     * The query string of the request URI, escaped as the URI shows it, or an empty string when
     * this view does not show a request or the URI has no query.
     *
     * <p>The escaped form is kept so that putting it back reproduces the query exactly; the decoded
     * form would be re-encoded (a second time by ZAP when it moves the query back into the body)
     * and encode characters the way ZAP does, not the way the request had them.
     */
    private String queryOf() {
        URI uri = uriOf();
        return uri == null ? "" : nullToEmpty(uri.getEscapedQuery());
    }

    /** Writes the query string of the request URI back into the message header. */
    private void setQueryOf(String escapedQuery) {
        URI uri = uriOf();
        if (uri == null) {
            return;
        }
        try {
            uri.setEscapedQuery(escapedQuery);
        } catch (URIException e) {
            LOGGER.warn("Could not put back the query string of the request.", e);
        }
    }

    /** The request URI the message carries, or {@code null} when this view shows none. */
    private URI uriOf() {
        if (target != Target.REQUEST_BODY && target != Target.REQUEST_ALL) {
            return null;
        }
        Message message = model.getMessage();
        if (!(message instanceof HttpMessage)) {
            return null;
        }
        HttpRequestHeader header = ((HttpMessage) message).getRequestHeader();
        return header == null ? null : header.getURI();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
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
        TextAlignment.Mapper mapper = TextAlignment.mapper(display, raw);
        if (mapper == null) {
            // The view is showing something that does not align with the raw message (for example
            // while a new document is being prepared); report the matches as they are.
            reportUnmappedMatches(pattern, display, matches);
            return;
        }
        boolean combined = target == Target.REQUEST_ALL || target == Target.RESPONSE_ALL;
        // The separator is the same for every match, so find it once instead of once per match.
        int bodyStart = combined ? bodyStartInLf(raw) : 0;
        Matcher matcher = pattern.matcher(display);
        while (matcher.find()) {
            int start = mapper.displayToRaw(matcher.start());
            int end = mapper.displayToRawEnd(matcher.end());
            if (start < 0 || end < 0 || start >= end) {
                continue;
            }
            SearchMatch.Location location = locationFor(start, bodyStart);
            if (location == null) {
                continue;
            }
            if (isHeaderLocation(location)) {
                int headerEnd = combined ? bodyStart : raw.length();
                int clampedEnd = Math.min(end, headerEnd);
                matches.add(new SearchMatch(location, start, clampedEnd));
            } else {
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
        TextAlignment.Mapper mapper = TextAlignment.mapper(display, raw);
        if (mapper == null) {
            return;
        }
        int displayStart = mapper.rawToDisplay(sourceStart);
        int displayEnd = mapper.rawToDisplayEnd(sourceEnd);
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
    private SearchMatch.Location locationFor(int lfOffset, int bodyStart) {
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
                return lfOffset < bodyStart
                        ? SearchMatch.Location.REQUEST_HEAD
                        : SearchMatch.Location.REQUEST_BODY;
            case RESPONSE_ALL:
                return lfOffset < bodyStart
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
