package org.zaproxy.zap.extension.prettify;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps a {@code Content-Type} header value to an RSTA syntax editing style key.
 *
 * <p>Beyond the common types this understands the {@code +json} / {@code +xml} structured syntax
 * suffixes (so vendor types such as {@code application/vnd.api+json} or {@code image/svg+xml} are
 * highlighted), a wide set of textual types, and - when the type is unknown, missing or
 * {@code text/plain} - a conservative content sniff of the body prefix. The sniff recognises JSON,
 * markup, JavaScript (including bundles behind a license comment), CSS and GraphQL; binary and
 * protobuf payloads are never highlighted or sniffed.
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

    /**
     * Named GraphQL operations. The keyword must be followed by an operation name or an opening
     * bracket so a JavaScript variable called {@code query} is not mistaken for GraphQL.
     */
    private static final Pattern GRAPHQL_OPERATION =
            Pattern.compile(
                    "^(?:(?:query|mutation|subscription|fragment)\\s+[A-Za-z_][\\w]*\\s*(?:\\(|\\{)"
                            + "|(?:query|mutation|subscription)\\s*(?:\\(|\\{))");

    /** Anonymous GraphQL selection: a field name rather than a quoted JSON key. */
    private static final Pattern GRAPHQL_SELECTION =
            Pattern.compile("^\\{\\s*[A-Za-z_][\\w]*\\s*(?:[({]|\\}|\\s+[A-Za-z_])");

    /** The characters a CSS selector or at-rule may contain before its opening brace. */
    private static final Pattern CSS_SELECTOR =
            Pattern.compile("[A-Za-z0-9_*.#:\\[\\]=\"'(),>+~|&\\s-]+");

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
     * The content type header value for a style, or null when the style does not name one. Used to
     * fill in a missing content type of a request from the body it carries.
     */
    public static String contentTypeFor(String styleKey) {
        if (styleKey == null) {
            return null;
        }
        switch (styleKey) {
            case JSON:
                return "application/json";
            case HTML:
                return "text/html";
            case XML:
                return "text/xml";
            case JAVASCRIPT:
                return "application/javascript";
            case TYPESCRIPT:
                return "application/typescript";
            case CSS:
                return "text/css";
            case LESS:
                return "text/less";
            case YAML:
                return "application/yaml";
            case CSV:
                return "text/csv";
            case MARKDOWN:
                return "text/markdown";
            case PHP:
                return "application/x-httpd-php";
            case SHELL:
                return "application/x-sh";
            case SQL:
                return "application/sql";
            case PYTHON:
                return "text/x-python";
            case RUBY:
                return "text/x-ruby";
            case PERL:
                return "text/x-perl";
            case GRAPHQL_STYLE:
                return "application/graphql";
            case FORM_STYLE:
                return "application/x-www-form-urlencoded";
            default:
                return null;
        }
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
        // Never colour a binary payload, whatever its declared (or missing) content type.
        if (looksBinary(body, n)) {
            return PLAIN_STYLE;
        }
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
        // Skip leading comments, both block banners and // license lines (common in bundles).
        for (int guard = 0; guard < 8 && i + 1 < n; guard++) {
            if (body.charAt(i) == '/' && body.charAt(i + 1) == '/') {
                int nl = body.indexOf('\n', i + 2);
                if (nl < 0 || nl >= n) {
                    i = n;
                    break;
                }
                i = skipWhitespace(body, nl + 1, n);
                continue;
            }
            // A GraphQL comment starts with "# " and runs to the end of the line. Requiring the
            // space keeps a CSS id selector such as "#main{" from being skipped.
            if (body.charAt(i) == '#'
                    && (body.charAt(i + 1) == ' ' || body.charAt(i + 1) == '\t')) {
                int nl = body.indexOf('\n', i + 2);
                if (nl < 0 || nl >= n) {
                    i = n;
                    break;
                }
                i = skipWhitespace(body, nl + 1, n);
                continue;
            }
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
        if (c == '{') {
            // An unquoted leading field name is GraphQL, not JSON.
            return GRAPHQL_SELECTION.matcher(prefix).lookingAt() ? GRAPHQL_STYLE : JSON;
        }
        if (c == '[') {
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
        if (GRAPHQL_OPERATION.matcher(prefix).lookingAt()) {
            return GRAPHQL_STYLE;
        }
        if (looksLikeCss(prefix)) {
            return CSS;
        }
        return PLAIN_STYLE;
    }

    /** Whether the sniffed prefix contains bytes that only a binary payload would carry. */
    private static boolean looksBinary(String body, int n) {
        int controls = 0;
        for (int i = 0; i < n; i++) {
            char c = body.charAt(i);
            if (c == '\u0000') {
                return true;
            }
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f') {
                if (++controls > 8) {
                    return true;
                }
            }
        }
        return false;
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
     * A conservative CSS sniff: an at-rule or a selector followed by a declaration block. Only the
     * first 256 characters are considered so a long minified script cannot be mistaken for CSS.
     */
    private static boolean looksLikeCss(String prefix) {
        String text = prefix.trim();
        if (text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("@")) {
            return lower.startsWith("@charset")
                    || lower.startsWith("@import")
                    || lower.startsWith("@media")
                    || lower.startsWith("@font-face")
                    || lower.startsWith("@keyframes")
                    || lower.startsWith("@supports")
                    || lower.startsWith("@page")
                    || lower.startsWith("@namespace")
                    || lower.startsWith("@layer")
                    || lower.startsWith("@container");
        }
        int brace = text.indexOf('{');
        if (brace <= 0 || brace > 256) {
            return false;
        }
        String selector = text.substring(0, brace).trim();
        if (selector.isEmpty() || selector.contains(";") || selector.contains("=>")) {
            return false;
        }
        return CSS_SELECTOR.matcher(selector).matches();
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

    /** Whether a style key selects a whole-document script token maker. */
    public static boolean isScriptStyle(String styleKey) {
        return JAVASCRIPT.equals(styleKey) || TYPESCRIPT.equals(styleKey);
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
        if (isProtobuf(ct)) {
            return true;
        }
        switch (ct) {
            case "application/octet-stream":
            case "application/pdf":
            case "application/zip":
            case "application/gzip":
            case "application/x-gzip":
            case "application/x-bzip":
            case "application/x-bzip2":
            case "application/x-xz":
            case "application/x-lzma":
            case "application/x-compress":
            case "application/zstd":
            case "application/x-zstd":
            case "application/x-tar":
            case "application/x-7z-compressed":
            case "application/x-rar-compressed":
            case "application/vnd.rar":
            case "application/wasm":
            case "application/cbor":
            case "application/msgpack":
            case "application/x-msgpack":
            case "application/vnd.msgpack":
            case "application/java-archive":
            case "application/x-java-archive":
            case "application/x-iso9660-image":
            case "application/x-apple-diskimage":
            case "application/vnd.debian.binary-package":
            case "application/x-deb":
            case "application/x-rpm":
            case "application/x-msi":
            case "application/x-shockwave-flash":
            case "application/vnd.android.package-archive":
            case "application/epub+zip":
            case "application/vnd.tcpdump.pcap":
                return true;
            default:
                return ct.startsWith("application/x-font")
                        || ct.startsWith("application/vnd.ms-")
                        || ct.startsWith("application/vnd.openxmlformats-")
                        || ct.startsWith("application/vnd.oasis.opendocument")
                        || ct.startsWith("application/x-shockwave")
                        || ct.startsWith("application/x-msdownload")
                        || ct.startsWith("application/x-executable")
                        || ct.startsWith("application/x-mach-binary")
                        || ct.startsWith("application/x-dosexec")
                        || ct.startsWith("application/grpc")
                        || ct.startsWith("application/vnd.android.package");
        }
    }

    /**
     * Whether a (normalised) content type is a protobuf payload, including the {@code +proto}
     * structured suffix and the {@code grpc} transport types.
     */
    private static boolean isProtobuf(String ct) {
        return contains(ct, "protobuf") || endsWith(ct, "/proto") || endsWith(ct, "+proto");
    }
}
