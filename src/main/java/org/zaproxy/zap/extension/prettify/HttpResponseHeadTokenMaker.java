package org.zaproxy.zap.extension.prettify;

/** Token maker for the header block of an HTTP response (status line and headers only). */
public class HttpResponseHeadTokenMaker extends HttpTokenMaker {

    public HttpResponseHeadTokenMaker() {
        super(true, true);
    }
}
