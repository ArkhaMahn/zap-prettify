package org.zaproxy.zap.extension.prettify;

/**
 * Helpers for tokenizing a very long logical line in bounded pieces.
 *
 * <p>RSTA's token list grows in tiny increments and copies itself on every growth, so handing a
 * whole minified line to a token maker costs time quadratic in the line length. Cutting the line
 * into chunks keeps the cost linear, but a cut can change how the next piece is read when a
 * construct only ends after the cut. Strings and comments carry a state; a regular-expression
 * literal does not, so a chunk that opens one is extended to include its closing slash.
 */
final class TokenizeChunks {

    /** Longest piece handed to a token maker in one call. */
    static final int SIZE = 8192;

    /** How far a chunk may grow to reach the slash that closes a regular expression. */
    private static final int MAX_SLASH_EXTENSION = SIZE;

    private TokenizeChunks() {}

    /**
     * Moves {@code chunkEnd} forward to include the slash that closes a regular expression whose
     * opener is in the chunk. RSTA only recognises a regex literal when it sees the closing slash;
     * a chunk cut between the two would make it read the body as ordinary code.
     *
     * <p>The extension is safe even when the last slash in the chunk is a division operator or is
     * inside a string or comment: it only makes the piece longer, and the token maker still reads
     * the longer piece correctly. The extension is bounded so a chunk never grows quadratically; a
     * regular expression longer than the bound can still be cut, in which case its body is coloured
     * as ordinary code rather than corrupting the text.
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
        int limit = Math.min(lineEnd, chunkEnd + MAX_SLASH_EXTENSION);
        boolean inClass = false;
        for (int i = lastSlash + 1; i < limit; i++) {
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
                return i + 1;
            }
        }
        return chunkEnd;
    }
}
