package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.fife.ui.rtextarea.LineNumberFormatter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestStringHttpPanelViewModel;

/**
 * Stage-by-stage timings of the read-only render pipeline at several payload sizes, cold and warm,
 * plus what a repeated render of the message that is already shown costs through the whole view.
 * Diagnostic only: the printed numbers are the point; kept free of assertions beyond trivial
 * correctness so the suite stays deterministic.
 */
class RenderingBenchTest {

    private static final int VIEW_WIDTH = 800;

    @Test
    void stageTimingsAtSeveralPayloadSizes() {
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        DisplayTextSplitter.CharWidths widths = widths(font);

        Object[][] payloads = {
            {"json-0.5M", jsonPayload(500_000), "application/json"},
            {"json-2M", jsonPayload(2_000_000), "application/json"},
            {"js-0.5M", jsPayload(500_000), "text/javascript"},
            {"js-2M", jsPayload(2_000_000), "text/javascript"},
            {"line-256K", linePayload(256_000), "text/plain"},
            {"line-2M", linePayload(2_000_000), "text/plain"},
        };

        for (int rep = 0; rep < 2; rep++) {
            for (Object[] payload : payloads) {
                String label = (String) payload[0];
                String raw = (String) payload[1];
                String contentType = (String) payload[2];
                String style = ContentType.styleKeyFor(contentType, raw);
                System.out.println(
                        "== " + (rep == 0 ? "cold" : "warm") + " " + label + " (" + raw.length() + " chars)");

                String display = time("format", () -> PrettyPrint.formatMessageFor(contentType, raw, true));
                time("sameNonWhitespace", () -> TextAlignment.sameNonWhitespace(raw, display));
                DisplayTextSplitter.Split split =
                        time(
                                "split",
                                () -> DisplayTextSplitter.split(display, style, VIEW_WIDTH, widths));
                System.out.println("    rows=" + countRows(split.displayText()));
                time("lineNumbers", () -> LineNumbers.forDisplay(raw, split.displayText()));
                RSyntaxDocument document =
                        time(
                                "document build",
                                () ->
                                        ChunkedTextLoader.buildDocument(
                                                split.displayText(), style, split, () -> false, false));

                RSyntaxTextArea proxy = new RSyntaxTextArea();
                proxy.setSyntaxEditingStyle(style);
                proxy.setLineWrap(false);
                proxy.setFont(font);
                proxy.setDocument(document);
                clearEditorMetricsFlag(proxy);
                long start = System.nanoTime();
                proxy.setSize(proxy.getPreferredSize());
                BufferedImage canvas =
                        new BufferedImage(VIEW_WIDTH, 700, BufferedImage.TYPE_INT_ARGB);
                proxy.paint(canvas.createGraphics());
                System.out.printf(
                        "layout+paint %-4s %d ms%n", "", (System.nanoTime() - start) / 1_000_000);
                proxy.setDocument(new RSyntaxDocument("text/plain"));
            }
        }

        assertTrue(true);
    }

