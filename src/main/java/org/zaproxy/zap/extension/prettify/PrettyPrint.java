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
                    return markup(body, false, styleKey);
                case "text/html":
                    return markup(body, true, styleKey);
                case "text/css":
                    return braces(body, false, styleKey);
                case "text/javascript":
                case "text/typescript":
                    return braces(body, true, styleKey);
                case ContentType.GRAPHQL_STYLE:
                    return graphql(body);
                case ContentType.FORM_STYLE:
                    // Form bodies are left exactly as they are: splitting the parameters across
                    // lines is not worth a formatted projection of a body that is usually short.
                    return body;
                default:
                    return body;
            }
        } catch (RuntimeException e) {
            return body;
        }
    }

    /**
     * Formats text pasted into an editable body. The content type is used when it is decisive;
     * otherwise the pasted text is sniffed. The text is returned unchanged when no formatter
     * applies or when formatting would change a non-whitespace character, so a paste can never
     * alter the message's data.
     */
    public static String formatPastedBody(String contentType, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String style = ContentType.styleKeyFor(contentType, text);
        String formatted = formatBody(style, text);
        if (formatted == null
                || formatted.equals(text)
                || !TextAlignment.sameNonWhitespace(text, formatted)) {
            return text;
        }
        return formatted;
    }

    /** Formats the body of a full message (status/request line + headers + body). */
    public static String formatMessage(String bodyStyleKey, String message) {
        return formatMessage(bodyStyleKey, message, true);
    }

    /**
     * Formats the body of a full message, optionally leaving the body as it is.
     */
    public static String formatMessage(String bodyStyleKey, String message, boolean reformat) {
        if (message == null || bodyStyleKey == null) {
            return message;
        }
        int split = bodyOffset(message);
        if (split < 0 || split >= message.length()) {
            // No body: drop the blank line ZAP keeps after the header block so the headers are not
            // followed by dangling empty lines.
            return stripTrailingNewlines(message);
        }
        String body = message.substring(split);
        return message.substring(0, split) + (reformat ? formatBody(bodyStyleKey, body) : body);
    }

    /**
     * Formats the body of a full message, resolving the body style from {@code contentType} and,
     * when the content type is not decisive, from the body itself. This lets bodies served with a
     * custom content type (or with {@code text/plain}) still be pretty-printed as JSON, markup or
     * JavaScript.
     */
    public static String formatMessageFor(String contentType, String message) {
        return formatMessageFor(contentType, message, true);
    }

    /** Formats a full message, optionally leaving the body as it is (see {@link #formatMessage}). */
    public static String formatMessageFor(String contentType, String message, boolean reformat) {
        if (message == null) {
            return null;
        }
        int split = bodyOffset(message);
        if (split < 0 || split >= message.length()) {
            return stripTrailingNewlines(message);
        }
        String body = message.substring(split);
        return message.substring(0, split)
                + (reformat ? formatBody(ContentType.styleKeyFor(contentType, body), body) : body);
    }

    /**
     * Removes trailing CR/LF characters. ZAP represents a header block as {@code lines + CRLF + CRLF}
     * (the blank line that ends the headers), which reads as extra blank lines when nothing follows.
     */
    public static String stripTrailingNewlines(String s) {
        if (s == null) {
            return null;
        }
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\n' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return end == s.length() ? s : s.substring(0, end);
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

    /** Stack marker for a statement or function block, as opposed to an object literal. */
    private static final char BLOCK_OPEN = '\u0007';

    /** Stack marker for a destructuring pattern, which stays inline. */
    private static final char PATTERN_OPEN = '\u0006';

    private static String braces(String s, boolean breakCommaInBraces, String styleKey) {
        Lexer lex = Lexer.of(styleKey, s);
        if (lex == null) {
            return s;
        }
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

            if (lex.opaque[i]) {
                int j = i;
                while (j < n && lex.opaque[j]) {
                    j++;
                }
                spaceBefore(out, pendingSpace, atLineStart, last);
                out.append(s, i, j);
                atLineStart = false;
                pendingSpace = false;
                last = s.charAt(j - 1);
                if (lex.lineComment[i]) {
                    line(out, indent);
                    atLineStart = true;
                }
                i = j;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                if (!atLineStart) {
                    pendingSpace = true;
                }
                i++;
                continue;
            }

            if (breakCommaInBraces
                    && c == 'c'
                    && startsWord(s, i, "const")
                    && last != '.'
                    && !insideParentheses(stack)
                    && !endsWithWord(out, "export")) {
                // A declaration always starts its own line, which also recovers statement
                // boundaries from minified or newline-separated scripts.
                if (!atLineStart) {
                    line(out, indent);
                }
                out.append("const");
                atLineStart = false;
                pendingSpace = false;
                last = 't';
                i += "const".length();
                continue;
            }

            switch (c) {
                case '{':
                case '(':
                case '[':
                    char open = c;
                    if (c == '{') {
                        if (startsPattern(out)) {
                            open = PATTERN_OPEN;
                        } else if (startsBlock(out, last, stack)) {
                            open = BLOCK_OPEN;
                        }
                    }
                    spaceBefore(out, pendingSpace, atLineStart, last);
                    out.append(c);
                    stack.push(open);
                    atLineStart = false;
                    pendingSpace = false;
                    last = c;
                    if (c == '{') {
                        int j = nextNonWs(s, i + 1);
                        if (open == PATTERN_OPEN) {
                            // Destructuring patterns stay on one line.
                            atLineStart = false;
                        } else if (j < n && s.charAt(j) == '}') {
                            // Keep an empty object on one line.
                            atLineStart = false;
                        } else {
                            indent++;
                            line(out, indent);
                            atLineStart = true;
                        }
                    }
                    i++;
                    break;
                case '}':
                case ')':
                case ']':
                    boolean patternClose = c == '}' && !stack.isEmpty()
                            && stack.peek() == PATTERN_OPEN;
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    if (c == '}') {
                        if (!patternClose && last != '{') {
                            indent = Math.max(0, indent - 1);
                            line(out, indent);
                        }
                        out.append('}');
                        atLineStart = false;
                        pendingSpace = false;
                        last = '}';
                        int j = nextNonWs(s, i + 1);
                        char next = (j < n) ? s.charAt(j) : 0;
                        if (next == '.' || next == '(' || next == '[') {
                            // The block is an expression operand; keep the call or index attached.
                            atLineStart = false;
                            pendingSpace = false;
                        } else if (continuesExpression(next)) {
                            atLineStart = false;
                            pendingSpace = next != '=';
                        } else if (next != ';' && next != ',' && next != ')' && next != ']') {
                            if (breakCommaInBraces
                                    && nextWordIs(s, i + 1, "else", "catch", "finally", "while")) {
                                // Keep the clause attached to the block it belongs to.
                                atLineStart = false;
                                pendingSpace = true;
                            } else {
                                line(out, indent);
                                atLineStart = true;
                            }
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
                case ':':
                    spaceBefore(out, pendingSpace, atLineStart, last);
                    out.append(':');
                    atLineStart = false;
                    last = ':';
                    if (breakCommaInBraces) {
                        int j = nextNonWs(s, i + 1);
                        // Object entries, labels and type annotations read better with a space after
                        // the colon; a following ':' is a CSS pseudo-element or scope operator.
                        pendingSpace = j >= n || s.charAt(j) != ':';
                    } else {
                        pendingSpace = false;
                    }
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
        return trimTrailing(out);
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

    /** Whether {@code word} starts at {@code i} and is not part of a longer identifier. */
    private static boolean startsWord(String s, int i, String word) {
        if (!s.startsWith(word, i)) {
            return false;
        }
        int end = i + word.length();
        return (i == 0 || !isWordChar(s.charAt(i - 1)))
                && (end == s.length() || !isWordChar(s.charAt(end)));
    }

    /**
     * Whether the innermost open construct is a parenthesis (a {@code for} header, for example)
     * rather than a block, so a {@code const} there must stay inline.
     */
    private static boolean insideParentheses(Deque<Character> stack) {
        for (Character open : stack) {
            if (open == '{' || open == BLOCK_OPEN) {
                return false;
            }
            if (open == '(') {
                return true;
            }
        }
        return false;
    }

    /** Whether a brace starts a destructuring pattern, which should stay on one line. */
    private static boolean startsPattern(StringBuilder out) {
        return endsWithWord(out, "var")
                || endsWithWord(out, "let")
                || endsWithWord(out, "const");
    }

    /** Whether the current statement is a bare identifier, i.e. a statement label. */
    private static boolean inLabel(StringBuilder out) {
        String statement = currentStatement(out);
        if (statement.endsWith(":")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (statement.isEmpty() || !isWordChar(statement.charAt(0))) {
            return false;
        }
        for (int i = 1; i < statement.length(); i++) {
            if (!isWordChar(statement.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the current statement is a {@code case}/{@code default} label. */
    private static boolean inCaseLabel(StringBuilder out) {
        String statement = currentStatement(out);
        return statement.startsWith("case") || statement.startsWith("default");
    }

    private static String currentStatement(StringBuilder out) {
        int i = out.length() - 1;
        while (i >= 0) {
            char c = out.charAt(i);
            if (c == ';' || c == '{' || c == '}') {
                break;
            }
            i--;
        }
        return out.substring(i + 1).trim();
    }

    /** Whether {@code c} continues the expression a closing brace belongs to. */
    private static boolean continuesExpression(char c) {
        return c == '=' || c == '+' || c == '-' || c == '*' || c == '/' || c == '%'
                || c == '&' || c == '|' || c == '^' || c == '<' || c == '>' || c == '!'
                || c == '?' || c == ':' || c == '~';
    }

    /**
     * Whether a {@code '{'} opens a statement/function block rather than an object literal, based on
     * the token before it. Object literals follow operators, {@code return} and similar keywords;
     * blocks follow {@code ')'}, statements, {@code else}/{@code do} and declaration names.
     */
    private static boolean startsBlock(StringBuilder out, char last, Deque<Character> stack) {
        if (last == '\0' || last == ')' || last == ';' || last == '}') {
            return true;
        }
        if (last == ':') {
            // Only a case/default label introduces a block; an object value stays an object.
            Character top = stack.peek();
            return (top == null || (top != '{' && top != PATTERN_OPEN))
                    && (inCaseLabel(out) || inLabel(out));
        }
        if (last == '>') {
            int length = out.length();
            return length >= 2 && out.charAt(length - 2) == '=';
        }
        if (endsWithWord(out, "else")
                || endsWithWord(out, "try")
                || endsWithWord(out, "finally")
                || endsWithWord(out, "do")) {
            return true;
        }
        if (isWordChar(last)) {
            // A brace directly after an identifier starts a class/namespace body; after these
            // keywords it introduces an object expression.
            return !(endsWithWord(out, "return")
                    || endsWithWord(out, "yield")
                    || endsWithWord(out, "await")
                    || endsWithWord(out, "case")
                    || endsWithWord(out, "var")
                    || endsWithWord(out, "let")
                    || endsWithWord(out, "const")
                    || endsWithWord(out, "in")
                    || endsWithWord(out, "of")
                    || endsWithWord(out, "delete")
                    || endsWithWord(out, "void")
                    || endsWithWord(out, "typeof")
                    || endsWithWord(out, "new")
                    || endsWithWord(out, "throw"));
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Whether the formatted output ends with {@code word} as a whole word. */
    private static boolean nextWordIs(String s, int from, String... words) {
        int j = nextNonWs(s, from);
        for (String word : words) {
            if (startsWord(s, j, word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithWord(StringBuilder out, String word) {
        int end = out.length();
        while (end > 0 && Character.isWhitespace(out.charAt(end - 1))) {
            end--;
        }
        int start = end - word.length();
        if (start < 0 || !out.substring(start, end).equals(word)) {
            return false;
        }
        return start == 0 || !isWordChar(out.charAt(start - 1));
    }

    // ---------------------------------------------------------------------
    // XML / HTML
    // ---------------------------------------------------------------------

    private static final int T_TEXT = 0;
    private static final int T_OPEN = 1;
    private static final int T_CLOSE = 2;
    private static final int T_OTHER = 3;
    /** Script/style/pre content, copied without markup parsing. */
    private static final int T_RAW_TEXT = 4;

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

    private static String markup(String s, boolean html, String styleKey) {
        Lexer lex = Lexer.of(styleKey, s);
        if (lex == null) {
            return s;
        }
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
            } else if (!isTagStart(s, lt)) {
                // A stray '<' in text rather than a tag start.
                addText(pieces, lt, lt + 1);
                i = lt + 1;
            } else {
                int e = findTagEnd(s, lt);
                if (e < 0) {
                    addText(pieces, lt, lt + 1);
                    i = lt + 1;
                    continue;
                }
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
                if (p.type == T_OPEN && isRawText(p.tag)) {
                    // Script, style, pre and textarea content is not markup: copy it whole so the
                    // closing tag (and any '<' or '>' inside) is not mistaken for a tag. Script and
                    // style bodies are re-indented when rendered; pre/textarea stay verbatim.
                    String closeTag = "</" + p.tag;
                    int close = indexOfIgnoreCase(s, closeTag, i);
                    int rawEnd = (close < 0) ? n : close;
                    addRaw(pieces, i, rawEnd);
                    i = rawEnd;
                }
            }
        }
        return pieces;
    }

    /** Elements whose content is text rather than markup. */
    private static boolean isRawText(String tag) {
        String t = tag.toLowerCase(Locale.ROOT);
        return t.equals("script")
                || t.equals("style")
                || t.equals("pre")
                || t.equals("textarea");
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int limit = s.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
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

    private static void addRaw(List<Piece> pieces, int start, int end) {
        Piece p = new Piece();
        p.type = T_RAW_TEXT;
        p.start = start;
        p.end = end;
        pieces.add(p);
    }

    /**
     * Returns the index of the '>' that ends the tag starting at {@code lt}, or -1 when the '<' is
     * not the start of a well-formed tag. Quotes are tracked so '>' inside an attribute value does
     * not end the tag.
     */
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
            } else if (c == '<') {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isTagStart(String s, int lt) {
        int n = s.length();
        if (lt + 1 >= n) {
            return false;
        }
        char c = s.charAt(lt + 1);
        if (c == '/' || c == '!' || c == '?') {
            return true;
        }
        if (!Character.isLetter(c)) {
            return false;
        }
        return findTagEnd(s, lt) >= 0;
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
            if (p.type != T_OPEN && p.type != T_CLOSE) {
                // Text, comments and raw script/style content all stay in the current list.
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
            String tag = e.open.tag == null ? "" : e.open.tag.toLowerCase(Locale.ROOT);
            if (isRawText(tag)) {
                renderRaw(e, tag, indent, src, out);
            } else if (isInline(e, src)) {
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

    /**
     * Renders a raw-text element. Script and style content is re-indented as JavaScript/CSS; pre and
     * textarea content keeps its exact whitespace.
     */
    private static void renderRaw(
            Element e, String tag, int indent, String src, StringBuilder out) {
        String open = collapseWs(src.substring(e.open.start, e.open.end));
        String close =
                e.close == null ? "" : collapseWs(src.substring(e.close.start, e.close.end));
        String raw = rawText(e, src);
        if (raw.trim().isEmpty()) {
            line(out, indent);
            out.append(open).append(close);
            out.append('\n');
            return;
        }
        if (tag.equals("pre") || tag.equals("textarea")) {
            line(out, indent);
            out.append(src, e.open.start, e.close == null ? e.open.end : e.close.end);
            out.append('\n');
            return;
        }
        line(out, indent);
        out.append(open);
        out.append('\n');
        String formatted =
                tag.equals("style")
                        ? braces(raw, false, "text/css")
                        : braces(raw, true, "text/javascript");
        appendIndented(out, formatted, indent + 1);
        if (e.close != null) {
            line(out, indent);
            out.append(close);
            out.append('\n');
        }
    }

    private static String rawText(Element e, String src) {
        StringBuilder b = new StringBuilder();
        for (Object child : e.children) {
            if (child instanceof Piece) {
                Piece p = (Piece) child;
                if (p.type == T_RAW_TEXT) {
                    b.append(src, p.start, p.end);
                }
            }
        }
        return b.toString();
    }

    /** Appends every non-empty line of {@code text} indented by {@code indent} levels. */
    private static void appendIndented(StringBuilder out, String text, int indent) {
        int i = 0;
        while (i < text.length()) {
            int nl = text.indexOf('\n', i);
            int end = nl < 0 ? text.length() : nl;
            if (end > i) {
                appendIndent(out, indent);
                out.append(text, i, end);
                out.append('\n');
            }
            if (nl < 0) {
                break;
            }
            i = nl + 1;
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
    // GraphQL
    // ---------------------------------------------------------------------

    private static final int K_NONE = 0;
    private static final int K_NAME = 1;
    private static final int K_VALUE = 2;
    private static final int K_OPEN_BRACE = 3;
    private static final int K_CLOSE_BRACE = 4;
    private static final int K_CLOSE_PAREN = 5;
    private static final int K_COLON = 6;
    private static final int K_COMMA = 7;
    private static final int K_AT = 8;
    private static final int K_SPREAD = 9;
    private static final int K_PUNCT = 10;
    private static final int K_COMMENT = 11;

    /**
     * Formats a GraphQL document: selection sets (the outermost braces are always nested on their
     * own lines) with one field per line, while arguments and list/input-object values stay inline.
     */
    private static String graphql(String s) {
        StringBuilder out = new StringBuilder(s.length() + 64);
        Deque<Character> stack = new ArrayDeque<>();
        int indent = 0;
        int n = s.length();
        int i = 0;
        boolean atLineStart = true;
        boolean pendingSpace = false;
        String lastWord = null;
        int lastKind = K_NONE;
        while (i < n) {
            char c = s.charAt(i);

            if (c == '#') {
                int e = i;
                while (e < n && s.charAt(e) != '\n' && s.charAt(e) != '\r') {
                    e++;
                }
                if (!atLineStart) {
                    line(out, indent);
                }
                out.append(s, i, e);
                line(out, indent);
                atLineStart = true;
                pendingSpace = false;
                lastKind = K_COMMENT;
                lastWord = null;
                i = e;
                continue;
            }
            if (c == '"') {
                int e;
                if (s.startsWith("\"\"\"", i)) {
                    int k = s.indexOf("\"\"\"", i + 3);
                    e = (k < 0) ? n : k + 3;
                } else {
                    e = endOfString(s, i, '"');
                }
                spaceBefore(out, pendingSpace, atLineStart, '\0');
                out.append(s, i, e);
                atLineStart = false;
                pendingSpace = false;
                lastKind = K_VALUE;
                lastWord = null;
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
            if (c == '{') {
                if (!atLineStart) {
                    char p = out.charAt(out.length() - 1);
                    if (p != '(' && p != '[' && p != '{' && !Character.isWhitespace(p)) {
                        out.append(' ');
                    }
                }
                out.append('{');
                stack.push('{');
                indent++;
                line(out, indent);
                atLineStart = true;
                pendingSpace = false;
                lastKind = K_OPEN_BRACE;
                lastWord = null;
                i++;
                continue;
            }
            if (c == '}') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                indent = Math.max(0, indent - 1);
                line(out, indent);
                out.append('}');
                atLineStart = false;
                pendingSpace = false;
                lastKind = K_CLOSE_BRACE;
                lastWord = null;
                if (stack.isEmpty()) {
                    line(out, indent);
                    atLineStart = true;
                }
                i++;
                continue;
            }
            if (c == '(' || c == '[') {
                spaceBefore(out, pendingSpace, atLineStart, '\0');
                out.append(c);
                stack.push(c);
                atLineStart = false;
                pendingSpace = false;
                lastKind = K_PUNCT;
                i++;
                continue;
            }
            if (c == ')' || c == ']') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                out.append(c);
                atLineStart = false;
                pendingSpace = false;
                lastKind = K_CLOSE_PAREN;
                lastWord = null;
                i++;
                continue;
            }
            if (c == ':') {
                out.append(": ");
                atLineStart = false;
                pendingSpace = false;
                lastKind = K_COLON;
                lastWord = null;
                i++;
                continue;
            }
            if (c == ',') {
                if (inSelection(stack)) {
                    line(out, indent);
                    atLineStart = true;
                    pendingSpace = false;
                } else {
                    out.append(", ");
                    atLineStart = false;
                    pendingSpace = false;
                }
                lastKind = K_COMMA;
                i++;
                continue;
            }
            if (c == '@') {
                spaceBefore(out, pendingSpace, atLineStart, '\0');
                out.append('@');
                atLineStart = false;
                pendingSpace = false;
                lastKind = K_AT;
                lastWord = null;
                i++;
                continue;
            }
            if (c == '.' && i + 2 < n && s.charAt(i + 1) == '.' && s.charAt(i + 2) == '.') {
                if (inSelection(stack) && breaksBefore(lastKind)) {
                    line(out, indent);
                } else {
                    spaceBefore(out, pendingSpace, atLineStart, '\0');
                }
                out.append("...");
                atLineStart = false;
                pendingSpace = false;
                lastKind = K_SPREAD;
                lastWord = null;
                i += 3;
                continue;
            }
            if (c == '$') {
                int e = i + 1;
                while (e < n
                        && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '_')) {
                    e++;
                }
                spaceBefore(out, pendingSpace, atLineStart, '\0');
                out.append(s, i, e);
                atLineStart = false;
                pendingSpace = false;
                lastKind = K_VALUE;
                lastWord = null;
                i = e;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int e = i + 1;
                while (e < n
                        && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '_')) {
                    e++;
                }
                String word = s.substring(i, e);
                if (inSelection(stack) && breaksBefore(lastKind) && !"on".equals(lastWord)) {
                    line(out, indent);
                } else {
                    spaceBefore(out, pendingSpace, atLineStart, '\0');
                }
                out.append(word);
                atLineStart = false;
                pendingSpace = false;
                lastKind = K_NAME;
                lastWord = word;
                i = e;
                continue;
            }

            spaceBefore(out, pendingSpace, atLineStart, '\0');
            out.append(c);
            atLineStart = false;
            pendingSpace = false;
            lastKind = K_VALUE;
            lastWord = null;
            i++;
        }
        return trimTrailing(out);
    }

    private static boolean inSelection(Deque<Character> stack) {
        Character top = stack.peek();
        return top != null && top == '{';
    }

    private static boolean breaksBefore(int kind) {
        return kind == K_CLOSE_BRACE
                || kind == K_NAME
                || kind == K_VALUE
                || kind == K_CLOSE_PAREN;
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

    private static String trimTrailing(StringBuilder out) {
        int l = out.length();
        while (l > 0 && Character.isWhitespace(out.charAt(l - 1))) {
            l--;
        }
        out.setLength(l);
        return out.toString();
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
