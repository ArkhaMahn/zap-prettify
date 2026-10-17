package org.zaproxy.zap.extension.prettify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pretty-prints HTTP message bodies the way Burp Suite's message editor "Pretty" tab does: JSON,
 * XML, HTML, CSS and JavaScript are re-indented with two spaces per nesting level and line breaks
 * are inserted at the standard structural points.
 *
 * <p>Formatting is best-effort and never throws: on any problem the original text is returned
 * unchanged.
 */
public final class PrettyPrint {

    /** Burp's standardized indentation is two spaces per level. */
    private static final String INDENT = "  ";

    private static final Set<String> HTML_VOID =
            Set.of(
                    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
                    "param", "source", "track", "wbr");

    private PrettyPrint() {}

    public static String formatBody(String styleKey, String body) {
        if (body == null || body.isEmpty() || styleKey == null) {
            return body;
        }
        try {
            switch (styleKey) {
                case "text/json":
                    return json(body);
                case "text/xml":
                    return markup(body, false);
                case "text/html":
                    return markup(body, true);
                case "text/css":
                    return braces(body, false, false);
                case "text/javascript":
                    return braces(body, true, true);
                default:
                    return body;
            }
        } catch (RuntimeException e) {
            return body;
        }
    }

    /** Formats the body of a full message (status/request line + headers + body). */
    public static String formatMessage(String bodyStyleKey, String message) {
        if (message == null || bodyStyleKey == null) {
            return message;
        }
        int split = bodyOffset(message);
        if (split < 0 || split >= message.length()) {
            return message;
        }
        return message.substring(0, split) + formatBody(bodyStyleKey, message.substring(split));
    }

