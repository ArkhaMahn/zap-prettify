package org.zaproxy.zap.extension.prettify;

import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * Token maker factory for the Prettify styles.
 *
 * <p>Instances are created directly (rather than reflectively, as {@link AbstractTokenMakerFactory}
 * does by default) so that add-on classes are resolved by the add-on class loader. Unknown style
 * keys (e.g. {@code text/json}) are resolved by RSTA's default factory so that HTTP bodies can be
 * highlighted with the built-in token makers.
 */
public class PrettifyTokenMakerFactory extends AbstractTokenMakerFactory {

    public static final String STYLE_HTTP_REQUEST = "prettify/http-request";
    public static final String STYLE_HTTP_RESPONSE = "prettify/http-response";
    public static final String STYLE_HTTP_REQUEST_HEAD = "prettify/http-request-head";
    public static final String STYLE_HTTP_RESPONSE_HEAD = "prettify/http-response-head";
    public static final String STYLE_FORM = "prettify/form";

    public PrettifyTokenMakerFactory() {}

    /**
     * Whether {@code key} selects one of the custom HTTP token makers (rather than an RSTA body
     * token maker). HTTP documents need {@link PrettifyDocument}'s continuation-aware state.
     */
    public static boolean isHttpStyle(String key) {
        return STYLE_HTTP_REQUEST.equals(key)
                || STYLE_HTTP_RESPONSE.equals(key)
                || STYLE_HTTP_REQUEST_HEAD.equals(key)
                || STYLE_HTTP_RESPONSE_HEAD.equals(key);
    }

    @Override
    protected void initTokenMakerMap() {
        // Mappings are resolved directly in getTokenMakerImpl.
    }

    @Override
    protected TokenMaker getTokenMakerImpl(String key) {
        switch (key) {
            case STYLE_HTTP_REQUEST:
                return new HttpRequestTokenMaker();
            case STYLE_HTTP_RESPONSE:
                return new HttpResponseTokenMaker();
            case STYLE_HTTP_REQUEST_HEAD:
                return new HttpRequestHeadTokenMaker();
            case STYLE_HTTP_RESPONSE_HEAD:
                return new HttpResponseHeadTokenMaker();
            case STYLE_FORM:
                return new FormUrlEncodedTokenMaker();
            case ContentType.GRAPHQL_STYLE:
                return TokenMakerFactory.getDefaultInstance().getTokenMaker(ContentType.PLAIN_STYLE);
            default:
                return TokenMakerFactory.getDefaultInstance().getTokenMaker(key);
        }
    }
}
