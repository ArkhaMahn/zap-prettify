# Prettify for OWASP ZAP

Prettify adds syntax-highlighted "Pretty" views to the OWASP ZAP HTTP request and response panels. It presents messages with beautiful syntax highlighting, viewport-width line splitting, and original-line numbering as additional tabs without replacing ZAP's core views.

## Features

- **Additional tabs, no replacement** - Adds "Pretty" tabs for Request Split (Header/Body), Response Split (Header/Body), Request All, and Response All. The core Text view remains unchanged and fully functional.
- **Syntax highlighting** - Always-on syntax highlighting powered by RSyntaxTextArea. Automatically detects and highlights HTTP, JSON, XML, HTML, JavaScript, CSS, and form data based on Content-Type, with a conservative body sniff for custom or missing types.
- **Light and dark theming** - Themes that automatically match ZAP's look and feel, including live refreshes when the Look and Feel changes.
- **Smart pretty-printing** - JSON, XML, HTML, CSS, JavaScript, TypeScript, and GraphQL are prettified for better readability, including when they arrive under a custom content type that has to be sniffed. Header-only views are cleaned up without affecting the underlying message structure. Form-encoded bodies are shown exactly as received, and binary or protobuf payloads are never formatted.
- **Pre-split display** - Long lines are split into display rows at the calculated viewport width, so header values, URLs, and other unbroken runs fill each row. The breaks exist only in the display: the message itself is untouched, and rows are recalculated immediately when the panel is resized.
- **Original line numbering** - Only original message lines are numbered in the gutter; split continuations and pretty-print-inserted lines are left blank.
- **Editable support** - Pretty views can be edited in editable contexts (like the Requester). Edits are applied back to the raw message through a non-whitespace alignment, so the original whitespace and line endings outside the changed range are preserved. Copying a selection yields the received text, not the formatter's projection. Line numbers follow your edits dynamically. Header order and repeated headers are preserved, so added or duplicated headers stay where you put them. Auto-indent keeps the formatted structure while you type, and the undo history is unlimited and survives sending a request, so Ctrl+Z can walk back through earlier states.
- **Paste formatting** - Pasting a JSON, XML, HTML, CSS, JavaScript or GraphQL body formats it before inserting it. Form-encoded, binary and protobuf bodies are inserted exactly as pasted. A header paste is never reformatted.
- **Large message support** - Formatting and tokenization run off the EDT with generation-based cancellation and chunked document loading. There is no size cutoff for pretty-printing: large bodies are formatted like any other, with the work kept off the UI thread.
- **Search integration** - Works with ZAP's search functionality, with offset mapping through the formatted projection back to the raw message offsets.
- **ZAP highlights and text actions** - Participates in ZAP's `HighlighterManager`, so user-defined highlights are painted like in the core views, and offers the standard undo/redo/cut/copy/paste/delete/select-all actions in the message popup menu.
- **Persisted view settings** - Word wrap, line numbers, anti-aliasing, current-line highlighting, whitespace/newline markers, and related editor options are saved per view and restored on restart.
- **Smart body handling** - Handles ZAP's behavior of moving request bodies when changing HTTP methods, preserves form-encoded bodies when editing to avoid breaking structure, and can auto-detect and set Content-Type headers when missing.

## Requirements

- **OWASP ZAP** 2.17.0 or later
- **Java** 17 or later

## Installation

### From the ZAP Marketplace (Recommended)
1. Open OWASP ZAP
2. Go to `Tools` → `Options` → `Marketplace`
3. Search for "Prettify"
4. Click `Install`

### Manual Installation
1. Download the `.zap` add-on file from the [releases page](https://github.com/your-username/prettify-addon/releases)
2. Open OWASP ZAP
3. Go to `File` → `Load Add-on File...`
4. Select the downloaded `.zap` file
5. Restart ZAP if prompted

## Building from Source

### Prerequisites
- Java 17 JDK
- Gradle 8.x (or use the Gradle wrapper)

### Build Steps

```bash
# Clone the repository
git clone https://github.com/your-username/prettify-addon.git
cd prettify-addon

# Build the add-on
./gradlew build
```

The compiled add-on will be generated at:
```
build/zapAddOn/bin/Prettify-addon-1.0.0.zap
```

### Development Build

```bash
# Compile only
./gradlew compileJava

# Run tests
./gradlew test
```

## Usage

1. Open any HTTP request or response in ZAP (History, Sites tree, Active Scan, Requester, etc.)
2. In the request/response panel, you'll see new "Pretty" tabs alongside the existing tabs
3. Click "Pretty" to view the syntax-highlighted, formatted version
4. In editable contexts, you can edit the Pretty view directly - changes will be saved back to the message

## Configuration

Prettify adapts to your ZAP theme (light/dark) and panel sizes automatically. The standard editor options - word wrap, line numbers, anti-aliasing, current-line highlighting, whitespace and newline markers, occurrence marking, rounded selection, and bracket matching - are saved with the view configuration and restored on restart. Word wrap applies to editable views; read-only views are always split to the viewport width instead.

There is no size cutoff for pretty-printing: a body is formatted whenever its type is recognised, however large. Binary and protobuf payloads are shown as received.

## Uninstallation

Prettify is fully cleanly unloadable. You can disable or uninstall it from `Tools` → `Add-ons` without affecting ZAP's core functionality or requiring a restart in most cases.

## Technical Details

- **Package**: `org.zaproxy.zap.extension.prettify`
- **Extension Class**: `ExtensionPrettify`
- **Views**: Implemented as `HttpPanelView` instances registered through `HttpPanelManager`
- **Rendering**: Uses RSyntaxTextArea with custom token makers for HTTP protocol parsing. Read-only views are pre-split into display rows at the measured viewport width, which keeps painting and scrolling on the fast non-wrapping layout path; editable views use normal word wrap.
- **Threading**: Formatting and tokenization happen off the EDT with generation-based cancellation and chunked document loading; only the finished document is installed on the EDT
- **Text mapping**: `TextAlignment` maps display offsets to raw-message offsets through non-whitespace alignment, so edits, search results, highlights, and clipboard copies refer to the received bytes

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

For issues, bug reports, or feature requests, please use the [GitHub Issues](https://github.com/your-username/prettify-addon/issues) page.
