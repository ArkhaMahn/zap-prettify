# Prettify for OWASP ZAP

Prettify adds Burp-style, syntax-highlighted "Pretty" views to the OWASP ZAP HTTP request and response panels. It presents messages with beautiful syntax highlighting, smart line wrapping, and original-line numbering as additional tabs without replacing ZAP's core views.

## Features

- **Additional tabs, no replacement** - Adds "Pretty" tabs for Request Split (Header/Body), Response Split (Header/Body), Request All, and Response All. The core Text view remains unchanged and fully functional.
- **Syntax highlighting** - Always-on syntax highlighting powered by RSyntaxTextArea. Automatically detects and highlights HTTP, JSON, XML, HTML, JavaScript, CSS, and form data based on Content-Type.
- **Burp-like theming** - Light and dark themes that automatically match ZAP's look and feel.
- **Smart pretty-printing** - JSON, XML, and other structured content are prettified for better readability. Header-only views are cleaned up without affecting the underlying message structure.
- **Pre-wrapped display** - Lines are pre-broken at the viewport width for fast rendering of large messages. Horizontal scrolling is disabled for a cleaner view. Wraps automatically adjust on panel resize.
- **Original line numbering** - Only original message lines are numbered in the gutter; wrapped continuations and pretty-print-inserted lines are left blank, matching Burp's behavior.
- **Editable support** - Pretty views can be edited in editable contexts (like the Requester). Edits are tracked, wrapped breaks are stripped on save, and line numbers follow your edits dynamically.
- **Large message support** - Content is prepared off the EDT and rendered in chunks with generation-based cancellation to keep the UI responsive.
- **Search integration** - Works with ZAP's search functionality, with proper offset mapping between wrapped display text and original message offsets.
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

Prettify has no configurable options at this time. It automatically adapts to your ZAP theme (light/dark) and panel sizes.

## Uninstallation

Prettify is fully cleanly unloadable. You can disable or uninstall it from `Tools` → `Add-ons` without affecting ZAP's core functionality or requiring a restart in most cases.

## Technical Details

- **Package**: `org.zaproxy.zap.extension.prettify`
- **Extension Class**: `ExtensionPrettify`
- **Views**: Implemented as `HttpPanelView` instances registered through `HttpPanelManager`
- **Rendering**: Uses RSyntaxTextArea with custom token makers for HTTP protocol parsing
- **Threading**: Heavy processing (formatting, wrapping, tokenization) happens off the EDT with chunked loading

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

For issues, bug reports, or feature requests, please use the [GitHub Issues](https://github.com/your-username/prettify-addon/issues) page.
