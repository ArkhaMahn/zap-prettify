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

### Differences that cannot be avoided in ZAP

- **The wire content.** In Burp the message is the raw bytes; the Pretty tab is a projection of it
  and an edit in the Pretty tab is mapped back onto the raw message. In ZAP the panel saves the view
  that is currently selected, so the Pretty view writes what it shows into the message. The Pretty
  view is therefore "what you see is what is sent".
- **Live prettification.** Burp re-formats the message while it is typed. ZAP's editor does not do
  that: the text is left alone while it is typed, which keeps the cursor and the undo history stable.
- **Live sync between the raw and the pretty view.** Burp refreshes the Pretty tab while the raw
  tab is being typed in. In ZAP the core text view keeps its own buffer until the panel is saved, so
  the Pretty view follows the message, not the core view's buffer.
