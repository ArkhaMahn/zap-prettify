# How Burp's Repeater handles requests and responses

Notes gathered from the PortSwigger documentation, and what they mean for the Pretty view.

Sources:

- [Text editor](https://portswigger.net/burp/documentation/desktop/tools/message-editor/text-editor)
- [Message editor](https://portswigger.net/burp/documentation/desktop/tools/message-editor)
- [Modifying requests using the Inspector](https://portswigger.net/burp/documentation/desktop/tools/inspector/modify-requests)
- [Burp Repeater](https://portswigger.net/burp/documentation/desktop/tools/repeater)

## The model

Burp keeps **one** message. Everything the user sees is a view of it:

| Tab | What it shows |
| --- | --- |
| Raw | the full message in its raw form |
| Pretty | the same editor with the pretty printer switched on |
| Hex | the message as bytes |
| Render | the response body as a browser would show it |
| GraphQL | the query and the variables, separated |

The Inspector is a third view of the same message: "the message editor updates to contain the new
item", and edited values "are injected into the request". The Repeater, the requester and the manual
request editor all use that same message editor, so they behave identically.

## Pretty printing

> In the **Pretty** tab, supported text formats are automatically prettified in the text editor. The
> editor currently supports pretty printing of the following formats: JSON, XML (including
> `image/svg+xml`), HTML, CSS, JavaScript.

> In editable messages, such as in Burp Repeater, supported text formats are dynamically prettified
> as you type. Otherwise, the text is prettified when you send the request.

> This tab is only available if the message contains content in one of the supported formats.

Consequences:

- Form bodies are **not** prettified (they are not one of the supported formats).
- In an editable message the pretty form is what the user works with; it is refreshed as they type.
- In a message that cannot be edited, the pretty form is produced when the message arrives (the
  response of the request that was sent).

## Line wrapping and line numbers

> In both the **Pretty** and **Raw** tabs, lines in requests and responses are automatically wrapped
> to fit the width of the text editor.

> Line numbers are displayed so that you can still keep track of the original line breaks.

So wrapping is a **display** feature: the text itself keeps its line breaks, and the gutter numbers
the original lines, not the wrapped rows.

## Syntax highlighting

> Syntax in HTTP requests and responses is automatically colorized to highlight interesting items,
> such as parameters in requests and HTML elements in responses. JavaScript, JSON, and CSS content is
> also fully colorized.

The highlighting follows the content type: the HTTP structure is always colourised, and the body is
colourised with the grammar of its content type.

## What this means for ZAP

ZAP has no separate "Repeater": the Requester, the manual request editor and Break all use
`HttpPanelRequest` / `HttpPanelResponse`, so a single Pretty view implementation covers them all
(the same way one message editor component covers all of Burp's).

| Burp | ZAP |
| --- | --- |
| one message, several views | the `HttpMessage` and the views of the request/response panel |
| Raw tab | the core "Text" view |
| Pretty tab | the "Pretty" view of this add-on |
| Hex tab | the core "Hex" view |
| the Inspector edits the message | the core "Params" view and ZAP's own inspectors |
| the wrap is display only | the same here: the wrap breaks are removed again before the message is written |
| the gutter numbers the original lines | the same here: only the rows that start a real message line are numbered |
| JSON, XML, HTML, CSS and JavaScript are prettified | the same list; form bodies are left alone |

### How this is implemented here

- The message keeps the text it was received with. The Pretty view is a projection of it: the
  formatted text, wrapped for the viewport.
- An edit in the Pretty view is mapped back onto the message before it is saved. The mapping uses
  the characters that are not whitespace: the formatter only changes whitespace, so the position of
  the change in the message can be found by counting them. The message therefore keeps its own
  whitespace and only the change the user made is different.
- The text is left alone while it is typed: it does not jump under the cursor. The message is
  written with the change when the panel is saved, and the view is rendered again from it, so the
  Pretty view shows the formatted form again.
- The wrap is only a display feature: the breaks the view inserts are removed again before the
  message is written.
- The gutter numbers the rows that start a real message line; the wrapped rows and the rows the
  formatter added are left blank.

### Differences from Burp

- **No live prettification.** Burp re-formats the message while it is typed in an editable message.
  Here the text stays as it was typed, so the cursor and the undo history stay stable; the
  formatted form is shown again when the view is rendered from the message.
- **The core text view has its own buffer.** ZAP's core text view keeps what was typed in it until
  the panel is saved, so the Pretty view follows the message rather than the core view's buffer
  while it is being typed in.
