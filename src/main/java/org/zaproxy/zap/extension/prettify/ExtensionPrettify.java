package org.zaproxy.zap.extension.prettify;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import java.util.function.Supplier;
import org.zaproxy.zap.extension.httppanel.component.split.request.RequestSplitComponent;
import org.zaproxy.zap.extension.httppanel.view.AbstractStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestBodyStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestHeaderStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.response.ResponseBodyStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.response.ResponseHeaderStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.response.ResponseStringHttpPanelViewModel;
import org.zaproxy.zap.view.HttpPanelManager;

/**
 * Prettify add-on: adds a syntax-highlighted "Pretty" view to the request and response panels of
 * ZAP.
 */
public class ExtensionPrettify extends ExtensionAdaptor {

    private static final Logger LOGGER = LogManager.getLogger(ExtensionPrettify.class);

    public static final String NAME = "ExtensionPrettify";

    private static final String CAPTION_KEY = "prettify.view.name";

    // Each view's name is the single source of truth for both registration and removal.
    // HttpPanelManager keys factories by the factory's name and mounted views by the view's own
    // name (HttpPanelView.getName()); using the same string for both keeps remove*ViewFactory and
    // remove*Views in sync, so an unload actually detaches the tab instead of only stopping new
    // panels from receiving it.
    private static final String VIEW_REQUEST_SPLIT_HEADER = "PrettifyRequestSplitHeader";
    private static final String VIEW_REQUEST_SPLIT_BODY = "PrettifyRequestSplitBody";
    private static final String VIEW_RESPONSE_SPLIT_HEADER = "PrettifyResponseSplitHeader";
    private static final String VIEW_RESPONSE_SPLIT_BODY = "PrettifyResponseSplitBody";
    private static final String VIEW_REQUEST_ALL = "PrettifyRequestAll";
    private static final String VIEW_RESPONSE_ALL = "PrettifyResponseAll";

    public ExtensionPrettify() {
        super(NAME);
    }

    @Override
    public String getUIName() {
        return Constant.messages.getString("prettify.ext.name");
    }

    @Override
    public String getAuthor() {
        return "ArkhaMahn";
    }

    @Override
    public String getDescription() {
        return Constant.messages.getString("prettify.ext.desc");
    }

    @Override
    public void hook(ExtensionHook extensionHook) {
        super.hook(extensionHook);
        if (getView() == null) {
            return;
        }
        PrettifyTextActions.init();
        HttpPanelManager panelManager = HttpPanelManager.getInstance();
        panelManager.addRequestViewFactory(
                "RequestSplit",
                new SplitViewFactory(
                        VIEW_REQUEST_SPLIT_HEADER,
                        RequestSplitComponent.ViewComponent.HEADER,
                        "prettify/http-request-head",
                        PrettifyHttpPanelView.Target.REQUEST_HEAD,
                        RequestHeaderStringHttpPanelViewModel::new));
        panelManager.addRequestViewFactory(
                "RequestSplit",
                new SplitViewFactory(
                        VIEW_REQUEST_SPLIT_BODY,
                        RequestSplitComponent.ViewComponent.BODY,
                        null,
                        PrettifyHttpPanelView.Target.REQUEST_BODY,
                        RequestBodyStringHttpPanelViewModel::new));
        panelManager.addResponseViewFactory(
                "ResponseSplit",
                new SplitViewFactory(
                        VIEW_RESPONSE_SPLIT_HEADER,
                        RequestSplitComponent.ViewComponent.HEADER,
                        "prettify/http-response-head",
                        PrettifyHttpPanelView.Target.RESPONSE_HEAD,
                        ResponseHeaderStringHttpPanelViewModel::new));
        panelManager.addResponseViewFactory(
                "ResponseSplit",
                new SplitViewFactory(
                        VIEW_RESPONSE_SPLIT_BODY,
                        RequestSplitComponent.ViewComponent.BODY,
                        null,
                        PrettifyHttpPanelView.Target.RESPONSE_BODY,
                        ResponseBodyStringHttpPanelViewModel::new));
        panelManager.addRequestViewFactory(
                "RequestAll",
                new AllViewFactory(
                        VIEW_REQUEST_ALL,
                        "prettify/http-request",
                        PrettifyHttpPanelView.Target.REQUEST_ALL,
                        RequestStringHttpPanelViewModel::new));
        panelManager.addResponseViewFactory(
                "ResponseAll",
                new AllViewFactory(
                        VIEW_RESPONSE_ALL,
                        "prettify/http-response",
                        PrettifyHttpPanelView.Target.RESPONSE_ALL,
                        ResponseStringHttpPanelViewModel::new));
    }

    @Override
    public boolean canUnload() {
        return true;
    }