    /**
     * What a render costs through the whole view, once and again for the message that is already
     * shown (the case ZAP hits the most, and the case the caches optimize away).
     */
    @Test
    void wholeViewRepeatRenderCosts() throws Exception {
        String header =
                "POST http://example.com/ HTTP/1.1\r\n"
                        + "Host: example.com\r\n"
                        + "Content-Type: application/javascript\r\n"
                        + "\r\n";
        HttpMessage message = new HttpMessage(new HttpRequestHeader(header));
        message.setRequestBody(jsPayload(1_000_000));
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);
        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "bench",
                        "bench",
                        "application/javascript",
                        PrettifyHttpPanelView.Target.REQUEST_ALL,
                        model);
        javax.swing.JTextArea plain = textAreaOf(view);

        long start = System.nanoTime();
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (plain.getText().isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("first render never finished");
            }
            Thread.sleep(10);
        }
        System.out.printf(
                "%-24s %5d ms%n",
                "first render 1M js", (System.nanoTime() - start) / 1_000_000);

        // Five echoes of the message that is already shown: each one is a render, and its one
        // observable heartbeat is the gutter formatter the install re-creates.
        LineNumberFormatter[] seen = new LineNumberFormatter[1];
        seen[0] = formatterOf(view);
        long repeatStart = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            final PrettifyHttpPanelView v = view;
            SwingUtilities.invokeAndWait(() -> v.dataChanged(null));
            long stepDeadline = System.nanoTime() + 60_000_000_000L;
            LineNumberFormatter previous = seen[0];
            while (seen[0] == previous) {
                seen[0] = formatterOf(view);
                if (System.nanoTime() > stepDeadline) {
                    throw new AssertionError("repeat render " + i + " never finished");
                }
                Thread.sleep(5);
            }
        }
        long repeats = (System.nanoTime() - repeatStart) / 1_000_000;
        System.out.printf("%-24s %5d ms (%d repeats, %d ms each)%n",
                "repeat render 1M js", repeats, 5, repeats / 5);

        // A changed message is the contrast: no cache key matches, the whole pipeline runs again.
        LineNumberFormatter previous = seen[0];
        changeBody(message, jsPayload(1_000_000) + "// altered\n", view);
        long changedStart = System.nanoTime();
        long stepDeadline = System.nanoTime() + 60_000_000_000L;
        while (seen[0] == previous) {
            seen[0] = formatterOf(view);
            if (System.nanoTime() > stepDeadline) {
                throw new AssertionError("changed render never started");
            }
            Thread.sleep(5);
        }
        // ...and completes. The wait started before the render was even prepared.
        long changed = (System.nanoTime() - changedStart) / 1_000_000;
        System.out.printf("%-24s %5d ms%n", "changed render 1M js", changed);
        assertTrue(true);
    }

    private static void changeBody(HttpMessage message, String body, PrettifyHttpPanelView view)
            throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    message.setRequestBody(body);
                    view.dataChanged(null);
                });
    }

    private static PrettifyTextArea textAreaOf(PrettifyHttpPanelView view) {
        try {
            Field field = PrettifyHttpPanelView.class.getDeclaredField("textArea");
            field.setAccessible(true);
            return (PrettifyTextArea) field.get(view);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LineNumberFormatter formatterOf(PrettifyHttpPanelView view) {
        try {
            Field field = PrettifyHttpPanelView.class.getDeclaredField("scrollPane");
            field.setAccessible(true);
            RTextScrollPane scrollPane = (RTextScrollPane) field.get(view);
            if (SwingUtilities.isEventDispatchThread()) {
                return scrollPane.getGutter().getLineNumberFormatter();
            }
            LineNumberFormatter[] formatter = new LineNumberFormatter[1];
            SwingUtilities.invokeAndWait(
                    () -> formatter[0] = scrollPane.getGutter().getLineNumberFormatter());
            return formatter[0];
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** RSTA's first paint refreshes font metrics through component.getGraphics(), which is null
     * for a peerless component; painting into a buffer remains legitimate once we skip that. */

    private static void clearEditorMetricsFlag(RSyntaxTextArea proxy) {
        try {
            java.lang.reflect.Field flag =
                    org.fife.ui.rsyntaxtextarea.RSyntaxTextArea.class.getDeclaredField(
                            "metricsNeverRefreshed");
            flag.setAccessible(true);
            flag.setBoolean(proxy, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int countRows(String text) {
        int rows = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                rows++;
            }
        }
        return rows;
    }

    private static <T> T time(String label, Supplier<T> run) {
        long start = System.nanoTime();
        T result = run.get();
        System.out.printf("%-20s %d ms%n", label, (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    private static DisplayTextSplitter.CharWidths widths(Font font) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontMetrics metrics = probe.createGraphics().getFontMetrics(font);
        double advance = metrics.charWidth('m');
        boolean probed = advance > 0;
        for (int c = 0x20; c <= 0x7E && probed; c++) {
            probed = font.canDisplay(c) && Math.abs(metrics.charWidth(c) - Math.round(advance)) <= 1;
        }
        final boolean fastAscii = probed;
        Map<Integer, Double> cache = new HashMap<>();
        return codePoint -> {
            if (fastAscii && codePoint >= 0x20 && codePoint <= 0x7E) {
                return advance;
            }
            return cache.computeIfAbsent(codePoint, cp -> (double) metrics.charWidth(cp));
        };
    }

    private static String jsonPayload(int targetSize) {
        StringBuilder out = new StringBuilder(targetSize + 64);
        out.append('{');
        int i = 0;
        while (out.length() < targetSize) {
            out.append("\"key").append(i).append("\":\"value ").append(i).append("\",");
            i++;
        }
        out.setLength(out.length() - 1);
        out.append('}');
        return out.toString();
    }

    private static String jsPayload(int targetSize) {
        String snippet = "function run(n){var a=n*2;return {alpha:1,beta:a};}\n";
        StringBuilder out = new StringBuilder(targetSize + snippet.length());
        while (out.length() < targetSize) {
            out.append(snippet);
        }
        return out.toString();
    }

    private static String linePayload(int targetSize) {
        return "x".repeat(targetSize);
    }
}
