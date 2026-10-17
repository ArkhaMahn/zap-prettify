package org.zaproxy.zap.extension.prettify;

/** Maps a {@code Content-Type} header value to an RSTA syntax editing style key. */
public final class ContentType {

    public static final String FORM_STYLE = "prettify/form";
    public static final String PLAIN_STYLE = "text/plain";

    private ContentType() {}

    public static String styleKeyFor(String contentType) {
        if (contentType == null) {
            return PLAIN_STYLE;
        }
        String ct = contentType.toLowerCase().trim();
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }
        if (ct.isEmpty()) {
            return PLAIN_STYLE;
        }
        if (ct.contains("x-www-form-urlencoded") || ct.contains("multipart/form-data")) {
            return FORM_STYLE;
        }
        if (ct.contains("json")) {
            return "text/json";
        }
        if (ct.contains("xml")) {
            return "text/xml";
        }
        if (ct.contains("html")) {
            return "text/html";
        }
        if (ct.contains("javascript") || ct.contains("ecmascript")) {
            return "text/javascript";
        }
        if (ct.contains("css")) {
            return "text/css";
        }
        return PLAIN_STYLE;
    }
}