    @Override
    public void unload() {
        if (getView() != null) {
            HttpPanelManager panelManager = HttpPanelManager.getInstance();
            // Drop the factories first so panels stop being offered a "Pretty" view, then detach
            // the instances already mounted on the open request/response panels. Both steps are
            // required: the view removal is what makes the tab disappear immediately, without a
            // restart.
            safe(() -> panelManager.removeRequestViewFactory("RequestSplit", VIEW_REQUEST_SPLIT_HEADER), "request header view factory");
            safe(() -> panelManager.removeRequestViewFactory("RequestSplit", VIEW_REQUEST_SPLIT_BODY), "request body view factory");
            safe(() -> panelManager.removeResponseViewFactory("ResponseSplit", VIEW_RESPONSE_SPLIT_HEADER), "response header view factory");
            safe(() -> panelManager.removeResponseViewFactory("ResponseSplit", VIEW_RESPONSE_SPLIT_BODY), "response body view factory");
            safe(() -> panelManager.removeRequestViewFactory("RequestAll", VIEW_REQUEST_ALL), "request view factory");
            safe(() -> panelManager.removeResponseViewFactory("ResponseAll", VIEW_RESPONSE_ALL), "response view factory");

            safe(() -> panelManager.removeRequestViews("RequestSplit", VIEW_REQUEST_SPLIT_HEADER, RequestSplitComponent.ViewComponent.HEADER), "request header views");
            safe(() -> panelManager.removeRequestViews("RequestSplit", VIEW_REQUEST_SPLIT_BODY, RequestSplitComponent.ViewComponent.BODY), "request body views");
            safe(() -> panelManager.removeResponseViews("ResponseSplit", VIEW_RESPONSE_SPLIT_HEADER, RequestSplitComponent.ViewComponent.HEADER), "response header views");
            safe(() -> panelManager.removeResponseViews("ResponseSplit", VIEW_RESPONSE_SPLIT_BODY, RequestSplitComponent.ViewComponent.BODY), "response body views");
            safe(() -> panelManager.removeRequestViews("RequestAll", VIEW_REQUEST_ALL, null), "request views");
            safe(() -> panelManager.removeResponseViews("ResponseAll", VIEW_RESPONSE_ALL, null), "response views");
        }
        // These are process-wide resources that may have been created even when no view was ever
        // shown (for example by a headless or partial hook, or by a background split still in
        // flight), so their cleanup must not depend on getView().
        PrettifyTextActions.remove();
        PrettifyExecutor.shutdown();
        // Clear the cache after stopping the workers so a finishing split cannot immediately
        // repopulate it.
        DisplayTextSplitter.clearCache();
    }

    /** Runs one removal, logging and continuing on failure so unload cannot stop half-way. */
    private static void safe(Runnable removal, String what) {
        try {
            removal.run();
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to remove {} during unload; continuing.", what, e);
        }
    }

    /** Factory for a request/response split pane (header or body) "Pretty" view. */
    private static final class SplitViewFactory implements HttpPanelManager.HttpPanelViewFactory {
        private final String name;
        private final Object viewComponent;
        private final String styleKey;
        private final PrettifyHttpPanelView.Target target;
        private final Supplier<AbstractStringHttpPanelViewModel> models;

        SplitViewFactory(
                String name,
                Object viewComponent,
                String styleKey,
                PrettifyHttpPanelView.Target target,
                Supplier<AbstractStringHttpPanelViewModel> models) {
            this.name = name;
            this.viewComponent = viewComponent;
            this.styleKey = styleKey;
            this.target = target;
            this.models = models;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public HttpPanelView getNewView() {
            // Every view needs its own model: the model holds the message the view shows, so a
            // shared one would make all the panels (the Requester tabs, Break, ...) show whichever
            // message was set last.
            return new PrettifyHttpPanelView(name, CAPTION_KEY, styleKey, target, models.get());
        }

        @Override
        public Object getOptions() {
            return viewComponent;
        }
    }

    /** Factory for a combined (All) "Pretty" view. */
    private static final class AllViewFactory implements HttpPanelManager.HttpPanelViewFactory {
        private final String name;
        private final String styleKey;
        private final PrettifyHttpPanelView.Target target;
        private final Supplier<AbstractStringHttpPanelViewModel> models;

        AllViewFactory(
                String name,
                String styleKey,
                PrettifyHttpPanelView.Target target,
                Supplier<AbstractStringHttpPanelViewModel> models) {
            this.name = name;
            this.styleKey = styleKey;
            this.target = target;
            this.models = models;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public HttpPanelView getNewView() {
            // Every view needs its own model: the model holds the message the view shows, so a
            // shared one would make all the panels (the Requester tabs, Break, ...) show whichever
            // message was set last.
            return new PrettifyHttpPanelView(name, CAPTION_KEY, styleKey, target, models.get());
        }

        @Override
        public Object getOptions() {
            return null;
        }
    }
}
