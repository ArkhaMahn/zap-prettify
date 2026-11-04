package org.zaproxy.zap.extension.prettify;

import java.util.function.BooleanSupplier;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * Builds an {@link RSyntaxDocument} off the Event Dispatch Thread in fixed-size slices, so that a
 * very large payload never blocks the UI while its text is inserted and tokenised.
 *
 * <p>Inserting into an {@link RSyntaxDocument} tokenises the affected lines; doing that in slices on
 * a background thread keeps the EDT free, and the finished document is swapped in with a single
 * {@link JTextComponent#setDocument} call on the EDT (see {@link #installDocument}). The build is
 * cancellable so a superseded message does not waste work.
 *
 * <p>Adapted from the Pretty View add-on's {@code ChunkedTextLoader}.
 */
public final class ChunkedTextLoader {

    private static final Logger LOGGER = LogManager.getLogger(ChunkedTextLoader.class);

    /** Number of characters inserted and tokenised per slice. */
    private static final int SLICE_CHARS = 262144;

    private ChunkedTextLoader() {}

    /**
     * Builds a syntax document for {@code text} with {@code styleKey}, checking {@code cancelled}
     * between slices. Returns {@code null} if the build was cancelled or failed; the caller must then
     * leave the existing document in place.
     *
     * <p>An editable document is a plain one: the wrapped rows of a {@link PrettifyDocument} carry
     * the tokens of the unwrapped text, which does not survive an edit made in the text area.
     *
     * <p>The widest row is not measured (see the full build that takes {@link
     * DisplayTextSplitter.CharWidths widths}).
     */
    public static RSyntaxDocument buildDocument(
            String text,
            String styleKey,
            DisplayTextSplitter.Split split,
            BooleanSupplier cancelled,
            boolean editable) {
        return buildDocument(text, styleKey, split, null, cancelled, editable);
    }

    /**
     * Builds a syntax document for {@code text} with {@code styleKey}, checking {@code cancelled}
     * between slices. Returns {@code null} if the build was cancelled or failed; the caller must then
     * leave the existing document in place.
     *
     * <p>An editable document is a plain one: the wrapped rows of a {@link PrettifyDocument} carry
     * the tokens of the unwrapped text, which does not survive an edit made in the text area.
     *
     * <p>For {@code widths} given and an uneditable build, the widest row's painted width is
     * measured here (off the EDT) and attached to the document, so the view reads it instead of
     * measuring the width of every row once just to find the widest one.
     */
    public static RSyntaxDocument buildDocument(
            String text,
            String styleKey,
            DisplayTextSplitter.Split split,
            DisplayTextSplitter.CharWidths widths,
            BooleanSupplier cancelled,
            boolean editable) {
        String source = text == null ? "" : text;
        String style = styleKey == null ? ContentType.PLAIN_STYLE : styleKey;
        PrettifyTokenMakerFactory baseFactory = new PrettifyTokenMakerFactory();
        // The ContinuedStringTokenMaker wrapper is needed only for styles whose document token maker
        // parses strings directly. HTTP messages handle string continuation inside the body delegate
        // instead, and the wrapper would corrupt the carried body state, so it is not applied there.
        TokenMakerFactory factory =
                PrettifyTokenMakerFactory.isHttpStyle(style)
                        ? baseFactory
                        : new ContinuedStringTokenMakerFactory(baseFactory);
        // An editable document keeps the state-aware tokenization (an HTTP message is only
        // highlighted correctly when the header and body states are carried from line to line), but
        // not the replay of the recorded tokens: those describe the text as it was split and do not
        // survive an edit.
        RSyntaxDocument document =
                editable
                        ? new PrettifyDocument(factory, style, split, false)
                        : new PrettifyDocument(factory, style, split);
        // Set the tab size on the document before it is displayed: RSyntaxDocument defaults it to 5,
        // and changing it after the document is installed forces a full re-layout of the (possibly
        // huge) document on the EDT.
        document.putProperty("tabSize", PrettifyTextArea.TAB_SIZE);
        try {
            for (int i = 0; i < source.length(); i += SLICE_CHARS) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return null;
                }
                document.insertString(
                        document.getLength(),
                        source.substring(i, Math.min(source.length(), i + SLICE_CHARS)),
                        null);
            }
        } catch (RuntimeException | BadLocationException e) {
            LOGGER.warn("Could not build the document.", e);
            return null;
        }
        if (cancelled != null && cancelled.getAsBoolean()) {
            return null;
        }
        if (document instanceof PrettifyDocument) {
            PrettifyDocument prettifyDocument = (PrettifyDocument) document;
            if (!editable) {
                // The widest row is measured off the EDT (see maxRowWidth): the view then never
                // walks every row's glyphs once just to learn the width that spans draw.
                prettifyDocument.setMaxRowWidthPx(maxRowWidth(source, widths));
            }
            // From now on a change in the text area invalidates the recorded tokens.
            prettifyDocument.watchForEdits();
            prettifyDocument.prepareScriptLines();
            // Resolve the HTTP continuation-aware line states here, off the EDT, so painting never
            // walks a large document.
            prettifyDocument.precomputeLineStates();
        }
        return document;
    }

    /**
     * The widest row's painted width, in pixels: one width lookup per code point, with tabs
     * expanded to the next tab stop like the view's tab expander does. -1 when {@code widths} is
     * {@code null}. This mirrors what the painter would measure closely enough that the padding
     * the view adds covers the drift of a control character or a fractional rounding.
     */
    private static float maxRowWidth(String text, DisplayTextSplitter.CharWidths widths) {
        if (widths == null) {
            return -1f;
        }
        double tabStep = Math.max(1, PrettifyTextArea.TAB_SIZE) * widths.widthOf(' ');
        double rowWidth = 0;
        double max = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == '\n') {
                if (rowWidth > max) {
                    max = rowWidth;
                }
                rowWidth = 0;
            } else if (codePoint == '\t') {
                rowWidth = ((int) (rowWidth / tabStep) + 1) * tabStep;
            } else {
                rowWidth += widths.widthOf(codePoint);
            }
        }
        return (float) Math.max(max, rowWidth);
    }

    /**
     * Swaps {@code document} into {@code textArea} on the EDT and rewinds the caret to the top, then
     * runs {@code afterInstall}. Must be called on the Event Dispatch Thread.
     */
    public static void installDocument(
            JTextComponent textArea, RSyntaxDocument document, Runnable afterInstall) {
        if (textArea == null || document == null) {
            return;
        }
        textArea.setDocument(document);
        textArea.setCaretPosition(0);
        if (afterInstall != null) {
            afterInstall.run();
        }
    }
}
