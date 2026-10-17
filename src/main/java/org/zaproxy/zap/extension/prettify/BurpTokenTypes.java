package org.zaproxy.zap.extension.prettify;

/**
 * Extra token types used by the Prettify HTTP token makers, in addition to the standard
 * {@link org.fife.ui.rsyntaxtextarea.TokenTypes}.
 *
 * <p>The values mirror the ordering used by Burp's Pretty view so that the colour scheme can be
 * indexed consistently.
 */
public final class BurpTokenTypes {

    /** First line of an HTTP request or response. */
    public static final int HTTP_FIRST_LINE = 39;

    /** Name of an HTTP header. */
    public static final int HTTP_HEADER_NAME = 40;

    /** Value of an HTTP header. */
    public static final int HTTP_HEADER_VALUE = 41;

    /** Name of an HTTP parameter (also used for cookie names). */
    public static final int HTTP_PARAMETER_NAME = 42;

    /** Value of an HTTP parameter (also used for cookie values). */
    public static final int HTTP_PARAMETER_VALUE = 43;

    /** Number of token types handled by {@link BurpSyntaxScheme}. */
    public static final int COUNT = 44;

    private BurpTokenTypes() {}
}
