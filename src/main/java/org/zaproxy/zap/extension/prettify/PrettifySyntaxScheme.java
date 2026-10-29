package org.zaproxy.zap.extension.prettify;

import java.awt.Color;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * A {@link SyntaxScheme} that colours the standard RSTA token types plus the Prettify HTTP token
 * types ({@link PrettifyTokenTypes}) using the Prettify editor palette.
 */
public class PrettifySyntaxScheme extends SyntaxScheme {

    private static final long serialVersionUID = 1L;

    private final Style[] styles;

    public PrettifySyntaxScheme(PrettifyTheme theme) {
        super(false);
        this.styles = new Style[PrettifyTokenTypes.COUNT];

        Color text = theme.getText();
        Color comment = theme.getComment();
        Color string = theme.getLiteralString();
        Color number = theme.getLiteralNumber();
        Color function = theme.getFunction();
        Color reserved = theme.getReservedWord();
        Color datatype = theme.getDatatype();
        Color tagName = theme.getTagName();
        Color cdata = theme.getCdata();
        Color annotation = theme.getLiteralString();
        Color bool = theme.getReservedWord();
        Color operator = theme.getOperator();

        set(TokenTypes.NULL, text);
        set(TokenTypes.COMMENT_EOL, comment);
        set(TokenTypes.COMMENT_MULTILINE, comment);
        set(TokenTypes.COMMENT_DOCUMENTATION, comment);
        set(TokenTypes.COMMENT_KEYWORD, comment);
        set(TokenTypes.COMMENT_MARKUP, comment);
        set(TokenTypes.RESERVED_WORD, reserved);
        set(7, text);
        set(TokenTypes.FUNCTION, function);
        set(TokenTypes.LITERAL_BOOLEAN, bool);
        set(TokenTypes.LITERAL_NUMBER_DECIMAL_INT, number);
        set(TokenTypes.LITERAL_NUMBER_FLOAT, number);
        set(TokenTypes.LITERAL_NUMBER_HEXADECIMAL, number);
        set(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, string);
        set(TokenTypes.LITERAL_CHAR, string);
        set(TokenTypes.LITERAL_BACKQUOTE, string);
        set(TokenTypes.DATA_TYPE, datatype);
        set(TokenTypes.VARIABLE, function);
        set(TokenTypes.REGEX, operator);
        set(TokenTypes.ANNOTATION, annotation);
        set(TokenTypes.IDENTIFIER, text);
        set(TokenTypes.WHITESPACE, text);
        set(TokenTypes.SEPARATOR, theme.getSeparator());
        set(TokenTypes.OPERATOR, operator);
        set(TokenTypes.PREPROCESSOR, comment);
        set(TokenTypes.MARKUP_TAG_DELIMITER, tagName);
        set(TokenTypes.MARKUP_TAG_NAME, tagName);
        set(TokenTypes.MARKUP_TAG_ATTRIBUTE, function);
        set(TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE, string);
        set(TokenTypes.MARKUP_COMMENT, comment);
        set(TokenTypes.MARKUP_DTD, cdata);
        set(TokenTypes.MARKUP_PROCESSING_INSTRUCTION, cdata);
        set(TokenTypes.MARKUP_CDATA_DELIMITER, cdata);
        set(TokenTypes.MARKUP_CDATA, cdata);
        set(TokenTypes.MARKUP_ENTITY_REFERENCE, annotation);
        set(TokenTypes.ERROR_IDENTIFIER, reserved);
        set(TokenTypes.ERROR_NUMBER_FORMAT, reserved);
        set(TokenTypes.ERROR_STRING_DOUBLE, reserved);
        set(TokenTypes.ERROR_CHAR, reserved);

        set(PrettifyTokenTypes.HTTP_FIRST_LINE, theme.getHttpFirstLine());
        set(PrettifyTokenTypes.HTTP_HEADER_NAME, theme.getHeaderName());
        set(PrettifyTokenTypes.HTTP_HEADER_VALUE, theme.getHeaderValue());
        set(PrettifyTokenTypes.HTTP_PARAMETER_NAME, theme.getParamName());
        set(PrettifyTokenTypes.HTTP_PARAMETER_VALUE, theme.getParamValue());
    }

    private void set(int index, Color foreground) {
        this.styles[index] = new Style(foreground);
    }

    @Override
    public Style getStyle(int index) {
        if (index < 0 || index >= this.styles.length) {
            return this.styles[TokenTypes.NULL];
        }
        return this.styles[index];
    }

    @Override
    public int getStyleCount() {
        return this.styles.length;
    }

    @Override
    public Style[] getStyles() {
        return this.styles;
    }
}
