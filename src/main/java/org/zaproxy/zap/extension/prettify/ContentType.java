package org.zaproxy.zap.extension.prettify;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps a {@code Content-Type} header value to an RSTA syntax editing style key.
 *
 * <p>Beyond the common types this understands the {@code +json} / {@code +xml} structured syntax
 * suffixes (so vendor types such as {@code application/vnd.api+json} or {@code image/svg+xml} are
 * highlighted), a wide set of textual types, and - when the type is unknown, missing or
 * {@code text/plain} - a conservative content sniff of the body prefix.
 */
public final class ContentType {

    public static final String FORM_STYLE = "prettify/form";
    public static final String GRAPHQL_STYLE = "prettify/graphql";
    public static final String PLAIN_STYLE = "text/plain";

    private static final String JSON = "text/json";
    private static final String XML = "text/xml";
    private static final String HTML = "text/html";
    private static final String JAVASCRIPT = "text/javascript";
    private static final String TYPESCRIPT = "text/typescript";
    private static final String CSS = "text/css";
    private static final String LESS = "text/less";
    private static final String YAML = "text/yaml";
    private static final String CSV = "text/csv";
    private static final String MARKDOWN = "text/markdown";
    private static final String PHP = "text/php";
    private static final String SHELL = "text/unix";
    private static final String SQL = "text/sql";
    private static final String PYTHON = "text/python";
    private static final String RUBY = "text/ruby";
    private static final String PERL = "text/perl";
    private static final String INI = "text/ini";
    private static final String PROPERTIES = "text/properties";

    /** Characters of body text examined when the content type alone is not decisive. */
    private static final int SNIFF_LIMIT = 4096;

    /** Leading forms that only appear in JavaScript sources (including minified bundles). */
    private static final String[] JS_PREFIXES = {
        "!function",
        "(function",
        "(()=>",
        "(async",
        "async function",
        "function ",
        "function(",
        "function*",
        "async(",
        "var ",
        "let ",
        "const ",
        "import ",
        "import{",
        "import(",
        "export ",
        "export{",
        "window.",
        "document.",
        "module.exports",
        "define(",
        "class ",
        "\"use strict\"",
        "'use strict'"
    };

    /** {@code callback({...})} / {@code jQuery123([...])} style JSONP wrapper. */
    private static final Pattern JSONP = Pattern.compile("^[A-Za-z_$][\\w$.]*\\s*\\(\\s*[\\[{]");

    private ContentType() {}

    /**
     * Resolves a style from the content type alone. Returns {@link #PLAIN_STYLE} when nothing is
     * recognised (callers that have the body may use {@link #styleKeyFor(String, String)} instead).
     */
    public static String styleKeyFor(String contentType) {
        if (contentType == null) {
            return PLAIN_STYLE;
        }
        String ct = normalize(contentType);
        if (ct.isEmpty()) {
            return PLAIN_STYLE;
        }
        // Binary payloads need no highlighting and must never be sniffed.
        if (isBinary(ct)) {
            return PLAIN_STYLE;
        }
        if (contains(ct, "x-www-form-urlencoded") || contains(ct, "multipart/form-data")) {
            return FORM_STYLE;
        }
        if (contains(ct, "jsonp")) {
            return JAVASCRIPT;
        }
        if (endsWith(ct, "+json") || contains(ct, "json")) {
            return JSON;
        }
        if (endsWith(ct, "+xml") || contains(ct, "xml")) {
            return contains(ct, "xhtml") ? HTML : XML;
        }
        if (contains(ct, "graphql")) {
            return GRAPHQL_STYLE;
        }
        if (contains(ct, "html")) {
            return HTML;
        }
        if (isJavaScript(ct)) {
            return JAVASCRIPT;
        }
        if (contains(ct, "typescript")) {
            return TYPESCRIPT;
        }
        if (contains(ct, "scss") || contains(ct, "sass")) {
            return CSS;
        }
        if (contains(ct, "less")) {
            return LESS;
        }
        if (contains(ct, "css")) {
            return CSS;
        }
        if (contains(ct, "yaml") || contains(ct, "yml")) {
            return YAML;
        }
        if (contains(ct, "tab-separated-values") || contains(ct, "csv")) {
            return CSV;
        }
        if (contains(ct, "markdown") || contains(ct, "x-markdown")) {
            return MARKDOWN;
        }
        if (contains(ct, "php")) {
            return PHP;
        }
        if (contains(ct, "shellscript")
                || contains(ct, "x-sh")
                || contains(ct, "x-bash")
                || contains(ct, "x-csh")
                || endsWith(ct, "/sh")) {
            return SHELL;
        }
        if (contains(ct, "sql")) {
            return SQL;
        }
        if (contains(ct, "python")) {
            return PYTHON;
        }
        if (contains(ct, "ruby")) {
            return RUBY;
        }
        if (contains(ct, "perl")) {
            return PERL;
        }
        if (contains(ct, "toml") || contains(ct, "x-ini") || endsWith(ct, "/ini")) {
            return INI;
        }
        if (contains(ct, "properties")) {
            return PROPERTIES;
        }
        return PLAIN_STYLE;
    }

    /**
     * Resolves a style for a body, falling back to a sniff of {@code bodyPrefix} when the content
     * type is unknown, missing or {@code text/plain}. Binary types are never sniffed.
     */
    public static String styleKeyFor(String contentType, String bodyPrefix) {
        String style = styleKeyFor(contentType);
        if (!PLAIN_STYLE.equals(style)) {
            return style;
        }
        if (contentType != null && isBinary(normalize(contentType))) {
            return PLAIN_STYLE;
        }
        return sniff(bodyPrefix);
    }

