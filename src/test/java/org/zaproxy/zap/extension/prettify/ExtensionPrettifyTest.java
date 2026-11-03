package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.extension.ViewDelegate;
import org.parosproxy.paros.view.MainFrame;
import org.parosproxy.paros.view.MainPopupMenu;
import org.parosproxy.paros.view.OptionsDialog;
import org.parosproxy.paros.view.OutputPanel;
import org.parosproxy.paros.view.SessionDialog;
import org.parosproxy.paros.view.SiteMapPanel;
import org.parosproxy.paros.view.WaitMessageDialog;
import org.zaproxy.zap.extension.httppanel.HttpPanelRequest;
import org.zaproxy.zap.extension.httppanel.HttpPanelResponse;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.view.ContextPanelFactory;
import org.zaproxy.zap.view.HttpPanelManager;

/**
 * The hook/unload symmetry of the extension: everything {@link ExtensionPrettify#hook} registers in
 * {@link HttpPanelManager} must be gone after {@link ExtensionPrettify#unload}, or an add-on
 * unload/reload cycle leaves "Pretty" factories behind that keep offering views to new panels. The
 * removals are keyed by verbatim component names, so this is the lock on the pairs (the four
 * component names and the six view names are shared by both phases for exactly this purpose).
 */
class ExtensionPrettifyTest {

    private static final Set<String> REQUEST_VIEWS =
            Set.of(
                    "PrettifyRequestSplitHeader",
                    "PrettifyRequestSplitBody",
                    "PrettifyRequestAll");
    private static final Set<String> RESPONSE_VIEWS =
            Set.of(
                    "PrettifyResponseSplitHeader",
                    "PrettifyResponseSplitBody",
                    "PrettifyResponseAll");

    private final ExtensionPrettify extension = new ExtensionPrettify();

    @Test
    void unloadingTheHookedExtensionUnregistersEveryViewFactory() throws Exception {
        assertTrue(prettifyNames().isEmpty(), "a fresh manager has no Prettify factories");

        // The hook object is only a parameter bag for super.hook, which is a no-op, and a real
        // Model would drag in ZAP's whole View/Session startup - a bare hook is enough. The view
        // delegate goes into the package-private field ExtensionLoader uses (initView is a no-op).
        try {
            Field field = ExtensionAdaptor.class.getDeclaredField("view");
            field.setAccessible(true);
            field.set(extension, new SilentViewDelegate());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        extension.hook(new ExtensionHook(null, new SilentViewDelegate()));

        assertEquals(6, prettifyNames().size(), "the hook registered all six views");
        Map<String, Map<String, ?>> request = viewFactories(true);
        Map<String, Map<String, ?>> response = viewFactories(false);
        assertEquals(
                Set.of("PrettifyRequestSplitHeader", "PrettifyRequestSplitBody"),
                request.get("RequestSplit").keySet(),
                "the two request split views");
        assertEquals(Set.of("PrettifyRequestAll"), request.get("RequestAll").keySet());
        assertEquals(
                Set.of("PrettifyResponseSplitHeader", "PrettifyResponseSplitBody"),
                response.get("ResponseSplit").keySet(),
                "the two response split views");
        assertEquals(Set.of("PrettifyResponseAll"), response.get("ResponseAll").keySet());

        extension.unload();

        assertEquals(Set.of(), prettifyNames(), "nothing the hook registered remains after unload");
    }

    @AfterEach
    void takeEveryFactoryBackAgain() {
        // Defensive: a test failing half way through must not leave "Pretty" factories registered
        // for the rest of the run. Unload does the same removals, and they are idempotent.
        ExtensionPrettify cleanup = new ExtensionPrettify();
        try {
            Field field = ExtensionAdaptor.class.getDeclaredField("view");
            field.setAccessible(true);
            field.set(cleanup, new SilentViewDelegate());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        cleanup.unload();
    }

    /** Every view name the manager holds that belongs to this add-on (over both panels). */
    private static Set<String> prettifyNames() throws Exception {
        Set<String> names = new java.util.TreeSet<>();
        for (Map<String, ? > views : viewFactories(true).values()) {
            names.addAll(views.keySet());
        }
        for (Map<String, ?> views : viewFactories(false).values()) {
            names.addAll(views.keySet());
        }
        Set<String> prettify = new java.util.HashSet<>(REQUEST_VIEWS);
        prettify.addAll(RESPONSE_VIEWS);
        names.retainAll(prettify);
        return names;
    }

    /** The view factories of the request or response panel management, as the manager stores them. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, ?>> viewFactories(boolean request) throws Exception {
        HttpPanelManager manager = HttpPanelManager.getInstance();
        Field panels =
                HttpPanelManager.class.getDeclaredField(request ? "requestPanels" : "responsePanels");
        panels.setAccessible(true);
        Object management = panels.get(manager);
        Field views = management.getClass().getDeclaredField("views");
        views.setAccessible(true);
        return (Map<String, Map<String, ?>>) views.get(management);
    }

    /**
     * A delegate that answers everything with {@code null}: the extension only needs a non-null
     * view to run its hook, and it must not headless-crash on operations the stub never serves.
     */
    private static final class SilentViewDelegate implements ViewDelegate {

        @Override
        public MainFrame getMainFrame() {
            return null;
        }

        @Override
        public SiteMapPanel getSiteTreePanel() {
            return null;
        }

        @Override
        public OutputPanel getOutputPanel() {
            return null;
        }

        @Override
        public void setOutputPanel(OutputPanel outputPanel) {}

        @Override
        public OptionsDialog getOptionsDialog() {
            return null;
        }

        @Override
        public SessionDialog getSessionDialog() {
            return null;
        }

        @Override
        public int showConfirmDialog(String message) {
            return -1;
        }

        @Override
        public int showYesNoCancelDialog(String message) {
            return -1;
        }

        @Override
        public void showWarningDialog(String message) {}

        @Override
        public void showMessageDialog(String message) {}

        @Override
        public WaitMessageDialog getWaitMessageDialog(String message) {
            return null;
        }

        @Override
        public MainPopupMenu getPopupMenu() {
            return null;
        }

        @Override
        public HttpPanelRequest getRequestPanel() {
            return null;
        }

        @Override
        public HttpPanelResponse getResponsePanel() {
            return null;
        }

        @Override
        public void addContextPanelFactory(ContextPanelFactory factory) {}

        @Override
        public void removeContextPanelFactory(ContextPanelFactory factory) {}

        @Override
        public void displayMessage(Message message) {}

        @Override
        public KeyStroke getDefaultDeleteKeyStroke() {
            return null;
        }

        @Override
        public JButton getOptionsButton(String resourceKey, String toolTipResourceKey) {
            return null;
        }

        @Override
        public JButton getHelpButton(String resourceKey) {
            return null;
        }
    }
}
