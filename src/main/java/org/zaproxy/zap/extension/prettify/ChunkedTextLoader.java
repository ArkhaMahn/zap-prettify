package org.zaproxy.zap.extension.prettify;

import java.util.function.BooleanSupplier;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
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

    /** Number of characters inserted and tokenised per slice. */
    private static final int SLICE_CHARS = 262144;

    private ChunkedTextLoader() {}

    /**
     * Builds a syntax document for {@code text} with {@code styleKey}, checking {@code cancelled}
     * between slices. Returns {@code null} if the build was cancelled or failed; the caller must then
     * leave the existing document in place.
     */
    public static RSyntaxDocument buildDocument(
            String text,
            String styleKey,
            DisplayTextSplitter.Split split,
            BooleanSupplier cancelled) {
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
        PrettifyDocument document = new PrettifyDocument(factory, style, split);
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
            return null;
        }
        if (cancelled != null && cancelled.getAsBoolean()) {
            return null;
        }
        document.prepareScriptLines();
        // Resolve the HTTP continuation-aware line states here, off the EDT, so painting never walks
        // a large document.
        document.precomputeLineStates();
        return document;
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
