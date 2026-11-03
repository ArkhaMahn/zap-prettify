package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.zaproxy.zap.extension.httppanel.view.AbstractStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestBodyStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestStringHttpPanelViewModel;
import org.zaproxy.zap.utils.ZapXmlConfiguration;

class PrettifyHttpPanelViewTest {

    private static final String HEADER =
            "POST http://example.com/ HTTP/1.1\r\n"
                    + "Host: example.com\r\n"
                    + "Content-Type: application/json\r\n"
                    + "\r\n";

    @Test
    void undoSurvivesSavingAndADataChangedNotification() throws Exception {
        HttpMessage message = new HttpMessage(new HttpRequestHeader(HEADER));
        message.setRequestBody("{\"a\":1,\"b\":2}");
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
        assertEquals("{\"a\":19,\"b\":2}", model.getData());
        assertTrue(area.canUndo());

        // The save's own notification is swallowed, and the state it would re-read is what the
        // view already shows - it is no state at all and adds nothing to the history. The user's
        // edit is the latest one on record: one undo takes it back, and the walk stops at the
        // message the view starts with - no empty text area ever shows up in it.
        SwingUtilities.invokeAndWait(area::undoLastAction);
        assertTrue(
                textOf(area).contains("\"a\": 1,\n  \"b\": 2"),
                "the state before the user's edit: [" + textOf(area) + "]");
        assertFalse(area.canUndo(), "the walk ends at the message the view starts with");

        // Redo takes the edit forward again, and the redone state is what sending persists.
        SwingUtilities.invokeAndWait(area::redoLastAction);
        assertTrue(textOf(area).contains("\"a\": 19"));
        SwingUtilities.invokeAndWait(view::save);
        assertEquals("{\"a\":19,\"b\":2}", model.getData());
    }

    @Test
    void addingAHeaderInTheAllViewKeepsItOnItsOwnLineAndInOrder() throws Exception {
        String header =
                "POST http://example.com/ HTTP/1.1\r\n"
                        + "Host: example.com\r\n"
                        + "X-Test: one\r\n"
                        + "X-Test: two\r\n"
                        + "X-Other: three\r\n"
                        + "Content-Type: application/json\r\n"
                        + "\r\n";
        HttpMessage message = new HttpMessage(new HttpRequestHeader(header));
        message.setRequestBody("{\"a\":1,\"b\":2}");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_ALL, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Document readOnlyDocument = area.getDocument();
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getDocument() != readOnlyDocument);

        SwingUtilities.invokeAndWait(
                () -> {
                    int separator = area.getText().indexOf("\n\n");
                    area.setCaretPosition(separator);
                    // Enter followed by a new header, as typed at the end of the header block.
                    area.replaceSelection("\nX-Test: four");
                    view.save();
                });

