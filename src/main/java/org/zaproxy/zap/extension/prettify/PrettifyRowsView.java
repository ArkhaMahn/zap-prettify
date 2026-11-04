package org.zaproxy.zap.extension.prettify;

import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.text.Element;
import javax.swing.text.PlainDocument;
import javax.swing.text.View;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxView;

/**
 * The unwrapped view of a pre-split document, with the one cost the stock {@link SyntaxView}
 * imposes on a document swap removed: after a swap the stock view measures the width of
 * every row once to find the widest one, and it is almost all of a large message's layout
 * pass on the EDT. This view reads the widest row's width from the document instead,
 * measured off the EDT while the render pipeline prepared the rows (see
 * {@link ChunkedTextLoader}).
 *
 * <p>Everything else - painting only the rows the clip asks for, caret and selection mapping,
 * tab stops, layered highlights - is the stock behaviour of {@link SyntaxView}, which paints
 * visible rows only and maps positions per row already.
 *
 * <p>The private state this view sets is that of RSyntaxTextArea 3.6.0's {@link SyntaxView},
 * which is a plain classpath library without module boundaries, so scoped reflection is the
 * only access there is. Should a build resolve none of it, the view falls back to the stock
 * behaviour completely: slower, never wrong.
 */
public class PrettifyRowsView extends SyntaxView {

    /**
     * The private fields of {@link SyntaxView} this view sets to the same values the stock
     * size update would set, minus the widest-row scan. Resolved once; a failure means the
     * stock (slower) behaviour is used, never a wrong one.
     */
    private static final Field FONT_FIELD;

    private static final Field METRICS_FIELD;

    private static final Field TAB_SIZE_FIELD;

    private static final Field HOST_FIELD;

    private static final Method LONGEST_LINE_METHOD;

    private static final boolean FIELDS_RESOLVED;

    /** The rendered EOL marker, exactly like {@code SyntaxView}'s own. */
    private static final String EOL_MARKER = "\u00B6";

    static {
        Field font = null;
        Field metrics = null;
        Field tabSize = null;
        Field host = null;
        Method longestLine = null;
        boolean resolved = false;
        try {
            font = SyntaxView.class.getDeclaredField("font");
            metrics = SyntaxView.class.getDeclaredField("metrics");
            tabSize = SyntaxView.class.getDeclaredField("tabSize");
            host = SyntaxView.class.getDeclaredField("host");
            longestLine = SyntaxView.class.getDeclaredMethod("calculateLongestLine");
            font.setAccessible(true);
            metrics.setAccessible(true);
            tabSize.setAccessible(true);
            host.setAccessible(true);
            longestLine.setAccessible(true);
            resolved = true;
        } catch (Exception e) {
            // Fields not resolvable in this RSyntaxTextArea build: only the stock slow path.
        }
        FONT_FIELD = font;
        METRICS_FIELD = metrics;
        TAB_SIZE_FIELD = tabSize;
        HOST_FIELD = host;
        LONGEST_LINE_METHOD = longestLine;
        FIELDS_RESOLVED = resolved;
    }

    public PrettifyRowsView(Element elem) {
        super(elem);
    }

    /** Whether the stock widest-row scan already ran here (see the no-measurement fallback). */
    private boolean longestMeasured;

    @Override
    public void setSize(float width, float height) {
        if (!FIELDS_RESOLVED) {
            // Not resolvable: the stock update (with its widest-row scan) keeps correctness.
            super.setSize(width, height);
            return;
        }
        // The stock update refreshes the cached font and measures every row's width once when
        // the font changed - and a fresh view (created per document swap) never measured
        // anything, so every swap pays the scan. Setting exactly what the stock refresh sets,
        // minus the scan, keeps every consumer (paint, line mapping, tab stops) correct.
        if (getContainer() instanceof RSyntaxTextArea) {
            RSyntaxTextArea host = (RSyntaxTextArea) getContainer();
            Font font = host.getFont();
            FontMetrics metrics = host.getFontMetrics(font);
            Integer tabSizeProperty =
                    (Integer) getDocument().getProperty(PlainDocument.tabSizeAttribute);
            int tabs = tabSizeProperty == null ? 5 : tabSizeProperty;
            int tabPx = tabs * metrics.charWidth(' ');
            set(HOST_FIELD, this, host);
            set(FONT_FIELD, this, font);
            set(METRICS_FIELD, this, metrics);
            set(TAB_SIZE_FIELD, this, tabPx);
        }
    }

    @Override
    public float getPreferredSpan(int axis) {
        if (axis == View.X_AXIS) {
            float maxRowWidth = maxRowWidthPx();
            if (maxRowWidth >= 0f) {
                // Like the stock span: the widest row, the right-hand-side breathing room, and
                // the EOL marker when it is drawn.
                float span = maxRowWidth + rhsCorrection();
                if (getContainer() instanceof RSyntaxTextArea
                        && ((RSyntaxTextArea) getContainer()).getEOLMarkersVisible()) {
                    span += eolMarkerWidth((RSyntaxTextArea) getContainer());
                }
                return span;
            }
            // No measurement for the rows as they are now: the stock behaviour needs the widest
            // row, and updateMetrics (which the stock span calls) only re-measures on a font
            // change - which will not have happened here. Measure once, like the stock size
            // update would have, so the span is exact and never falls back to a stale one; the
            // stock edit handling then keeps the widest row up to date row by row.
            if (!longestMeasured) {
                longestMeasured = true;
                measureLongestLine();
            }
        }
        return super.getPreferredSpan(axis);
    }

    /**
     * Runs the stock widest-row scan once, so a view with no usable measurement answers exactly
     * like the stock view. Never runs on the fast path, which reads the measured width instead.
     */
    private void measureLongestLine() {
        if (FIELDS_RESOLVED && LONGEST_LINE_METHOD != null) {
            try {
                LONGEST_LINE_METHOD.invoke(this);
            } catch (Exception e) {
                // Falls through to the stock span, which derives what it can of its fields.
            }
        }
    }

    /**
     * The widest row's painted width in document terms is not discoverable here: the measurement
     * belongs to the rows as they were rendered (see {@link ChunkedTextLoader}), and an edited
     * document's rows have moved past it - exactly when the stock measurement has to run, below.
     */
    private float maxRowWidthPx() {
        if (getDocument() instanceof PrettifyDocument) {
            PrettifyDocument document = (PrettifyDocument) getDocument();
            if (!document.isEdited()) {
                return document.maxRowWidthPx();
            }
        }
        return -1f;
    }

    /** Workaround like the stock one: the caret is otherwise rendered fully off-screen. */
    private float rhsCorrection() {
        Component container = getContainer();
        if (container instanceof RSyntaxTextArea) {
            return ((RSyntaxTextArea) container).getRightHandSideCorrection();
        }
        return 10;
    }

    private float eolMarkerWidth(RSyntaxTextArea textArea) {
        FontMetrics metrics = textArea.getFontMetrics(textArea.getFont());
        return metrics.stringWidth(EOL_MARKER);
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            // Cannot happen into a resolved field; leaving the view stateless is safe because
            // paint and every stock consumer re-derive or guard their fields.
        }
    }
}
