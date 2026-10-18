package org.zaproxy.zap.extension.prettify;

import java.util.Set;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * Wraps the {@link PrettifyTokenMakerFactory} so every produced {@link TokenMaker} keeps strings
 * correctly highlighted across the artificial breaks inserted by {@link DisplayTextSplitter}.
 *
 * <p>Adapted from the Pretty View add-on's {@code ContinuedStringTokenMakerFactory}.
 */
public class ContinuedStringTokenMakerFactory extends TokenMakerFactory {

    private final TokenMakerFactory delegate;

    public ContinuedStringTokenMakerFactory(TokenMakerFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    protected TokenMaker getTokenMakerImpl(String key) {
        TokenMaker tokenMaker = delegate.getTokenMaker(key);
        return tokenMaker == null ? null : new ContinuedStringTokenMaker(tokenMaker);
    }

    @Override
    public Set<String> keySet() {
        return delegate.keySet();
    }
}
