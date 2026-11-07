package org.zaproxy.zap.extension.prettify;

/**
 * Makes header lines ZAP core would reject parsable, without losing what the user typed.
 *
 * <p>ZAP core's header parse aborts on any header line without a {@code ':'} (e.g. a bare token
 * like {@code xxx} with no header value): a whole save that contains such a line is rejected.
 * To keep malformed lines instead of dropping them, a bare line is given the empty value core
 * would itself write when it re-serializes the header, so it comes out as {@code name: }.
 * Header order, duplicates, well-formed lines, the request line, whitespace-only and continuation
 * lines and everything past the blank-line separator (the body) are left untouched.
 */
final class HeaderLineFix {

    private HeaderLineFix() {}

    /**
     * @return the text with every colon-less line of the header block turned into a header with
     *     an empty value, or the text itself when it has none.
     */
    public static String fixMalformedHeaderLines(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = null;
        int fixedEnd = -1;
        int lineStart = 0;
        boolean firstLine = true;
        while (true) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl;
            String line = text.substring(lineStart, lineEnd);
            if (!firstLine && (line.isEmpty() || line.equals("\r"))) {
                // The blank-line separator: the body starts here and is never touched.
                break;
            }
            if (!firstLine && needsAColon(line)) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 16);
                    out.append(text, 0, lineStart);
                } else {
                    out.append(text, fixedEnd, lineStart);
                }
                if (line.endsWith("\r")) {
                    out.append(line, 0, line.length() - 1).append(':').append('\r');
                } else {
                    out.append(line).append(':');
                }
                fixedEnd = lineEnd;
            }
            firstLine = false;
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        if (out == null) {
            return text;
        }
        out.append(text, fixedEnd, text.length());
        return out.toString();
    }

    /**
     * Whether a header line is the colon-less kind core rejects: not whitespace-only, not a
     * continuation of the preceding header value, and without a ':'.
     */
    private static boolean needsAColon(String line) {
        int end = line.endsWith("\r") ? line.length() - 1 : line.length();
        if (end <= 0) {
            return false;
        }
        char first = line.charAt(0);
        if (first == ' ' || first == '\t') {
            return false;
        }
        if (line.substring(0, end).isBlank()) {
            return false;
        }
        return line.substring(0, end).indexOf(':') < 0;
    }
}
