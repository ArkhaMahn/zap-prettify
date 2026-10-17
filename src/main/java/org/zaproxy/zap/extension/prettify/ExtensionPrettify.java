package org.zaproxy.zap.extension.prettify;

import org.parosproxy.paros.Constant;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.zaproxy.zap.extension.httppanel.component.split.request.RequestSplitComponent;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestBodyStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestHeaderStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.response.ResponseBodyStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.response.ResponseHeaderStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.impl.models.http.response.ResponseStringHttpPanelViewModel;
import org.zaproxy.zap.view.HttpPanelManager;

/**
 * Prettify add-on: adds a Burp-style, syntax-highlighted "Pretty" view to the request and response
 * panels of ZAP.
 */
public class ExtensionPrettify extends ExtensionAdaptor {

    public static final String NAME = "ExtensionPrettify";

    private static final String CAPTION_KEY = "prettify.view.name";

    public ExtensionPrettify() {
        super(NAME);
    }

    @Override
    public String getUIName() {
        return Constant.messages.getString("prettify.ext.name");
    }

    @Override
    public String getAuthor() {
        return "Prettify";
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
        HttpPanelManager panelManager = HttpPanelManager.getInstance();
        panelManager.addRequestViewFactory("RequestSplit", new RequestSplitHeaderViewFactory());
        panelManager.addRequestViewFactory("RequestSplit", new RequestSplitBodyViewFactory());
        panelManager.addResponseViewFactory("ResponseSplit", new ResponseSplitHeaderViewFactory());
        panelManager.addResponseViewFactory("ResponseSplit", new ResponseSplitBodyViewFactory());
        panelManager.addRequestViewFactory("RequestAll", new RequestAllViewFactory());
        panelManager.addResponseViewFactory("ResponseAll", new ResponseAllViewFactory());
    }

    @Override
    public boolean canUnload() {
        return true;
    }

    @Override
    public void unload() {
        if (getView() == null) {
            return;
        }
        HttpPanelManager panelManager = HttpPanelManager.getInstance();
        panelManager.removeRequestViewFactory("RequestSplit", RequestSplitHeaderViewFactory.NAME);
        panelManager.removeRequestViews(
                "RequestSplit", RequestSplitHeaderViewFactory.NAME, RequestSplitComponent.ViewComponent.HEADER);
        panelManager.removeRequestViewFactory("RequestSplit", RequestSplitBodyViewFactory.NAME);
        panelManager.removeRequestViews(
                "RequestSplit", RequestSplitBodyViewFactory.NAME, RequestSplitComponent.ViewComponent.BODY);
        panelManager.removeResponseViewFactory("ResponseSplit", ResponseSplitHeaderViewFactory.NAME);
        panelManager.removeResponseViews(
                "ResponseSplit", ResponseSplitHeaderViewFactory.NAME, RequestSplitComponent.ViewComponent.HEADER);
        panelManager.removeResponseViewFactory("ResponseSplit", ResponseSplitBodyViewFactory.NAME);
        panelManager.removeResponseViews(
                "ResponseSplit", ResponseSplitBodyViewFactory.NAME, RequestSplitComponent.ViewComponent.BODY);
        panelManager.removeRequestViewFactory("RequestAll", RequestAllViewFactory.NAME);
        panelManager.removeRequestViews("RequestAll", RequestAllViewFactory.NAME, null);
        panelManager.removeResponseViewFactory("ResponseAll", ResponseAllViewFactory.NAME);
        panelManager.removeResponseViews("ResponseAll", ResponseAllViewFactory.NAME, null);
    }

    private static final class RequestSplitHeaderViewFactory implements HttpPanelManager.HttpPanelViewFactory {
        private static final String NAME = "PrettifyRequestSplitHeaderViewFactory";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public HttpPanelView getNewView() {
            return new PrettifyHttpPanelView(
                    "PrettifyRequestSplitHeader",
                    CAPTION_KEY,
                    "prettify/http-request-head",
                    PrettifyHttpPanelView.Target.REQUEST_HEAD,
                    new RequestHeaderStringHttpPanelViewModel());
        }

        @Override
        public Object getOptions() {
            return RequestSplitComponent.ViewComponent.HEADER;
        }
    }

    private static final class RequestSplitBodyViewFactory implements HttpPanelManager.HttpPanelViewFactory {
        private static final String NAME = "PrettifyRequestSplitBodyViewFactory";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public HttpPanelView getNewView() {
            return new PrettifyHttpPanelView(
                    "PrettifyRequestSplitBody",
                    CAPTION_KEY,
                    null,
                    PrettifyHttpPanelView.Target.REQUEST_BODY,
                    new RequestBodyStringHttpPanelViewModel());
        }

        @Override
        public Object getOptions() {
            return RequestSplitComponent.ViewComponent.BODY;
        }
    }

    private static final class ResponseSplitHeaderViewFactory implements HttpPanelManager.HttpPanelViewFactory {
        private static final String NAME = "PrettifyResponseSplitHeaderViewFactory";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public HttpPanelView getNewView() {
            return new PrettifyHttpPanelView(
                    "PrettifyResponseSplitHeader",
                    CAPTION_KEY,
                    "prettify/http-response-head",
                    PrettifyHttpPanelView.Target.RESPONSE_HEAD,
                    new ResponseHeaderStringHttpPanelViewModel());
        }

        @Override
        public Object getOptions() {
            return RequestSplitComponent.ViewComponent.HEADER;
        }
    }

    private static final class ResponseSplitBodyViewFactory implements HttpPanelManager.HttpPanelViewFactory {
        private static final String NAME = "PrettifyResponseSplitBodyViewFactory";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public HttpPanelView getNewView() {
            return new PrettifyHttpPanelView(
                    "PrettifyResponseSplitBody",
                    CAPTION_KEY,
                    null,
                    PrettifyHttpPanelView.Target.RESPONSE_BODY,
                    new ResponseBodyStringHttpPanelViewModel());
        }

        @Override
        public Object getOptions() {
            return RequestSplitComponent.ViewComponent.BODY;
        }
    }

    private static final class RequestAllViewFactory implements HttpPanelManager.HttpPanelViewFactory {
        private static final String NAME = "PrettifyRequestAllViewFactory";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public HttpPanelView getNewView() {
            return new PrettifyHttpPanelView(
                    "PrettifyRequestAll",
                    CAPTION_KEY,
                    "prettify/http-request",
                    PrettifyHttpPanelView.Target.REQUEST_ALL,
                    new RequestStringHttpPanelViewModel());
        }

        @Override
        public Object getOptions() {
            return null;
        }
    }

    private static final class ResponseAllViewFactory implements HttpPanelManager.HttpPanelViewFactory {
        private static final String NAME = "PrettifyResponseAllViewFactory";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public HttpPanelView getNewView() {
            return new PrettifyHttpPanelView(
                    "PrettifyResponseAll",
                    CAPTION_KEY,
                    "prettify/http-response",
                    PrettifyHttpPanelView.Target.RESPONSE_ALL,
                    new ResponseStringHttpPanelViewModel());
        }

        @Override
        public Object getOptions() {
            return null;
        }
    }
}
