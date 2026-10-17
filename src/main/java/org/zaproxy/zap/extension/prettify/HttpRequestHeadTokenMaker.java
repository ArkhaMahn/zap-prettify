package org.zaproxy.zap.extension.prettify;

/** Token maker for the header block of an HTTP request (first line and headers only). */
public class HttpRequestHeadTokenMaker extends HttpTokenMaker {

    public HttpRequestHeadTokenMaker() {
        super(false, true);
    }
}
