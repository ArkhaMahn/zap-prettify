package org.zaproxy.zap.extension.prettify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContentTypeTest {

    @Test
    void customContentTypeWithJsonBodySniffsJson() {
        assertEquals(
                "text/json",
                ContentType.styleKeyFor(
                        "application/vnd.acme.recommendations", "[{\"name\":\"container_1\"}]"));
    }

    @Test
    void customContentTypeWithXmlBodySniffsXml() {
        String body = "<root><item id=\"1\">value</item><empty/></root>";
        assertEquals("text/xml", ContentType.styleKeyFor("application/soap+xml", body));
        assertEquals("text/xml", ContentType.styleKeyFor("application/vnd.acme.thing", body));

        String formatted =
                PrettyPrint.formatBody(
                        ContentType.styleKeyFor("application/vnd.acme.thing", body), body);
        assertNotEquals(body, formatted);
        assertTrue(formatted.contains("\n  <item"));
        assertTrue(TextAlignment.sameNonWhitespace(body, formatted));
    }

    @Test
    void customContentTypeWithCssBodySniffsCss() {
        assertEquals(
                "text/css",
                ContentType.styleKeyFor("application/vnd.acme.styles", ".card{color:red}"));
        assertEquals(
                "text/css",
                ContentType.styleKeyFor("text/plain", "@media screen{.card{color:red}}"));
    }

    @Test
    void customContentTypeWithGraphqlBodySniffsGraphql() {
        assertEquals(
                ContentType.GRAPHQL_STYLE,
                ContentType.styleKeyFor("application/vnd.acme.graph", "query Viewer { id }"));
        assertEquals(
                ContentType.GRAPHQL_STYLE,
                ContentType.styleKeyFor("text/plain", "{ viewer { id } }"));
        assertEquals(
                ContentType.GRAPHQL_STYLE,
                ContentType.styleKeyFor(
                        "text/plain", "# a comment\nquery Viewer { id }"));
    }

    @Test
    void customContentTypeWithJavaScriptAfterLineCommentSniffsJavaScript() {
        String bundle =
                "// For license information, see https://example.test\n"
                        + "window.x=window.x||{},window.x.y={buildInfo:{minified:!0}};";
        assertEquals(
                "text/javascript",
                ContentType.styleKeyFor("application/vnd.acme.bundle", bundle));
    }

    @Test
    void binaryBodyIsNeverSniffed() {
        String binary = "\u0000\u0001\u0002{\"a\":1}";
        assertEquals(
                ContentType.PLAIN_STYLE,
                ContentType.styleKeyFor("application/vnd.acme.thing", binary));
    }

    @Test
    void protobufTypesAreBinary() {
        List<String> types =
                List.of(
                        "application/protobuf",
                        "application/x-protobuf",
                        "application/vnd.google.protobuf",
                        "application/grpc",
                        "application/grpc-web",
                        "application/grpc+proto",
                        "application/vnd.acme+proto");
        for (String type : types) {
            assertEquals(ContentType.PLAIN_STYLE, ContentType.styleKeyFor(type), type);
            assertEquals(
                    ContentType.PLAIN_STYLE,
                    ContentType.styleKeyFor(type, "{\"a\":1}"),
                    type);
        }
    }

    @Test
    void formTypeStaysFormAndIsNotFormatted() {
        String body = "a=1&b=2&c=3";
        assertEquals(
                ContentType.FORM_STYLE,
                ContentType.styleKeyFor("application/x-www-form-urlencoded"));
        assertEquals(body, PrettyPrint.formatBody(ContentType.FORM_STYLE, body));
    }

    @Test
    void largeBodyIsFormatted() {
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < 20_000; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"i\":").append(i).append('}');
        }
        body.append(']');
        String raw = body.toString();
        assertTrue(raw.length() > 100_000, "the test body must exceed the old 100k cutoff");

        String formatted =
                PrettyPrint.formatBody(
                        ContentType.styleKeyFor("application/vnd.acme.data", raw), raw);

        assertNotEquals(raw, formatted);
        assertTrue(formatted.contains("\n"));
        assertTrue(TextAlignment.sameNonWhitespace(raw, formatted));
    }
}
