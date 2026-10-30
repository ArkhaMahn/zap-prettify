package org.zaproxy.zap.extension.prettify;

/**
 * Helpers for tokenizing a very long logical line in bounded pieces.
 *
 * <p>RSTA's token list grows in tiny increments and copies itself on every growth, so handing a
 * whole minified line to a token maker costs time quadratic in the line length. Cutting the line
 * into chunks keeps the cost linear, but a cut can change how the next piece is read when a
 * construct only ends after the cut. Strings and comments carry a state; a regular-expression
 * literal does not, so a chunk that opens one is extended to include its closing slash.
 *
 * <p>Whether a slash starts a regular expression or is a division operator depends on the token
 * before it, and a token maker only sees the piece it is given. The chunk end is therefore adjusted
 * so a slash stays in the piece that still holds its left context, and so a two-character comment
 * delimiter is never split.
 *
 * <p>A regex can be much longer than a chunk. Extending the chunk is still safe in that case: once
 * the closing slash is included, the whole literal is a single token, so the token list stays small
 * even though the chunk is long. A literal that never closes is marked directly instead, because
 * feeding the rest of the line to the token maker would read it as code and grow the list
 * quadratically.
 */
final class TokenizeChunks {

    /** Longest piece handed to a token maker in one call. */
    static final int SIZE = 8192;

    /**
     * How far past a cut to look for whitespace and a slash that belongs with the previous piece.
     * Scanned whitespace is always moved into that piece, so the total scan stays linear.
     */
    private static final int SLASH_LOOKAHEAD = SIZE;

    private TokenizeChunks() {}

    /**
     * Adjusts a proposed chunk end so a cut does not change how a slash is read.
     *
     * <p>A slash that starts a regular expression, a division or a comment is decided from the
     * characters around it. The token maker only sees the piece it is given, so the cut is moved to
     * keep a slash with the token before it, and a two-character comment delimiter is never split
     * across two pieces. Comment openers are left for the next piece when possible: a comment is
     * recognised without any left context, so it can start a piece safely.
     *
     * <p>Only a bounded run of whitespace is skipped, and skipped whitespace is always moved into
     * this piece. That keeps the scan linear even on a line made mostly of whitespace: no piece
     * starts inside whitespace that an earlier cut already scanned.
     *
     * @param text the whole logical line
     * @param lineEnd end offset of the line in {@code text} (exclusive)
     * @param chunkEnd the proposed chunk end
     * @return the chunk end to use, never beyond {@code lineEnd}
     */
    static int adjustForSlash(String text, int lineEnd, int chunkEnd) {
        if (chunkEnd <= 0 || chunkEnd >= lineEnd) {
            return chunkEnd;
        }
        char previous = text.charAt(chunkEnd - 1);
        char next = text.charAt(chunkEnd);
        if (previous == '/') {
            if (next == '/' || next == '*') {
                // The cut splits a comment opener (or a division followed by one): finish it here.
                int end = chunkEnd + 1;
                if (next == '/' && end < lineEnd && text.charAt(end) == '/') {
                    end++;
                }
                return end;
            }
        } else if (previous == '*' && next == '/') {
            // Keep a block-comment closer in one piece.
            return chunkEnd + 1;
        }
        // Move a following slash (and any whitespace before it) into this piece so the token maker
        // sees what precedes the slash. A comment opener is safe to leave for the next piece.
        int slash = chunkEnd;
        int limit = Math.min(lineEnd, chunkEnd + SLASH_LOOKAHEAD);
        while (slash < limit && Character.isWhitespace(text.charAt(slash))) {
            slash++;
        }
        if (slash >= lineEnd) {
            return lineEnd;
        }
        if (text.charAt(slash) != '/') {
            // Consume the whitespace seen so far so the next piece does not scan it again.
            return slash;
        }
        if (slash + 1 < lineEnd
                && (text.charAt(slash + 1) == '/' || text.charAt(slash + 1) == '*')) {
            return slash;
        }
        return slash + 1;
    }

    /**
     * Moves {@code chunkEnd} forward to include the slash that closes a regular expression whose
     * opener is in the chunk. RSTA only recognises a regex literal when it sees the closing slash;
     * a chunk cut between the two would make it read the body as ordinary code.
     *
     * <p>This is a bounded first pass that helps every style. A literal longer than the bound is
     * handled by the caller for styles whose token maker has a regex rule (JavaScript and
     * TypeScript); the bound keeps this scan from growing a chunk without limit.
     *
     * @param text the whole logical line
     * @param chunkStart start offset of the chunk in {@code text}
     * @param lineEnd end offset of the line in {@code text} (exclusive)
     * @param chunkEnd the proposed chunk end
     * @return the chunk end to use, never beyond {@code lineEnd}
     */
    static int extendForRegex(String text, int chunkStart, int lineEnd, int chunkEnd) {
        if (chunkEnd >= lineEnd) {
            return chunkEnd;
        }
        int lastSlash = -1;
        for (int i = chunkEnd - 1; i >= chunkStart; i--) {
            if (text.charAt(i) == '/') {
                lastSlash = i;
                break;
            }
        }
        if (lastSlash < 0) {
            return chunkEnd;
        }
        int closer = findClosingSlash(text, lastSlash + 1, Math.min(lineEnd, chunkEnd + SIZE));
        if (closer < 0) {
            return chunkEnd;
        }
        if (closer + 1 < lineEnd
                && (text.charAt(closer + 1) == '/' || text.charAt(closer + 1) == '*')) {
            // The candidate starts a comment rather than closing a regex; extending to it would
            // leave the comment opener split across two pieces. Script styles recover a genuine
            // regex that ends here through the caller's closing-slash retry.
            return chunkEnd;
        }
        return closer + 1;
    }

    /**
     * Finds the slash that closes a regular-expression literal whose body starts at {@code from}.
     * A backslash escapes the next character, and a slash inside a {@code [...]} character class
     * does not close the literal.
     *
     * @param text the whole logical line
     * @param from first character of the regex body
     * @param lineEnd end offset of the line in {@code text} (exclusive)
     * @return the index of the closing slash, or {@code -1} if the line ends first
     */
    static int findClosingSlash(String text, int from, int lineEnd) {
        boolean inClass = false;
        for (int i = from; i < lineEnd; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                return i;
            }
        }
        return -1;
    }
}
