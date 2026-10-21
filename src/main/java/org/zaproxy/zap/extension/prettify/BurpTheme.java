package org.zaproxy.zap.extension.prettify;

import java.awt.Color;

/**
 * Colours used by the Prettify syntax scheme, extracted from Burp Suite's light and dark editor
 * themes ({@code ui.editor.message.*}).
 */
public final class BurpTheme {

    public static final BurpTheme LIGHT = new BurpTheme(
            /* background            */ "#FFFFFF",
            /* text                  */ "#141414",
            /* httpFirstLine         */ "#141414",
            /* headerName            */ "#000075",
            /* headerValue           */ "#202020",
            /* paramName             */ "#0000C0",
            /* paramValue            */ "#A01010",
            /* operator              */ "#202020",
            /* separator             */ "#202020",
            /* comment               */ "#005A00",
            /* reservedWord          */ "#000075",
            /* literalString         */ "#008000",
            /* literalNumber         */ "#0000FF",
            /* function              */ "#000075",
            /* datatype              */ "#000075",
            /* tagName               */ "#B000C0",
            /* cdata                 */ "#CC6600",
            /* lineNumbers           */ "#A0A0A0",
            /* selectionBackground   */ "#CADAF0",
            /* selectionForeground   */ "#FFFFFF");

    public static final BurpTheme DARK = new BurpTheme(
            /* background            */ "#2B2B2B",
            /* text                  */ "#CECECE",
            /* httpFirstLine         */ "#D1E8F9",
            /* headerName            */ "#D1E8F9",
            /* headerValue           */ "#BABABA",
            /* paramName             */ "#BBCDFF",
            /* paramValue            */ "#A5C35B",
            /* operator              */ "#D1E8F9",
            /* separator             */ "#D1E8F9",
            /* comment               */ "#A3BABA",
            /* reservedWord          */ "#FF9E57",
            /* literalString         */ "#93C763",
            /* literalNumber         */ "#79C1F4",
            /* function              */ "#E9C063",
            /* datatype              */ "#FF9E57",
            /* tagName               */ "#E9C063",
            /* cdata                 */ "#D5E6F0",
            /* lineNumbers           */ "#707070",
            /* selectionBackground   */ "#2C5175",
            /* selectionForeground   */ "#FFFFFF");

    private final Color background;
    private final Color text;
    private final Color httpFirstLine;
    private final Color headerName;
    private final Color headerValue;
    private final Color paramName;
    private final Color paramValue;
    private final Color operator;
    private final Color separator;
    private final Color comment;
    private final Color reservedWord;
    private final Color literalString;
    private final Color literalNumber;
    private final Color function;
    private final Color datatype;
    private final Color tagName;
    private final Color cdata;
    private final Color lineNumbers;
    private final Color selectionBackground;
    private final Color selectionForeground;

    private BurpTheme(
            String background,
            String text,
            String httpFirstLine,
            String headerName,
            String headerValue,
            String paramName,
            String paramValue,
            String operator,
            String separator,
            String comment,
            String reservedWord,
            String literalString,
            String literalNumber,
            String function,
            String datatype,
            String tagName,
            String cdata,
            String lineNumbers,
            String selectionBackground,
            String selectionForeground) {
        this.background = web(background);
        this.text = web(text);
        this.httpFirstLine = web(httpFirstLine);
        this.headerName = web(headerName);
        this.headerValue = web(headerValue);
        this.paramName = web(paramName);
        this.paramValue = web(paramValue);
        this.operator = web(operator);
        this.separator = web(separator);
        this.comment = web(comment);
        this.reservedWord = web(reservedWord);
        this.literalString = web(literalString);
        this.literalNumber = web(literalNumber);
        this.function = web(function);
        this.datatype = web(datatype);
        this.tagName = web(tagName);
        this.cdata = web(cdata);
        this.lineNumbers = web(lineNumbers);
        this.selectionBackground = web(selectionBackground);
        this.selectionForeground = web(selectionForeground);
    }

    private static Color web(String rgb) {
        return Color.decode(rgb);
    }

    public Color getBackground() {
        return background;
    }

    public Color getText() {
        return text;
    }

    public Color getHttpFirstLine() {
        return httpFirstLine;
    }

    public Color getHeaderName() {
        return headerName;
    }

    public Color getHeaderValue() {
        return headerValue;
    }

    public Color getParamName() {
        return paramName;
    }

    public Color getParamValue() {
        return paramValue;
    }

    public Color getOperator() {
        return operator;
    }

    public Color getSeparator() {
        return separator;
    }

    public Color getComment() {
        return comment;
    }

    public Color getReservedWord() {
        return reservedWord;
    }

    public Color getLiteralString() {
        return literalString;
    }

    public Color getLiteralNumber() {
        return literalNumber;
    }

    public Color getFunction() {
        return function;
    }

    public Color getDatatype() {
        return datatype;
    }

    public Color getTagName() {
        return tagName;
    }

    public Color getCdata() {
        return cdata;
    }

    public Color getLineNumbers() {
        return lineNumbers;
    }

    public Color getSelectionBackground() {
        return selectionBackground;
    }

    public Color getSelectionForeground() {
        return selectionForeground;
    }
}
