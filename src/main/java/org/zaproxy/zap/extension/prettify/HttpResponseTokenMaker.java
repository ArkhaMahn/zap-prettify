package org.zaproxy.zap.extension.prettify;

/** Token maker for a full HTTP response (status line, headers and body). */
public class HttpResponseTokenMaker extends HttpTokenMaker {

    public HttpResponseTokenMaker() {
        super(true, false);
    }
}