    /**
     * Very conservative sniff of a body prefix: the unambiguous leading markers for JSON, HTML, XML
     * and JavaScript are recognised, so a mis-guess cannot turn genuinely plain text into colourful
     * noise.
     */
    static String sniff(String body) {
        if (body == null || body.isEmpty()) {
            return PLAIN_STYLE;
        }
        int n = Math.min(body.length(), SNIFF_LIMIT);
        int i = 0;
        // Skip a UTF-8 BOM, leading whitespace and license banners such as /*! bundle v1 */.
        if (n >= 3 && body.charAt(0) == '\uFEFF') {
            i = 1;
        }
        i = skipWhitespace(body, i, n);
        // Node scripts start with a hashbang line.
        if (i + 1 < n && body.charAt(i) == '#' && body.charAt(i + 1) == '!') {
            int nl = body.indexOf('\n', i + 2);
            i = nl < 0 || nl >= n ? n : skipWhitespace(body, nl + 1, n);
        }
        for (int guard = 0; guard < 4 && i + 1 < n; guard++) {
            if (body.charAt(i) != '/' || body.charAt(i + 1) != '*') {
                break;
            }
            int close = body.indexOf("*/", i + 2);
            if (close < 0 || close >= n) {
                break;
            }
            i = skipWhitespace(body, close + 2, n);
        }
        if (i >= n) {
            return PLAIN_STYLE;
        }
        char c = body.charAt(i);
        String prefix = body.substring(i, Math.min(n, i + 512));
        if (c == '"' || c == '\'') {
            if (prefix.startsWith("\"use strict\"") || prefix.startsWith("'use strict'")) {
                return JAVASCRIPT;
            }
            // A bare double-quoted JSON string document is uncommon but unambiguous enough.
            return c == '"' ? JSON : PLAIN_STYLE;
        }
        if (c == '{' || c == '[') {
            return JSON;
        }
        if (c == '<') {
            String head = prefix.toLowerCase(Locale.ROOT);
            if (head.startsWith("<!--")
                    || head.startsWith("<html")
                    || head.startsWith("<!doctype html")
                    || head.contains("<html")) {
                return HTML;
            }
            if (head.startsWith("<?xml") || head.contains("<svg")) {
                return XML;
            }
            return XML;
        }
        if (looksLikeJavaScript(prefix)) {
            return JAVASCRIPT;
        }
        if (JSONP.matcher(prefix).lookingAt()) {
            return JAVASCRIPT;
        }
        return PLAIN_STYLE;
    }

    private static int skipWhitespace(String body, int i, int n) {
        while (i < n && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean looksLikeJavaScript(String prefix) {
        for (String marker : JS_PREFIXES) {
            if (prefix.startsWith(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a normalised content type denotes JavaScript, including vendor and shorthand forms
     * such as {@code application/x-js}, {@code text/js}, {@code application/vnd.acme.js} and the
     * {@code +js} structured suffix.
     */
    private static boolean isJavaScript(String ct) {
        if (contains(ct, "javascript")
                || contains(ct, "ecmascript")
                || contains(ct, "jscript")) {
            return true;
        }
        int slash = ct.indexOf('/');
        String subtype = slash < 0 ? ct : ct.substring(slash + 1);
        return subtype.equals("js")
                || subtype.equals("x-js")
                || subtype.equals("node")
                || subtype.endsWith("-js")
                || subtype.endsWith(".js")
                || subtype.endsWith("+js");
    }

    /** Whether a style key selects the HTML/XML markup token maker. */
    public static boolean isHtmlStyle(String styleKey) {
        return HTML.equals(styleKey);
    }

    /** Whether a style key selects the JavaScript token maker. */
    public static boolean isJavaScriptStyle(String styleKey) {
        return JAVASCRIPT.equals(styleKey);
    }

    private static String normalize(String contentType) {
        String ct = contentType.toLowerCase(Locale.ROOT).trim();
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }
        return ct;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack.contains(needle);
    }

    private static boolean endsWith(String haystack, String suffix) {
        return haystack.endsWith(suffix);
    }

    /** Whether a (normalised) content type is a binary payload that should never be highlighted. */
    private static boolean isBinary(String ct) {
        if (ct.startsWith("image/")) {
            return !ct.contains("svg");
        }
        if (ct.startsWith("audio/") || ct.startsWith("video/") || ct.startsWith("font/")) {
            return true;
        }
        switch (ct) {
            case "application/octet-stream":
            case "application/pdf":
            case "application/zip":
            case "application/gzip":
            case "application/x-gzip":
            case "application/x-tar":
            case "application/x-7z-compressed":
            case "application/x-rar-compressed":
            case "application/wasm":
            case "application/x-protobuf":
            case "application/vnd.google.protobuf":
                return true;
            default:
                return ct.startsWith("application/x-font")
                        || ct.startsWith("application/vnd.ms-")
                        || ct.startsWith("application/vnd.openxmlformats-")
                        || ct.startsWith("application/vnd.oasis.opendocument")
                        || ct.startsWith("application/x-shockwave")
                        || ct.startsWith("application/x-msdownload")
                        || ct.startsWith("application/x-executable")
                        || ct.startsWith("application/vnd.android.package");
        }
    }
}
