package org.zaproxy.zap.extension.prettify;

import javax.swing.Action;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.OccurrenceMarker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenImpl;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * A {@link TokenMaker} wrapper that keeps string literals correctly highlighted across the hard
 * breaks inserted by {@link DisplayTextSplitter}.
 *
 * <p>When a splitter break lands inside a string literal, RSTA sees the remainder of the string as
 * a new, unterminated string and paints it with the error colour. This wrapper encodes "we are
 * inside a string" into the token state that RSTA carries between lines, so the continuation is
 * painted as a normal string.
 *
 * <p>Adapted from the Pretty View add-on's {@code ContinuedStringTokenMaker}.
 */
public class ContinuedStringTokenMaker implements TokenMaker {

    private static final int MARKER = 0x40000000;
    private static final int MARKER_MASK = 0xC0000000;
    private static final int SINGLE_QUOTE = 0x10000;
    private static final int LANGUAGE_SHIFT = 20;
    private static final int BASE_MASK = 0xFFFF;

    private final TokenMaker delegate;

    public ContinuedStringTokenMaker(TokenMaker delegate) {
        this.delegate = delegate;
    }

    @Override
    public Token getTokenList(Segment segment, int initialTokenType, int startOffset) {
        if (isMarker(initialTokenType)) {
            return continueString(segment, initialTokenType, startOffset);
        }
        return recolourOpenString(delegate.getTokenList(segment, initialTokenType, startOffset));
    }

    @Override
    public int getLastTokenTypeOnLine(Segment segment, int initialTokenType) {
        if (isMarker(initialTokenType)) {
            int quoteIndex = indexOfQuote(segment, markerQuote(initialTokenType));
            if (quoteIndex < 0) {
                return initialTokenType;
            }
            Segment rest =
                    new Segment(
                            segment.array,
                            segment.offset + quoteIndex + 1,
                            segment.count - quoteIndex - 1);
            return lastType(rest, baseOf(initialTokenType));
        }
        return lastType(segment, initialTokenType);
    }

    private Token continueString(Segment segment, int marker, int startOffset) {
        char quote = markerQuote(marker);
        int quoteIndex = indexOfQuote(segment, quote);
        int end = quoteIndex < 0 ? segment.count : quoteIndex + 1;
        int type = quote == '\'' ? TokenTypes.LITERAL_CHAR : TokenTypes.LITERAL_STRING_DOUBLE_QUOTE;
        TokenImpl token =
                new TokenImpl(
                        segment.array,
                        segment.offset,
                        segment.offset + end - 1,
                        startOffset,
                        type,
                        languageOf(marker));
        Segment rest = new Segment(segment.array, segment.offset + end, segment.count - end);
        token.setNextToken(delegate.getTokenList(rest, baseOf(marker), startOffset + end));
        return token;
    }

    private int lastType(Segment segment, int initialTokenType) {
        int lastType = delegate.getLastTokenTypeOnLine(segment, initialTokenType);
        Token open = openString(delegate.getTokenList(segment, initialTokenType, 0));
        if (open == null) {
            return lastType;
        }
        return marker(quoteOfType(open.getType()), open.getLanguageIndex(), lastType);
    }

    private static Token openString(Token token) {
        Token open = null;
        for (Token t = token; t != null; t = t.getNextToken()) {
            if (!t.isPaintable()) {
                continue;
            }
            int type = t.getType();
            open = type == TokenTypes.ERROR_STRING_DOUBLE || type == TokenTypes.ERROR_CHAR ? t : null;
        }
        return open;
    }

    private static Token recolourOpenString(Token token) {
        for (Token t = token; t != null; t = t.getNextToken()) {
            int type = t.getType();
            if (type == TokenTypes.ERROR_STRING_DOUBLE) {
                t.setType(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE);
            } else if (type == TokenTypes.ERROR_CHAR) {
                t.setType(TokenTypes.LITERAL_CHAR);
            }
        }
        return token;
    }

    private static int indexOfQuote(Segment segment, char quote) {
        int i = 0;
        while (i < segment.count) {
            char c = segment.array[segment.offset + i];
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static char quoteOfType(int type) {
        return type == TokenTypes.ERROR_CHAR ? '\'' : '"';
    }

    private static char markerQuote(int marker) {
        return (marker & SINGLE_QUOTE) != 0 ? '\'' : '"';
    }

    private static boolean isMarker(int initialTokenType) {
        return (initialTokenType & MARKER_MASK) == MARKER;
    }

    private static int languageOf(int marker) {
        return (marker >>> LANGUAGE_SHIFT) & 0xF;
    }

    private static int baseOf(int marker) {
        return (short) (marker & BASE_MASK);
    }

    private static int marker(char quote, int languageIndex, int base) {
        return MARKER
                | (quote == '\'' ? SINGLE_QUOTE : 0)
                | ((languageIndex & 0xF) << LANGUAGE_SHIFT)
                | (base & BASE_MASK);
    }

    @Override
    public void addNullToken() {
        delegate.addNullToken();
    }

    @Override
    public void addToken(char[] array, int start, int end, int tokenType, int startOffset) {
        delegate.addToken(array, start, end, tokenType, startOffset);
    }

    @Override
    public int getClosestStandardTokenTypeForInternalType(int type) {
        return delegate.getClosestStandardTokenTypeForInternalType(type);
    }

    @Override
    public boolean getCurlyBracesDenoteCodeBlocks(int languageIndex) {
        return delegate.getCurlyBracesDenoteCodeBlocks(languageIndex);
    }

    @Override
    public String[] getLineCommentStartAndEnd(int languageIndex) {
        return delegate.getLineCommentStartAndEnd(languageIndex);
    }

    @Override
    public Action getInsertBreakAction() {
        return delegate.getInsertBreakAction();
    }

    @Override
    public boolean getMarkOccurrencesOfTokenType(int type) {
        return delegate.getMarkOccurrencesOfTokenType(type);
    }

    @Override
    public OccurrenceMarker getOccurrenceMarker() {
        return delegate.getOccurrenceMarker();
    }

    @Override
    public boolean getShouldIndentNextLineAfter(Token token) {
        return delegate.getShouldIndentNextLineAfter(token);
    }

    @Override
    public boolean isIdentifierChar(int languageIndex, char c) {
        return delegate.isIdentifierChar(languageIndex, c);
    }

    @Override
    public boolean isMarkupLanguage() {
        return delegate.isMarkupLanguage();
    }
}