    /** Returns the offset of the first byte of the body, or -1 if the message has no body. */
    private static int bodyOffset(String message) {
        int n = message.length();
        for (int i = 0; i < n; i++) {
            if (message.charAt(i) != '\n') {
                continue;
            }
            int j = i + 1;
            if (j < n && message.charAt(j) == '\r') {
                j++;
            }
            if (j < n && message.charAt(j) == '\n') {
                return j + 1;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------

    private static String json(String s) {
        StringBuilder out = new StringBuilder(s.length() + 64);
        int indent = 0;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                int e = endOfString(s, i, '"');
                out.append(s, i, e);
                i = e;
                continue;
            }
            switch (c) {
                case '{':
                case '[':
                    {
                        int j = nextNonWs(s, i + 1);
                        char close = (c == '{') ? '}' : ']';
                        if (j < n && s.charAt(j) == close) {
                            out.append(c).append(close);
                            i = j + 1;
                        } else {
                            out.append(c);
                            indent++;
                            newline(out, indent);
                            i++;
                        }
                        break;
                    }
                case '}':
                case ']':
                    indent = Math.max(0, indent - 1);
                    newline(out, indent);
                    out.append(c);
                    i++;
                    break;
                case ',':
                    out.append(',');
                    newline(out, indent);
                    i++;
                    break;
                case ':':
                    out.append(": ");
                    i++;
                    break;
                case ' ':
                case '\t':
                case '\r':
                case '\n':
                    i++;
                    break;
                default:
                    out.append(c);
                    i++;
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------
    // CSS / JavaScript
    // ---------------------------------------------------------------------

    private static String braces(String s, boolean breakCommaInBraces, boolean js) {
        StringBuilder out = new StringBuilder(s.length() + 64);
        Deque<Character> stack = new ArrayDeque<>();
        int indent = 0;
        int n = s.length();
        int i = 0;
        boolean atLineStart = true;
        boolean pendingSpace = false;
        char last = 0;
        while (i < n) {
            char c = s.charAt(i);

            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                e = (e < 0) ? n : e + 2;
                spaceBefore(out, pendingSpace, atLineStart, last);
                out.append(s, i, e);
                atLineStart = false;
                pendingSpace = false;
                last = s.charAt(e - 1);
                i = e;
                continue;
            }
            if (js && c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int e = i;
                while (e < n && s.charAt(e) != '\n' && s.charAt(e) != '\r') {
                    e++;
                }
                spaceBefore(out, pendingSpace, atLineStart, last);
                out.append(s, i, e);
                line(out, indent);
                atLineStart = true;
                pendingSpace = false;
                last = s.charAt(e - 1);
                i = e;
                continue;
            }
            if (js && c == '/' && i + 1 < n && regexContext(s, i)) {
                int e = endOfRegex(s, i);
                spaceBefore(out, pendingSpace, atLineStart, last);
                out.append(s, i, e);
                atLineStart = false;
                pendingSpace = false;
                last = s.charAt(e - 1);
                i = e;
                continue;
            }
            if (c == '"' || c == '\'' || (js && c == '`')) {
                int e = endOfString(s, i, c);
                spaceBefore(out, pendingSpace, atLineStart, last);
                out.append(s, i, e);
                atLineStart = false;
                pendingSpace = false;
                last = c;
                i = e;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                if (!atLineStart) {
                    pendingSpace = true;
                }
                i++;
                continue;
            }

            switch (c) {
                case '{':
                case '(':
                case '[':
                    spaceBefore(out, pendingSpace, atLineStart, last);
                    out.append(c);
                    stack.push(c);
                    atLineStart = false;
                    pendingSpace = false;
                    last = c;
                    if (c == '{') {
                        indent++;
                        line(out, indent);
                        atLineStart = true;
                    }
                    i++;
                    break;
                case '}':
                case ')':
                case ']':
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    if (c == '}') {
                        indent = Math.max(0, indent - 1);
                        line(out, indent);
                        out.append('}');
                        atLineStart = false;
                        pendingSpace = false;
                        last = '}';
                        int j = nextNonWs(s, i + 1);
                        char next = (j < n) ? s.charAt(j) : 0;
                        if (next != ';' && next != ',' && next != ')' && next != ']') {
                            line(out, indent);
                            atLineStart = true;
                        }
                    } else {
                        out.append(c);
                        atLineStart = false;
                        pendingSpace = false;
                        last = c;
                    }
                    i++;
                    break;
                case ';':
                    out.append(';');
                    Character semicolonTop = stack.peek();
                    if (semicolonTop != null && semicolonTop == '(') {
                        atLineStart = false;
                        pendingSpace = false;
                    } else {
                        line(out, indent);
                        atLineStart = true;
                        pendingSpace = false;
                    }
                    last = ';';
                    i++;
                    break;
                case ',':
                    spaceBefore(out, pendingSpace, atLineStart, last);
                    out.append(',');
                    Character commaTop = stack.peek();
                    if (breakCommaInBraces && commaTop != null && commaTop == '{') {
                        line(out, indent);
                        atLineStart = true;
                    } else {
                        atLineStart = false;
                        pendingSpace = true;
                    }
                    last = ',';
                    i++;
                    break;
                default:
                    spaceBefore(out, pendingSpace, atLineStart, last);
                    out.append(c);
                    atLineStart = false;
                    pendingSpace = false;
                    last = c;
                    i++;
            }
        }
        return out.toString();
    }

    private static void spaceBefore(
            StringBuilder out, boolean pendingSpace, boolean atLineStart, char last) {
        if (pendingSpace && !atLineStart && !isOpening(last)) {
            out.append(' ');
        }
    }

    private static boolean isOpening(char c) {
        return c == '(' || c == '[' || c == '{';
    }

    /** Keywords after which a {@code /} starts a regular expression rather than a division. */
    private static final Set<String> REGEX_KEYWORDS =
            Set.of(
                    "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "do",
                    "else", "case", "yield", "await", "throw");

    /** Heuristic: is a {@code /} at {@code slashIdx} the start of a JavaScript regex literal? */
    private static boolean regexContext(String s, int slashIdx) {
        int j = slashIdx - 1;
        while (j >= 0 && Character.isWhitespace(s.charAt(j))) {
            j--;
        }
        if (j < 0) {
            return true;
        }
        char p = s.charAt(j);
        if ("([{,;:!&|?+-*%~^<=>".indexOf(p) >= 0) {
            return true;
        }
        if (Character.isLetterOrDigit(p) || p == '_' || p == '$') {
            int k = j;
            while (k >= 0
                    && (Character.isLetterOrDigit(s.charAt(k))
                            || s.charAt(k) == '_'
                            || s.charAt(k) == '$')) {
                k--;
            }
            return REGEX_KEYWORDS.contains(s.substring(k + 1, j + 1));
        }
        return false;
    }

    /** Returns the end index (exclusive) of a regex literal starting at {@code start}. */
    private static int endOfRegex(String s, int start) {
        int n = s.length();
        int i = start + 1;
        boolean inClass = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\n' || c == '\r') {
                return i;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                i++;
                break;
            }
            i++;
        }
        while (i < n && Character.isLetter(s.charAt(i))) {
            i++;
        }
        return Math.min(i, n);
    }

    // ---------------------------------------------------------------------
    // XML / HTML
    // ---------------------------------------------------------------------

    private static final int T_TEXT = 0;
    private static final int T_OPEN = 1;
    private static final int T_CLOSE = 2;
    private static final int T_OTHER = 3;

    private static final class Piece {
        int type;
        int start;
        int end;
        String tag;
        boolean selfClosing;
    }

    private static final class Element {
        Piece open;
        Piece close;
        final List<Object> children = new ArrayList<>();
    }

    private static String markup(String s, boolean html) {
        List<Piece> pieces = tokenizeMarkup(s);
        List<Object> tree = buildTree(pieces, html);
        StringBuilder out = new StringBuilder(s.length() + 64);
        render(tree, 0, s, html, out);
        return out.toString();
    }

    private static List<Piece> tokenizeMarkup(String s) {
        List<Piece> pieces = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0) {
                addText(pieces, i, n);
                break;
            }
            if (lt > i) {
                addText(pieces, i, lt);
            }
            if (s.startsWith("<!--", lt)) {
                int e = s.indexOf("-->", lt + 4);
                e = (e < 0) ? n : e + 3;
                addOther(pieces, lt, e);
                i = e;
            } else if (s.startsWith("<![CDATA[", lt)) {
                int e = s.indexOf("]]>", lt + 9);
                e = (e < 0) ? n : e + 3;
                addOther(pieces, lt, e);
                i = e;
            } else if (s.startsWith("<!", lt) || s.startsWith("<?", lt)) {
                int e = s.indexOf('>', lt + 2);
                e = (e < 0) ? n : e + 1;
                addOther(pieces, lt, e);
                i = e;
            } else {
                int e = findTagEnd(s, lt);
                String inner = s.substring(lt + 1, e);
                Piece p = new Piece();
                p.start = lt;
                p.end = e + 1;
                if (inner.startsWith("/")) {
                    p.type = T_CLOSE;
                    p.tag = inner.substring(1).trim();
                } else {
                    p.type = T_OPEN;
                    p.selfClosing = inner.endsWith("/");
                    String name = inner;
                    int sp = firstWs(name);
                    if (sp >= 0) {
                        name = name.substring(0, sp);
                    }
                    if (name.endsWith("/")) {
                        name = name.substring(0, name.length() - 1);
                    }
                    p.tag = name;
                }
                pieces.add(p);
                i = e + 1;
            }
        }
        return pieces;
    }

    private static void addText(List<Piece> pieces, int start, int end) {
        Piece p = new Piece();
        p.type = T_TEXT;
        p.start = start;
        p.end = end;
        pieces.add(p);
    }

    private static void addOther(List<Piece> pieces, int start, int end) {
        Piece p = new Piece();
        p.type = T_OTHER;
        p.start = start;
        p.end = end;
        pieces.add(p);
    }

    private static int findTagEnd(String s, int lt) {
        int n = s.length();
        char quote = 0;
        for (int i = lt + 1; i < n; i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return n - 1;
    }

    private static int firstWs(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<Object> buildTree(List<Piece> pieces, boolean html) {
        List<Object> root = new ArrayList<>();
        Deque<Element> stack = new ArrayDeque<>();
        Deque<List<Object>> lists = new ArrayDeque<>();
        lists.push(root);
        for (Piece p : pieces) {
            List<Object> current = lists.peek();
            if (p.type == T_TEXT || p.type == T_OTHER) {
                current.add(p);
                continue;
            }
            if (p.type == T_OPEN) {
                Element e = new Element();
                e.open = p;
                current.add(e);
                boolean voidEl =
                        p.selfClosing || (html && HTML_VOID.contains(p.tag.toLowerCase(Locale.ROOT)));
                if (!voidEl) {
                    stack.push(e);
                    lists.push(e.children);
                }
                continue;
            }
            // close tag
            if (!stack.isEmpty() && stack.peek().open.tag.equalsIgnoreCase(p.tag)) {
                Element e = stack.pop();
                lists.pop();
                e.close = p;
            } else {
                current.add(p);
            }
        }
        return root;
    }

    private static void render(
            List<Object> nodes, int indent, String src, boolean html, StringBuilder out) {
        for (Object node : nodes) {
            if (node instanceof Piece) {
                Piece p = (Piece) node;
                if (p.type == T_TEXT) {
                    String t = src.substring(p.start, p.end).trim();
                    if (!t.isEmpty()) {
                        if (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
                            out.append(' ');
                        }
                        out.append(collapseWs(t));
                    }
                } else {
                    line(out, indent);
                    out.append(collapseWs(src.substring(p.start, p.end)));
                    out.append('\n');
                }
                continue;
            }
            Element e = (Element) node;
            if (isInline(e, src)) {
                line(out, indent);
                out.append(collapseWs(src.substring(e.open.start, e.close.end).trim()));
                out.append('\n');
            } else {
                line(out, indent);
                out.append(collapseWs(src.substring(e.open.start, e.open.end)));
                out.append('\n');
                render(e.children, indent + 1, src, html, out);
                if (e.close != null) {
                    line(out, indent);
                    out.append(collapseWs(src.substring(e.close.start, e.close.end)));
                    out.append('\n');
                }
            }
        }
    }

    private static boolean isInline(Element e, String src) {
        for (Object child : e.children) {
            if (child instanceof Piece) {
                Piece p = (Piece) child;
                if (p.type == T_TEXT && !src.substring(p.start, p.end).trim().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String collapseWs(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean ws = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                ws = true;
            } else {
                if (ws && b.length() > 0) {
                    b.append(' ');
                }
                ws = false;
                b.append(c);
            }
        }
        return b.toString();
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    /** Appends a newline followed by {@code indent} levels of indentation. */
    private static void newline(StringBuilder out, int indent) {
        int l = out.length();
        while (l > 0 && (out.charAt(l - 1) == ' ' || out.charAt(l - 1) == '\t')) {
            l--;
        }
        out.setLength(l);
        out.append('\n');
        appendIndent(out, indent);
    }

    /** Starts a fresh indented line, trimming any trailing whitespace from the previous line. */
    private static void line(StringBuilder out, int indent) {
        int l = out.length();
        while (l > 0 && Character.isWhitespace(out.charAt(l - 1))) {
            l--;
        }
        out.setLength(l);
        if (out.length() > 0) {
            out.append('\n');
        }
        appendIndent(out, indent);
    }

    private static void appendIndent(StringBuilder out, int indent) {
        for (int i = 0; i < indent; i++) {
            out.append(INDENT);
        }
    }

    private static int nextNonWs(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int endOfString(String s, int start, char quote) {
        int n = s.length();
        int i = start + 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return n;
    }
}
