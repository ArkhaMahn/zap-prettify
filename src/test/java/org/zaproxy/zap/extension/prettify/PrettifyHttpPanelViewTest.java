package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import org.junit.jupiter.api.Test;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.zaproxy.zap.extension.httppanel.view.AbstractStringHttpPanelViewModel;

class PrettifyHttpPanelViewTest {

    private static final String HEADER =
            "POST http://example.com/ HTTP/1.1\r\n"
                    + "Host: example.com\r\n"
                    + "Content-Type: application/json\r\n"
                    + "\r\n";

    @Test
    void undoSurvivesSavingAndADataChangedNotification() throws Exception {
        HttpMessage message = new HttpMessage(new HttpRequestHeader(HEADER));
        message.setRequestBody("{\"a\":1}");
        BodyModel model = new BodyModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_BODY, model);
        PrettifyTextArea area = textAreaOf(view);

        // The view renders off the EDT; wait for the first document before making it editable.
        waitUntil(() -> !area.getText().isEmpty());
        Document readOnlyDocument = area.getDocument();
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getDocument() != readOnlyDocument);

        int caret = area.getText().indexOf('1') + 1;
        SwingUtilities.invokeAndWait(
                () -> {
                    area.setCaretPosition(caret);
                    area.replaceSelection("9");
                    view.save();
                });
        assertEquals("{\"a\":19}", model.getData());
        assertTrue(area.canUndo());

        // A dataChanged notification for the same message must not rebuild the document.
        SwingUtilities.invokeAndWait(() -> view.dataChanged(null));
        assertTrue(area.canUndo(), "save/dataChanged discarded the undo history");

        // Undo returns the request to the state before the edit, and sending again persists it.
        SwingUtilities.invokeAndWait(area::undoLastAction);
        SwingUtilities.invokeAndWait(view::save);
        assertEquals("{\"a\":1}", model.getData());
    }

    private static PrettifyTextArea textAreaOf(PrettifyHttpPanelView view) throws Exception {
        Field field = PrettifyHttpPanelView.class.getDeclaredField("textArea");
        field.setAccessible(true);
        return (PrettifyTextArea) field.get(view);
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            boolean[] ready = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) {
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for the view to render");
            }
            Thread.sleep(10);
        }
    }

    private static final class BodyModel extends AbstractStringHttpPanelViewModel {

        @Override
        public String getData() {
            return message instanceof HttpMessage
                    ? ((HttpMessage) message).getRequestBody().toString()
                    : "";
        }

        @Override
        public void setData(String data) {
            if (message instanceof HttpMessage) {
                ((HttpMessage) message).setRequestBody(data);
            }
        }
    }
}
