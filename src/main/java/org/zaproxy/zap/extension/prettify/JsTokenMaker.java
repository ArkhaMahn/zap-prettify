package org.zaproxy.zap.extension.prettify;

import java.util.Set;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * JavaScript token maker that colours object literal keys like JSON keys and object method names
 * like functions, while leaving identifiers, labels and ternary branches alone.
 *
 * <p>RSTA's JavaScript maker paints every unquoted property name as a plain identifier, so
 * {@code {name: 1}} and {@code name = 1} look the same. This wrapper walks the token list the
 * delegate produced, tracks the container stack (object/block/array/parenthesis) plus whether an
 * expression or a statement is expected, and retypes
 *
 * <ul>
 *   <li>{@code key:} in an object literal as {@link TokenTypes#VARIABLE} (the type RSTA's JSON maker
 *       uses for keys), and
 *   <li>{@code key(} in an object literal (method shorthand, including {@code get}/{@code set},
 *       {@code async} and generator {@code *} forms) as {@link TokenTypes#FUNCTION}.
 * </ul>
 *
 * <p>The tracked context is carried in the line state, so object keys stay coloured across
 * formatted multi-line objects; the delegate's own lexer state (an open comment, for example) is
 * packed into the same value.
 */
final class JsTokenMaker implements TokenMaker {

    private static final int OBJECT = 0;
    private static final int BLOCK = 1;
    private static final int ARRAY = 2;
    private static final int PAREN = 3;

    private static final int MAX_DEPTH = 4;

    /**
     * Tag for a carried state that holds this maker's context. It is negative (so RSTA never treats
     * the text-less token that carries it as paintable) and small (the HTTP token maker encodes a
     * body state by subtracting it from a negative base, and the result must stay far enough below
     * zero to be recognised). Bit 19 is clear, which distinguishes the tag from the delegate's own
     * all-ones-prefixed negative states and from {@link ContinuedStringTokenMaker}'s positive
     * marker.
     */
    private static final int CONTEXT_BASE = 0xFFF00000;

    private static final int CONTEXT_MASK = 0xFFF80000;

    private static final int CODE_MASK = 0x0007FFFF;

    /**
     * Bits used to carry the delegate's own lexer state. RSTA's JavaScript states that can span
     * lines are -24..-8; wider (E4X) states are carried without the context instead.
     */
    private static final int DELEGATE_BITS = 5;

    private static final int DELEGATE_BIAS = 24;

    private static final int DELEGATE_MASK = (1 << DELEGATE_BITS) - 1;

    private static final int VALUE_BIT = 1 << DELEGATE_BITS;
    private static final int KEY_BIT = 1 << (DELEGATE_BITS + 1);
    private static final int DEPTH_SHIFT = DELEGATE_BITS + 2;
    private static final int FRAME_SHIFT = DEPTH_SHIFT + 3;

    /** Reserved words after which an object literal (rather than a block) may start. */
    private static final Set<String> VALUE_WORDS =
            Set.of(
                    "return", "yield", "throw", "case", "typeof", "void", "delete", "new", "in",
                    "instanceof", "of", "await", "var", "let", "const");

    /** Property modifiers that keep the next name in key position. */
    private static final Set<String> KEY_MODIFIERS = Set.of("get", "set", "async");

    private final TokenMaker delegate;

    /** State the last {@link #getTokenList} call leaves for the next line. */
    private int lastState;

    JsTokenMaker(TokenMaker delegate) {
        this.delegate = delegate;
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        Ctx ctx = new Ctx();
        int delegateState = decode(initialTokenType, ctx);
        Token list = delegate.getTokenList(text, delegateState, startOffset);
        if (list == null) {
            lastState = encode(ctx, delegateState);
            return list;
        }
        Token nullToken = null;
        for (Token t = list; t != null; t = t.getNextToken()) {
            // The makers this wraps carry their end-of-line state in the last token. RSTA recycles
            // token objects, so that token's length and text cannot be trusted to identify it.
            if (t.getNextToken() == null) {
                nullToken = t;
                break;
            }
            int type = t.getType();
            if (t.length() <= 0 || isInvisible(type)) {
                continue;
            }
            process(t, ctx);
        }
        int out = delegateState;
        if (nullToken != null) {
            // RSTA stores the delegate's carried lexer state in the trailing text-less token's type.
            out = nullToken.getType();
            nullToken.setType(encode(ctx, out));
            lastState = nullToken.getType();
        } else {
            lastState = encode(ctx, out);
        }
        return list;
    }

    /** The state produced by the last {@link #getTokenList} call. */
    int lastState() {
        return lastState;
    }

    @Override
    public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
        getTokenList(text, initialTokenType, 0);
        return lastState;
    }

    private void process(Token t, Ctx ctx) {
        if (isChar(t, '{')) {
            boolean object = ctx.expectValue;
            push(ctx, object ? OBJECT : BLOCK);
            ctx.key = object;
            ctx.expectValue = true;
            return;
        }
        if (isChar(t, '}')) {
            pop(ctx);
            ctx.key = false;
            ctx.expectValue = false;
            return;
        }
        if (isChar(t, '[')) {
            push(ctx, ARRAY);
            ctx.key = false;
            ctx.expectValue = true;
            return;
        }
        if (isChar(t, ']')) {
            pop(ctx);
            ctx.key = false;
            ctx.expectValue = false;
            return;
        }
        if (isChar(t, '(')) {
            push(ctx, PAREN);
            ctx.expectValue = true;
            return;
        }
        if (isChar(t, ')')) {
            pop(ctx);
            ctx.key = false;
            ctx.expectValue = false;
            return;
        }
        if (isChar(t, ',')) {
            if (ctx.inObject()) {
                ctx.key = true;
            }
            ctx.expectValue = true;
            return;
        }
        if (isChar(t, ':')) {
            ctx.key = false;
            ctx.expectValue = true;
            return;
        }
        if (isChar(t, ';')) {
            ctx.key = false;
            ctx.expectValue = false;
            return;
        }
        if (isChar(t, '*') && ctx.inObject() && ctx.key) {
            // Generator method marker: the name that follows is still the key.
            ctx.expectValue = false;
            return;
        }
        if (isWord(t)) {
            String word = lexeme(t);
            if (ctx.inObject() && ctx.key) {
                if (KEY_MODIFIERS.contains(word)) {
                    ctx.expectValue = false;
                    return;
                }
                Token next = nextSignificant(t);
                if (next != null && isChar(next, ':')) {
                    if (t.getType() == TokenTypes.IDENTIFIER
                            || t.getType() == TokenTypes.RESERVED_WORD) {
                        t.setType(TokenTypes.VARIABLE);
                    }
                } else if (next != null && isChar(next, '(')) {
                    if (t.getType() == TokenTypes.IDENTIFIER
                            || t.getType() == TokenTypes.RESERVED_WORD) {
                        t.setType(TokenTypes.FUNCTION);
                    }
                }
                ctx.key = false;
                ctx.expectValue = false;
                return;
            }
            ctx.key = false;
            ctx.expectValue = VALUE_WORDS.contains(word);
            return;
        }
        // Operators and anything else: an operand is expected next, except postfix ++/--.
        ctx.key = false;
        if (!isChar(t, '+') && !isChar(t, '-')) {
            ctx.expectValue = true;
        }
    }

    private static boolean isChar(Token t, char c) {
        return t.length() == 1 && charAt(t, 0) == c;
    }

    private static char charAt(Token t, int i) {
        char[] array = t.getTextArray();
        if (array != null) {
            return array[t.getTextOffset() + i];
        }
        return t.getLexeme().charAt(i);
    }

    private static String lexeme(Token t) {
        return t.getLexeme();
    }

    private static boolean isWord(Token t) {
        switch (t.getType()) {
            case TokenTypes.IDENTIFIER:
            case TokenTypes.VARIABLE:
            case TokenTypes.FUNCTION:
            case TokenTypes.DATA_TYPE:
            case TokenTypes.RESERVED_WORD:
            case TokenTypes.LITERAL_BOOLEAN:
            case TokenTypes.LITERAL_NUMBER_DECIMAL_INT:
            case TokenTypes.LITERAL_NUMBER_FLOAT:
            case TokenTypes.LITERAL_NUMBER_HEXADECIMAL:
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE:
            case TokenTypes.LITERAL_CHAR:
            case TokenTypes.LITERAL_BACKQUOTE:
            case TokenTypes.REGEX:
                return true;
            default:
                return false;
        }
    }

    private static boolean isInvisible(int type) {
        switch (type) {
            case TokenTypes.WHITESPACE:
            case TokenTypes.COMMENT_EOL:
            case TokenTypes.COMMENT_MULTILINE:
            case TokenTypes.COMMENT_DOCUMENTATION:
            case TokenTypes.COMMENT_KEYWORD:
            case TokenTypes.COMMENT_MARKUP:
                return true;
            default:
                return false;
        }
    }

    private static Token nextSignificant(Token t) {
        for (Token next = t.getNextToken(); next != null; next = next.getNextToken()) {
            if (next.getNextToken() == null) {
                // End-of-line state carrier, not a real token.
                return null;
            }
            int type = next.getType();
            if (type == TokenTypes.NULL || next.length() <= 0 || isInvisible(type)) {
                continue;
            }
            return next;
        }
        return null;
    }

    private static void push(Ctx ctx, int frame) {
        if (ctx.depth < MAX_DEPTH) {
            ctx.frames[ctx.depth++] = frame;
        }
    }

    private static void pop(Ctx ctx) {
        if (ctx.depth > 0) {
            ctx.depth--;
        }
    }

    /** Decodes the context and returns the delegate's lexer state. */
    private static int decode(int state, Ctx ctx) {
        if ((state & CONTEXT_MASK) != CONTEXT_BASE) {
            // Either fresh (0) or one of the delegate's own carried states (for example an open
            // multi-line comment or template literal). No context was recorded.
            return state;
        }
        int code = state & CODE_MASK;
        ctx.expectValue = (code & VALUE_BIT) != 0;
        ctx.key = (code & KEY_BIT) != 0;
        ctx.depth = (code >>> DEPTH_SHIFT) & 0x7;
        if (ctx.depth > MAX_DEPTH) {
            ctx.depth = MAX_DEPTH;
        }
        for (int i = 0; i < ctx.depth; i++) {
            ctx.frames[i] = (code >>> (FRAME_SHIFT + 2 * i)) & 0x3;
        }
        return (code & DELEGATE_MASK) - DELEGATE_BIAS;
    }

    private static int encode(Ctx ctx, int delegateState) {
        if (delegateState < -DELEGATE_BIAS || delegateState > DELEGATE_MASK - DELEGATE_BIAS) {
            // Exotic delegate state (E4X): carry it alone and drop the context rather than mangle it.
            return delegateState;
        }
        int code = (delegateState + DELEGATE_BIAS) & DELEGATE_MASK;
        if (ctx.expectValue) {
            code |= VALUE_BIT;
        }
        if (ctx.key) {
            code |= KEY_BIT;
        }
        code |= (ctx.depth & 0x7) << DEPTH_SHIFT;
        for (int i = 0; i < ctx.depth; i++) {
            code |= (ctx.frames[i] & 0x3) << (FRAME_SHIFT + 2 * i);
        }
        return CONTEXT_BASE | code;
    }

    private static final class Ctx {
        final int[] frames = new int[MAX_DEPTH];
        int depth;
        boolean key;
        boolean expectValue;

        boolean inObject() {
            return depth > 0 && frames[depth - 1] == OBJECT;
        }
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
    public javax.swing.Action getInsertBreakAction() {
        return delegate.getInsertBreakAction();
    }

    @Override
    public boolean getMarkOccurrencesOfTokenType(int type) {
        return delegate.getMarkOccurrencesOfTokenType(type);
    }

    @Override
    public org.fife.ui.rsyntaxtextarea.OccurrenceMarker getOccurrenceMarker() {
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
