package org.zaproxy.zap.extension.prettify;

/** Token maker for a full HTTP request (first line, headers and body). */
public class HttpRequestTokenMaker extends HttpTokenMaker {

    public HttpRequestTokenMaker() {
        super(false, false);
    }
}