        assertEquals(
                List.of("one", "two", "four"), message.getRequestHeader().getHeaderValues("X-Test"));
        assertEquals(
                "POST http://example.com/ HTTP/1.1\r\n"
                        + "Host: example.com\r\n"
                        + "X-Test: one\r\n"
                        + "X-Test: two\r\n"
                        + "X-Other: three\r\n"
                        + "Content-Type: application/json\r\n"
                        + "X-Test: four\r\n"
                        + "\r\n",
                message.getRequestHeader().toString());
        assertEquals("{\"a\":1,\"b\":2}", message.getRequestBody().toString());
        assertTrue(area.getText().contains("\nX-Test: four\n\n"));
    }

    @Test
    void aMissingContentTypeIsOnlyAddedWhenTheMessageIsSaved() throws Exception {
        String header =
                "POST http://example.com/ HTTP/1.1\r\n"
                        + "Host: example.com\r\n"
                        + "X-Test: one\r\n"
                        + "\r\n";
        HttpMessage message = new HttpMessage(new HttpRequestHeader(header));
        message.setRequestBody("{\"a\":1}");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_ALL, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Document readOnlyDocument = area.getDocument();
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getDocument() != readOnlyDocument);

        // Merely opening the editable view (or a later refresh of it) must not touch the message.
        assertTrue(
                message.getRequestHeader().getHeaderValues("Content-Type").isEmpty(),
                "opening the editable view must not write a header: "
                        + message.getRequestHeader());

        // The edit-and-save flow is what fills the header in, from the body being sent.
        SwingUtilities.invokeAndWait(
                () -> {
                    String text = area.getText();
                    // The first "1" in the All view is the HTTP version; target the body value.
                    area.setCaretPosition(text.indexOf('1', text.indexOf("\"a\"")) + 1);
                    area.replaceSelection("9");
                    view.save();
                });
        assertEquals(
                List.of("application/json"),
                message.getRequestHeader().getHeaderValues("Content-Type"));
        assertTrue(
                message.getRequestBody().toString().startsWith("{\"a\":19"),
                "the saved body: " + message.getRequestBody());
    }

    @Test
    void theContentTypeToggleKeepsTheHeaderWhenSwitchedOff() throws Exception {
        String header =
                "POST http://example.com/ HTTP/1.1\r\n"
                        + "Host: example.com\r\n"
                        + "X-Test: one\r\n"
                        + "\r\n";
        HttpMessage message = new HttpMessage(new HttpRequestHeader(header));
        message.setRequestBody("{\"a\":1}");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_ALL, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Document readOnlyDocument = area.getDocument();
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getDocument() != readOnlyDocument);

        // The toggle starts on, and it is offered while the view is an editable request view.
        boolean[] on = new boolean[1];
        boolean[] applicable = new boolean[1];
        SwingUtilities.invokeAndWait(
                () -> {
                    PrettifyTextArea.Options options = area.options();
                    on[0] =
                            options != null
                                    && options.isOn(PrettifyTextArea.Option.UPDATE_CONTENT_TYPE);
                    applicable[0] =
                            options != null
                                    && options.isApplicable(
                                            PrettifyTextArea.Option.UPDATE_CONTENT_TYPE);
                });
        assertTrue(on[0] && applicable[0], "the filler-toggle paperwork: " + on[0] + " " + applicable[0]);

        // Toggled off, the save keeps the request exactly as it was made: no Content-Type appears,
        // while the edited body is still persisted.
        SwingUtilities.invokeAndWait(
                () -> area.options().setOn(PrettifyTextArea.Option.UPDATE_CONTENT_TYPE, false));
        SwingUtilities.invokeAndWait(
                () -> {
                    String text = area.getText();
                    area.setCaretPosition(text.indexOf('1', text.indexOf("\"a\"")) + 1);
                    area.replaceSelection("9");
                    view.save();
                });
        assertTrue(
                message.getRequestHeader().getHeaderValues("Content-Type").isEmpty(),
                "the filler must stay off: " + message.getRequestHeader());
        assertTrue(
                message.getRequestBody().toString().startsWith("{\"a\":19"),
                "the saved body: " + message.getRequestBody());

        // Toggled on again, the next save fills the header in from the body being sent.
        SwingUtilities.invokeAndWait(
                () -> area.options().setOn(PrettifyTextArea.Option.UPDATE_CONTENT_TYPE, true));
        SwingUtilities.invokeAndWait(
                () -> {
                    String text = area.getText();
                    int pos = text.indexOf('9', text.indexOf("\"a\""));
                    area.setCaretPosition(pos);
                    area.moveCaretPosition(pos + 1);
                    area.replaceSelection("8");
                    view.save();
                });
        assertEquals(
                List.of("application/json"),
                message.getRequestHeader().getHeaderValues("Content-Type"));
        assertTrue(
                message.getRequestBody().toString().startsWith("{\"a\":18"),
                "the saved body: " + message.getRequestBody());
    }

    @Test
    void theFormatToggleShowsTheReceivedBodyAndSwitchesBackLive() throws Exception {
        HttpMessage message = new HttpMessage(new HttpRequestHeader(HEADER));
        message.setRequestBody("{\"a\":1,\"b\":2}");
        BodyModel model = new BodyModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_BODY, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());

        // On by default: the body is pretty-printed.
        assertTrue(textOf(area).contains("\n"), "received: [" + textOf(area) + "]");

        // Off: the body is exactly the received bytes (no live edit is needed for the switch).
        SwingUtilities.invokeAndWait(() -> area.options().setOn(PrettifyTextArea.Option.FORMAT, false));
        waitUntil(() -> "{\"a\":1,\"b\":2}".equals(textOf(area)));

        // On again: the pretty layout comes back before anything is edited or saved.
        SwingUtilities.invokeAndWait(() -> area.options().setOn(PrettifyTextArea.Option.FORMAT, true));
        waitUntil(() -> textOf(area).contains("\n"));
    }

    @Test
    void pasteIsNotReformattedWhileTheFormatToggleIsOff() throws Exception {
        HttpMessage message = new HttpMessage(new HttpRequestHeader(HEADER));
        message.setRequestBody("{\"a\":1,\"b\":2}");
        BodyModel model = new BodyModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_BODY, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Method formatPastedText =
                PrettifyHttpPanelView.class.getDeclaredMethod("formatPastedText", String.class);
        formatPastedText.setAccessible(true);
        String pasted = "{\"a\": 3,\"b\":  4}";

        // Off: the paste is returned exactly as pasted. The view shows the raw body, so a
        // paste must not silently reformat behind the user's back.
        SwingUtilities.invokeAndWait(() -> area.options().setOn(PrettifyTextArea.Option.FORMAT, false));
        waitUntil(() -> "{\"a\":1,\"b\":2}".equals(textOf(area)));
        assertEquals(pasted, formatPastedText.invoke(view, pasted), "toggle off");

        // On: the pasted body is reformatted again, like the paste path normally does.
        SwingUtilities.invokeAndWait(() -> area.options().setOn(PrettifyTextArea.Option.FORMAT, true));
        waitUntil(() -> textOf(area).contains("\n"));
        assertNotEquals(pasted, formatPastedText.invoke(view, pasted), "toggle on");
    }

    @Test
    void theWrapToggleKeepsTheReceivedRowsAndMovesTheHorizontalScrollbar() throws Exception {
        HttpMessage message = new HttpMessage(new HttpRequestHeader(HEADER));
        message.setRequestBody("{\"a\":1}");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_ALL, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        RTextScrollPane scrollPane = scrollPaneOf(view);

        // Wrap on (the default): a read-only view is pre-split to the viewport, the scrollbar
        // would never reveal anything and stays out of the way.
        assertEquals(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                scrollPane.getHorizontalScrollBarPolicy(),
                "wrap on by default");

        // Off: the received rows are kept (the display matches the received text up to the
        // whitespace the pretty layout reworks) and the scrollbar appears for rows that can run
        // past the viewport.
        SwingUtilities.invokeAndWait(() -> area.options().setOn(PrettifyTextArea.Option.WRAP, false));
        waitUntil(() -> TextAlignment.sameNonWhitespace(model.getData(), textOf(area)));
        assertEquals(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                scrollPane.getHorizontalScrollBarPolicy(),
                "wrap off");

        // On again: the scrollbar goes back.
        SwingUtilities.invokeAndWait(() -> area.options().setOn(PrettifyTextArea.Option.WRAP, true));
        assertEquals(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                scrollPane.getHorizontalScrollBarPolicy(),
                "wrap on again");
    }

    @Test
    void theHighlightToggleDrawsEveryTokenInThePlainTextColour() throws Exception {
        HttpMessage message = new HttpMessage(new HttpRequestHeader(HEADER));
        message.setRequestBody("{\"a\":1}");
        BodyModel model = new BodyModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_BODY, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());

        // On by default: string, number and identifier tokens wear their own colours (the plain
        // colour is the theme's text colour, which is also the area's foreground).
        Color text = area.getForeground();
        SyntaxScheme scheme = schemeOf(area);
        Color stringFg = scheme.getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground;
        Color numberFg = scheme.getStyle(TokenTypes.LITERAL_NUMBER_DECIMAL_INT).foreground;
        assertNotEquals(text, stringFg, "the string literal colour");
        assertNotEquals(text, numberFg, "the number literal colour");

        // Off: every token is drawn in the plain text colour.
        SwingUtilities.invokeAndWait(
                () -> area.options().setOn(PrettifyTextArea.Option.SYNTAX_HIGHLIGHT, false));
        scheme = schemeOf(area);
        assertEquals(text, scheme.getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground);
        assertEquals(text, scheme.getStyle(TokenTypes.LITERAL_NUMBER_DECIMAL_INT).foreground);
        assertEquals(text, scheme.getStyle(TokenTypes.IDENTIFIER).foreground);

        // On: the colours come back.
        SwingUtilities.invokeAndWait(
                () -> area.options().setOn(PrettifyTextArea.Option.SYNTAX_HIGHLIGHT, true));
        scheme = schemeOf(area);
        assertEquals(stringFg, scheme.getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground);
        assertEquals(numberFg, scheme.getStyle(TokenTypes.LITERAL_NUMBER_DECIMAL_INT).foreground);
    }

    @Test
    void theTogglesSurviveASaveLoadRoundtrip() throws Exception {
        HttpMessage message = new HttpMessage(new HttpRequestHeader(HEADER));
        message.setRequestBody("{\"a\":1}");
        BodyModel model = new BodyModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_BODY, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        SwingUtilities.invokeAndWait(
                () -> {
                    PrettifyTextArea.Options options = area.options();
                    options.setOn(PrettifyTextArea.Option.FORMAT, false);
                    options.setOn(PrettifyTextArea.Option.WRAP, false);
                    options.setOn(PrettifyTextArea.Option.SYNTAX_HIGHLIGHT, false);
                    options.setOn(PrettifyTextArea.Option.UPDATE_CONTENT_TYPE, false);
                });
        view.setParentConfigurationKey("panel.");
        ZapXmlConfiguration configuration = new ZapXmlConfiguration();
        view.saveConfiguration(configuration);

        // A fresh view of the same kind reads the saved view: the pieces the toggles move are
        // exactly where the view left them.
        HttpMessage reloaded = new HttpMessage(new HttpRequestHeader(HEADER));
        reloaded.setRequestBody("{\"a\":1}");
        BodyModel reloadedModel = new BodyModel();
        reloadedModel.setMessage(reloaded);
        PrettifyHttpPanelView loaded =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_BODY, reloadedModel);
        loaded.setParentConfigurationKey("panel.");
        loaded.loadConfiguration(configuration);
        PrettifyTextArea loadedArea = textAreaOf(loaded);
        boolean[] states = new boolean[4];
        SwingUtilities.invokeAndWait(
                () -> {
                    states[0] = loadedArea.options().isOn(PrettifyTextArea.Option.FORMAT);
                    states[1] = loadedArea.options().isOn(PrettifyTextArea.Option.WRAP);
                    states[2] = loadedArea.options().isOn(PrettifyTextArea.Option.SYNTAX_HIGHLIGHT);
                    states[3] = loadedArea.options().isOn(PrettifyTextArea.Option.UPDATE_CONTENT_TYPE);
                });
        assertFalse(states[0] || states[1] || states[2] || states[3], "saved: " + List.of(states));
        assertFalse(loadedArea.isSyntaxHighlightingEnabled(), "highlight plain");
    }

    @Test
    void duplicateContentTypeHeadersAreLeftAlone() throws Exception {
        String header =
                "POST http://example.com/ HTTP/1.1\r\n"
                        + "Host: example.com\r\n"
                        + "Content-Type: application/x-www-form-urlencoded\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "\r\n";
        HttpMessage message = new HttpMessage(new HttpRequestHeader(header));
        message.setRequestBody("{\"a\":1}");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_ALL, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Document readOnlyDocument = area.getDocument();
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getDocument() != readOnlyDocument);

        SwingUtilities.invokeAndWait(() -> view.dataChanged(null));

        assertEquals(
                List.of("application/x-www-form-urlencoded", "text/plain"),
                message.getRequestHeader().getHeaderValues("Content-Type"));
    }

    @Test
    void theQueryTheRequestHadBeforeTheMethodChangeSurvivesTheRoundTrip() throws Exception {
        String header =
                "POST http://example.com/z?key=K HTTP/1.1\r\n"
                        + "Host: example.com\r\n"
                        + "Content-Type: application/x-www-form-urlencoded\r\n"
                        + "\r\n";
        HttpMessage message = new HttpMessage(new HttpRequestHeader(header));
        message.setRequestBody("foo=bar&baz");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        assertQuerySurvivesTheMethodRoundTrip(
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_ALL, model),
                message,
                "foo=bar&baz");
    }

    @Test
    void theQuerySurvivesTheMethodRoundTripWithTheBodyView() throws Exception {
        String header =
                "POST http://example.com/z?key=K HTTP/1.1\r\n"
                        + "Host: example.com\r\n"
                        + "Content-Type: application/x-www-form-urlencoded\r\n"
                        + "\r\n";
        HttpMessage message = new HttpMessage(new HttpRequestHeader(header));
        message.setRequestBody("foo=bar&baz");
        RequestBodyStringHttpPanelViewModel model = new RequestBodyStringHttpPanelViewModel();
        model.setMessage(message);

        assertQuerySurvivesTheMethodRoundTrip(
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_BODY, model),
                message,
                "foo=bar&baz");
    }

    /**
     * Runs ZAP's own sequence for a change of the method through its combo (an edit-save of the
     * views, the mutation of the message, a dataChanged notification) in both directions, and
     * checks that the body the request had and the query string that was in its URL before the
     * body was merged into the query both survive the round trip.
     */
    private void assertQuerySurvivesTheMethodRoundTrip(
            PrettifyHttpPanelView view, HttpMessage message, String body) throws Exception {
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Document readOnlyDocument = area.getDocument();
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getDocument() != readOnlyDocument);

        // The method combo saves the views, mutates the message and notifies the views.
        SwingUtilities.invokeAndWait(
                () -> {
                    message.mutateHttpMethod("GET");
                    view.dataChanged(null);
                });
        assertEquals("key=K&foo=bar&baz", uriQueryOf(message), "body moved into the query");

        SwingUtilities.invokeAndWait(
                () -> {
                    message.mutateHttpMethod("POST");
                    view.dataChanged(null);
                });
        assertEquals(body, message.getRequestBody().toString(), "body put back");
        assertEquals("key=K", uriQueryOf(message), "query the URL had before put back");
        assertTrue(
                message.getRequestHeader().toString().contains("POST http://example.com/z?key=K"),
                "request line keeps the URL: " + message.getRequestHeader().toString());
    }

    private static String uriQueryOf(HttpMessage message) throws Exception {
        return message.getRequestHeader().getURI().getEscapedQuery();
    }

    /** The document object of the area read on the EDT, to wait for an install to finish. */
    private static Object documentOf(PrettifyTextArea area) {
        if (SwingUtilities.isEventDispatchThread()) {
            return area.getDocument();
        }
        Object[] doc = new Object[1];
        try {
            SwingUtilities.invokeAndWait(() -> doc[0] = area.getDocument());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
        return doc[0];
    }

    /** The text of the area read on the EDT. */
    private static String textOf(PrettifyTextArea area) {
        if (SwingUtilities.isEventDispatchThread()) {
            return area.getText();
        }
        String[] text = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> text[0] = area.getText());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
        return text[0];
    }

    /**
     * The undo history is per message, like it is per request in Repeater and the manual request
     * editor, where every tab owns the history of its request alone: ZAP swapping a different
     * request into the panel starts the edit session over, so nothing the earlier request had on
     * record resurfaces in the editor.
     */
    @Test
    void aSwapToAnotherMessageStartsTheEditSessionOver() throws Exception {
        HttpMessage first =
                new HttpMessage(
                        new HttpRequestHeader(
                                "POST http://example.com/one HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        first.setRequestBody("{\"a\":1}");
        HttpMessage second =
                new HttpMessage(
                        new HttpRequestHeader(
                                "POST http://example.com/two HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        second.setRequestBody("{\"a\":2}");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(first);

        // The fixed style keeps the edit session over the renders, like the registered All views
        // have.
        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", "text/plain", PrettifyHttpPanelView.Target.REQUEST_ALL,
                        model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        // The renders replace the document; wait for the editable render's own document, so the
        // edit happens in the document that is kept over the renders (an edit in the document of
        // the read-only render would be replaced with it).
        Object readOnlyDocument = documentOf(area);
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> documentOf(area) != readOnlyDocument);
        waitUntil(() -> textOf(area).contains("example.com/one"));

        // The user edits the first request (an unsaved change in the text area).
        SwingUtilities.invokeAndWait(
                () -> {
                    area.setCaretPosition(area.getText().indexOf('1') + 1);
                    area.replaceSelection("9");
                });
        assertTrue(area.canUndo());

        // Another request is shown: the model fires the dataChanged notification and the view
        // re-renders. The edit session document must stay the one installed - and the session
        // starts over for the new request, so the edit (and the whole earlier walk) is not
        // carried into it.
        SwingUtilities.invokeAndWait(() -> model.setMessage(second));
        waitUntil(() -> textOf(area).contains("example.com/two"));
        assertFalse(area.canUndo(), "a session started at the message has nothing on record");

        // Undo cannot empty the view and cannot bring the earlier request in: the walk has
        // nothing to step on.
        SwingUtilities.invokeAndWait(area::undoLastAction);
        assertTrue(
                textOf(area).contains("example.com/two"),
                "the view after the undo: [" + textOf(area) + "]");
        assertFalse(area.canUndo(), "the session before the message is empty");
        assertFalse(area.canRedo(), "nothing walked back, nothing to redo");
        assertFalse(textOf(area).contains("example.com/one"), "no content of the earlier request");
        assertFalse(textOf(area).contains("19"), "no edit of the earlier request");
    }

    @Test
    void undoSurvivesTheMethodChangeAndTheRoundTrip() throws Exception {
        HttpMessage message =
                new HttpMessage(
                        new HttpRequestHeader(
                                "POST http://example.com/z?key=K HTTP/1.1\r\n"
                                        + "Host: example.com\r\n"
                                        + "Content-Type: application/x-www-form-urlencoded\r\n"
                                        + "\r\n"));
        message.setRequestBody("foo=bar&baz");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", "text/plain", PrettifyHttpPanelView.Target.REQUEST_ALL,
                        model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Object readOnlyDocument = documentOf(area);
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> documentOf(area) != readOnlyDocument);
        waitUntil(() -> textOf(area).contains("foo=bar&baz"));

        // The edit the user made before the method change.
        SwingUtilities.invokeAndWait(
                () -> {
                    String text = area.getText();
                    int caret = text.indexOf("foo=bar&baz") + "foo=bar&baz".length();
                    area.setCaretPosition(caret);
                    area.replaceSelection("&qux");
                });
        assertTrue(area.canUndo());

        // ZAP's method combo: the mutation of the message and a dataChanged notification. The
        // body is merged into the query string; the view is re-rendered (the content changed),
        // and the whole render is one undoable edit with the earlier state as its before.
        SwingUtilities.invokeAndWait(
                () -> {
                    message.mutateHttpMethod("GET");
                    view.dataChanged(null);
                });
        waitUntil(() -> textOf(area).contains("GET http://example.com/z?key=K"));
        assertTrue(area.canUndo(), "history thrown away when the method changed");

        // One undo takes the view back to exactly the state before the change - not through an
        // empty text area on the way.
        SwingUtilities.invokeAndWait(area::undoLastAction);
        assertTrue(textOf(area).contains("POST http://example.com/z?key=K"), "the earlier state");
        assertTrue(
                textOf(area).contains("foo=bar&baz&qux"),
                "the state before the change: [[" + textOf(area) + "]] canUndo=" + area.canUndo());
        assertTrue(area.canRedo());

        // The next undo takes the user's edit back, and the walk stops at the message the
        // session starts with.
        SwingUtilities.invokeAndWait(area::undoLastAction);
        assertTrue(
                textOf(area).contains("foo=bar&baz")
                        && !textOf(area).contains("&qux"),
                "the state before the edit: [[" + textOf(area) + "]]");
        assertFalse(area.canUndo(), "the walk ends at the message");
        assertFalse(textOf(area).isEmpty(), "no Ctrl+Z stops at an empty view");

        // And redo takes the states forward the same way: the edit, then the re-render.
        SwingUtilities.invokeAndWait(area::redoLastAction);
        assertTrue(textOf(area).contains("foo=bar&baz&qux"));
        SwingUtilities.invokeAndWait(area::redoLastAction);
        waitUntil(() -> textOf(area).contains("GET http://example.com/z?key=K"));
    }

    @Test
    void theEditSessionStartsOverWhenTheViewStopsBeingEditable() throws Exception {
        HttpMessage message =
                new HttpMessage(
                        new HttpRequestHeader("POST http://example.com/ HTTP/1.1\r\n\r\n"));
        message.setRequestBody("{\"a\":1}");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", "text/plain", PrettifyHttpPanelView.Target.REQUEST_ALL,
                        model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Object readOnlyDocument = documentOf(area);
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> documentOf(area) != readOnlyDocument);
        waitUntil(() -> area.getText().contains("\"a\": 1"));

        SwingUtilities.invokeAndWait(
                () -> {
                    area.setCaretPosition(area.getText().length());
                    area.replaceSelection("zzz");
                });
        assertTrue(area.canUndo());

        SwingUtilities.invokeAndWait(() -> view.setEditable(false));
        // The editability resets the session synchronously: the stack is gone with the edits, and
        // the read-only view shows the message as it is in the model.
        assertFalse(area.canUndo(), "the edit session ends with the editability");
        Thread.sleep(300);
        assertTrue(!textOf(area).contains("zzz"), "content after toggle off: [" + textOf(area) + "]");
        Object documentBeforeReset = documentOf(area);

        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        // The read-only view shows the same text as the editable one, so the wait is on the
        // document of the new session, not on the text: only the swap of the document tells the
        // editable render apart.
        waitUntil(() -> documentOf(area) != documentBeforeReset);
        waitUntil(() -> area.getText().contains("\"a\": 1"));
        // The new session starts with the message and nothing on record: there is no state
        // before the message to undo into, and no Ctrl+Z empties the view.
        assertFalse(area.canUndo(), "a fresh session has nothing on record");
        SwingUtilities.invokeAndWait(area::undoLastAction);
        assertTrue(textOf(area).contains("\"a\": 1"), "undo of an empty walk shows the message");
        assertFalse(textOf(area).contains("zzz"), "the earlier edit is not carried over");
    }

    /**
     * The session starts at the message: there is nothing to undo before it, so a stray Ctrl+Z
     * cannot empty the view, and undo becomes usable with the first edit the user makes - which
     * then stops at the message instead of carrying on past it.
     */
    @Test
    void theUndoOfAUserEditStopsAtTheMessageTheSessionStartsWith() throws Exception {
        HttpMessage message = new HttpMessage(new HttpRequestHeader(HEADER));
        message.setRequestBody("{\"a\":1}");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", "text/plain", PrettifyHttpPanelView.Target.REQUEST_ALL,
                        model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Object readOnlyDocument = documentOf(area);
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> documentOf(area) != readOnlyDocument);
        waitUntil(() -> textOf(area).contains("\"a\": 1"));

        // One Ctrl+Z without any edit of the user: nothing is on record, and the message stays.
        SwingUtilities.invokeAndWait(
                () -> {
                    assertFalse(area.canUndo(), "the session starts with nothing on record");
                    area.undoLastAction();
                });
        assertTrue(textOf(area).contains("\"a\": 1"), "an empty walk shows the message");

        // The user's edit is the first state on record: its undo stops at the message.
        SwingUtilities.invokeAndWait(
                () -> {
                    area.setCaretPosition(area.getText().length());
                    area.replaceSelection("zzz");
                });
        assertTrue(area.canUndo());
        SwingUtilities.invokeAndWait(area::undoLastAction);
        assertTrue(
                textOf(area).contains("\"a\": 1") && !textOf(area).contains("zzz"),
                "the message after the undo of the edit: [" + textOf(area) + "]");
        assertFalse(area.canUndo(), "the walk stops at the message, not at an empty view");
        assertTrue(area.canRedo());

        SwingUtilities.invokeAndWait(area::redoLastAction);
        assertTrue(textOf(area).contains("zzz"), "the redo takes the edit back in");
    }

    @Test
    void aSwitchToAnotherRequestStartsWithNothingOnRecord() throws Exception {
        HttpMessage first =
                new HttpMessage(
                        new HttpRequestHeader(
                                "POST http://example.com/one HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        first.setRequestBody("{\"a\":1}");
        HttpMessage second =
                new HttpMessage(
                        new HttpRequestHeader(
                                "POST http://example.com/two HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        second.setRequestBody("{\"a\":2}");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(first);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", "text/plain", PrettifyHttpPanelView.Target.REQUEST_ALL,
                        model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Object readOnlyDocument = documentOf(area);
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> documentOf(area) != readOnlyDocument);
        waitUntil(() -> textOf(area).contains("example.com/one"));

        // Another request is shown, and the user edits nothing in between. The session starts
        // over for the new request: it begins with the message and nothing on record, so a
        // Ctrl+Z neither empties the view nor brings the earlier walk in.
        SwingUtilities.invokeAndWait(() -> model.setMessage(second));
        waitUntil(() -> textOf(area).contains("example.com/two"));
        SwingUtilities.invokeAndWait(area::undoLastAction);
        assertTrue(
                textOf(area).contains("example.com/two"),
                "the view after one undo: [" + textOf(area) + "]");
        assertFalse(area.canUndo(), "the session has nothing on record before the message");
        assertFalse(area.canRedo(), "nothing was undone, nothing to redo");

        // The first request never returns into this editor, however many undos follow.
        SwingUtilities.invokeAndWait(area::undoLastAction);
        assertFalse(
                textOf(area).contains("example.com/one"),
                "the earlier request resurfaced: [" + textOf(area) + "]");
    }

    @Test
    void anEmptyMessageWipesTheEditHistoryLikeTheCoreTextView() throws Exception {
        HttpMessage message = new HttpMessage(new HttpRequestHeader(HEADER));
        message.setRequestBody("{\"a\":1}");
        BodyModel model = new BodyModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", "text/plain", PrettifyHttpPanelView.Target.REQUEST_BODY,
                        model);
        PrettifyTextArea area = textAreaOf(view);

        // The view renders off the EDT; wait for the first document before making it editable.
        waitUntil(() -> !area.getText().isEmpty());
        Document readOnlyDocument = area.getDocument();
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getDocument() != readOnlyDocument);
        waitUntil(() -> textOf(area).contains("\"a\": 1"));

        // A message without a body is shown: the view passes through the empty message and,
        // like the core text view, wipes the history with it.
        SwingUtilities.invokeAndWait(
                () -> {
                    model.setData("");
                    view.dataChanged(null);
                });
        waitUntil(() -> textOf(area).isEmpty());
        assertFalse(area.canUndo(), "the empty message did not wipe the history");

        // The next message's fill runs from the emptied state with nothing on record before it:
        // there is nothing to undo.
        SwingUtilities.invokeAndWait(
                () -> {
                    model.setData("{\"b\":2}");
                    view.dataChanged(null);
                });
        waitUntil(() -> textOf(area).contains("\"b\": 2"));
        assertFalse(area.canUndo());
        SwingUtilities.invokeAndWait(area::undoLastAction);
        assertTrue(
                textOf(area).contains("\"b\": 2"),
                "an undo of an empty walk shows the message: [" + textOf(area) + "]");
    }

    @Test
    void theBodyOfAPreviousRequestIsNotPutIntoANewOneWhenTheVerbChanges() throws Exception {
        HttpMessage first =
                new HttpMessage(
                        new HttpRequestHeader(
                                "POST http://example.com/first?a=b HTTP/1.1\r\n"
                                        + "Host: example.com\r\n"
                                        + "\r\n"));
        first.setRequestBody("foo=bar");
        HttpMessage second =
                new HttpMessage(
                        new HttpRequestHeader(
                                "GET http://example.com/second?p=1 HTTP/1.1\r\n"
                                        + "Host: example.com\r\n"
                                        + "\r\n"));
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(first);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", null, PrettifyHttpPanelView.Target.REQUEST_ALL, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getText().contains("foo=bar"));

        // Another request is shown (the model fires the dataChanged notification for it): a GET,
        // which carries no body.
        SwingUtilities.invokeAndWait(
                () -> {
                    model.setMessage(second);
                    view.dataChanged(null);
                });
        waitUntil(() -> area.getText().contains("GET http://example.com/second?p=1"));

        // The verb of the new request is changed through ZAP's method combo.
        SwingUtilities.invokeAndWait(
                () -> {
                    second.mutateHttpMethod("POST");
                    view.dataChanged(null);
                });

        // ZAP moves the new request's query into the body; the previous request's body (and query)
        // must not come back with it.
        assertEquals("p=1", second.getRequestBody().toString());
        assertNull(uriQueryOf(second), "the previous request's query is not in the URL");
        assertEquals("foo=bar", first.getRequestBody().toString(), "the first request is untouched");
        assertEquals("a=b", uriQueryOf(first), "the first request is untouched");
    }

    /**
     * A body typed before any content type header exists is plain; once the header naming its type
     * is typed in, the body line following it is tokenized for that type. The body delegate a
     * document senses once must not stay plain when the reader types the header afterwards.
     */
    @Test
    void aBodyTypedBeforeItsContentTypeHeaderIsHighlightedOnceTheHeaderNamesTheType()
            throws Exception {
        HttpMessage message =
                new HttpMessage(
                        new HttpRequestHeader(
                                "POST http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n"));
        message.setRequestBody("");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", "prettify/http-request",
                        PrettifyHttpPanelView.Target.REQUEST_ALL, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Document readOnlyDocument = area.getDocument();
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getDocument() != readOnlyDocument);

        // Typing the body while no Content-Type is in the headers: an editable All view with an
        // empty body does not carry the blank separator line the model has, so the body is started
        // with its own blank line.
        SwingUtilities.invokeAndWait(
                () -> {
                    area.setCaretPosition(area.getText().length());
                    area.replaceSelection("\r\n\r\nfoo=bar&baz");
                });
        assertEquals(List.of("foo=bar&baz|ID"), tokensOfLastLine(area), "undetermined, so plain");

        // The Content-Type naming the body is typed into the header block afterwards.
        SwingUtilities.invokeAndWait(
                () -> {
                    String text = area.getText();
                    int separator = text.indexOf("\r\n\r\n");
                    assertTrue(separator >= 0, "a separator line exists: " + text);
                    area.setCaretPosition(separator + 2);
                    area.replaceSelection("Content-Type: application/x-www-form-urlencoded\r\n");
                });
        assertEquals(
                List.of("foo|NAME", "=|OP", "bar|VALUE", "&|SEP", "baz|NAME"),
                tokensOfLastLine(area),
                "the header makes the type decisive");
    }

    /**
     * A re-render of the message shown (a change of the method, say) swaps the whole display inside
     * an atomic edit; the tokens of the re-rendered request line that the next repaint asks for
     * first must describe the new text, not a leftover of the emptied document from half way
     * through the swap.
     */
    @Test
    void aMethodChangeRerenderTokenizesTheRequestLineFromScratch() throws Exception {
        HttpMessage message =
                new HttpMessage(
                        new HttpRequestHeader(
                                "POST http://example.com/ HTTP/1.1\r\n"
                                        + "Host: example.com\r\n"
                                        + "Content-Type: application/x-www-form-urlencoded\r\n"
                                        + "\r\n"));
        message.setRequestBody("foo=bar&baz");
        RequestStringHttpPanelViewModel model = new RequestStringHttpPanelViewModel();
        model.setMessage(message);

        PrettifyHttpPanelView view =
                new PrettifyHttpPanelView(
                        "test", "test", "prettify/http-request",
                        PrettifyHttpPanelView.Target.REQUEST_ALL, model);
        PrettifyTextArea area = textAreaOf(view);
        waitUntil(() -> !area.getText().isEmpty());
        Document readOnlyDocument = area.getDocument();
        SwingUtilities.invokeAndWait(() -> view.setEditable(true));
        waitUntil(() -> area.getDocument() != readOnlyDocument);
        waitUntil(() -> textOf(area).contains("foo=bar"));

        // ZAP's method combo saves the views, mutates the message and notifies the views.
        SwingUtilities.invokeAndWait(
                () -> {
                    message.mutateHttpMethod("GET");
                    view.dataChanged(null);
                });
        waitUntil(() -> textOf(area).contains("GET http://example.com/?foo=bar&baz"));
        assertEquals(
                List.of(
                        "GET|FIRST",
                        " |WS",
                        "http://example.com/|FIRST",
                        "?|FIRST",
                        "foo|NAME",
                        "=|OP",
                        "bar|VALUE",
                        "&|SEP",
                        "baz|NAME",
                        " |WS",
                        "HTTP/1.1|FIRST"),
                tokensOfLine(area, 0),
                "the request line as ZAP moved the body into the query");
    }

    /**
     * The tokens of a display line, read on the EDT, as {@code lexeme|TYPE} pairs. Line state
     * carriers (negative types with no text of their own) and empty tokens are left out.
     */
    private static List<String> tokensOfLine(PrettifyTextArea area, int line) {
        List<String>[] tokens = new List[1];
        if (SwingUtilities.isEventDispatchThread()) {
            collectTokens(area.getDocument(), line, tokens);
            return tokens[0];
        }
        try {
            SwingUtilities.invokeAndWait(
                    () -> collectTokens(area.getDocument(), line, tokens));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
        return tokens[0];
    }

    private static List<String> tokensOfLastLine(PrettifyTextArea area) {
        int[] line = new int[1];
        if (SwingUtilities.isEventDispatchThread()) {
            line[0] = lastLineOf(area.getDocument());
            return tokensOfLine(area, line[0]);
        }
        try {
            SwingUtilities.invokeAndWait(() -> line[0] = lastLineOf(area.getDocument()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
        return tokensOfLine(area, line[0]);
    }

    private static int lastLineOf(javax.swing.text.Document document) {
        return document.getDefaultRootElement().getElementCount() - 1;
    }

    private static void collectTokens(javax.swing.text.Document document, int line, List<String>[] tokens) {
        PrettifyDocument prettify = (PrettifyDocument) document;
        List<String> collected = new java.util.ArrayList<>();
        Token t = prettify.getTokenListForLine(line);
        while (t != null) {
            int len = t.length();
            if (len > 0 && t.getTextArray() != null) {
                String lexeme =
                        new String(t.getTextArray(), t.getTextOffset(), t.length());
                if (!lexeme.isEmpty()) {
                    collected.add(lexeme + "|" + tokenName(t.getType()));
                }
            }
            t = t.getNextToken();
        }
        tokens[0] = collected;
    }

    private static String tokenName(int type) {
        switch (type) {
            case PrettifyTokenTypes.HTTP_PARAMETER_NAME:
                return "NAME";
            case PrettifyTokenTypes.HTTP_PARAMETER_VALUE:
                return "VALUE";
            case PrettifyTokenTypes.HTTP_FIRST_LINE:
                return "FIRST";
            case PrettifyTokenTypes.HTTP_HEADER_NAME:
                return "HDRNAME";
            case PrettifyTokenTypes.HTTP_HEADER_VALUE:
                return "HDRVALUE";
            case org.fife.ui.rsyntaxtextarea.TokenTypes.OPERATOR:
                return "OP";
            case org.fife.ui.rsyntaxtextarea.TokenTypes.SEPARATOR:
                return "SEP";
            case org.fife.ui.rsyntaxtextarea.TokenTypes.WHITESPACE:
                return "WS";
            case org.fife.ui.rsyntaxtextarea.TokenTypes.IDENTIFIER:
                return "ID";
            default:
                return Integer.toString(type);
        }
    }

    static PrettifyTextArea textAreaOf(PrettifyHttpPanelView view) throws Exception {
        Field field = PrettifyHttpPanelView.class.getDeclaredField("textArea");
        field.setAccessible(true);
        return (PrettifyTextArea) field.get(view);
    }

    private static RTextScrollPane scrollPaneOf(PrettifyHttpPanelView view) throws Exception {
        Field field = PrettifyHttpPanelView.class.getDeclaredField("scrollPane");
        field.setAccessible(true);
        return (RTextScrollPane) field.get(view);
    }

    /** The scheme read on the EDT, so the document paint state is the same one being reasoned about. */
    private static SyntaxScheme schemeOf(PrettifyTextArea area) throws Exception {
        SyntaxScheme[] scheme = new SyntaxScheme[1];
        try {
            SwingUtilities.invokeAndWait(() -> scheme[0] = area.getSyntaxScheme());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
        return scheme[0];
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
