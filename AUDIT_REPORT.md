# Prettify Add-on Audit Report

Status of the memory, lifecycle and performance audit. Every item below was re-checked against the
current code; the fixes were verified with `./gradlew check` (JUnit 5).

## Fixed

### 1. `DisplayTextSplitter.LINE_CACHE` not cleared on session change
**Severity: High — fixed**

The static `LineCache` (16 MB budget) was only cleared in `ExtensionPrettify.unload()`, so tokenized
lines from a previous session stayed resident after a session switch.

**Fix:** `ExtensionPrettify` now implements `SessionChangedListener`, registers itself with
`ExtensionHook.addSessionListener()`, and clears the cache in `sessionChanged` and
`sessionAboutToChange`. `unload()` clears it as well (after stopping the workers). The cache also
keeps an epoch: a split captures it when it starts, and a `put` whose epoch is stale is dropped, so
a worker that was already running when the cache was cleared (and is only interrupted, not awaited)
cannot repopulate it with old-session lines.

### 2. `PrettifyHttpPanelView.CHAR_WIDTHS` cache retains `Font` objects
**Severity: Medium — fixed**

The static access-ordered `LinkedHashMap<Font, FontCharWidths>` (max 8 entries) was never cleared.

**Fix:** Added `PrettifyHttpPanelView.clearStaticCaches()`, called on session change and add-on
unload. The clear takes the same monitor (`synchronized (CHAR_WIDTHS)`) as the views, because
`get()` on an access-ordered map mutates its order.

### 3. Per-view caches hold the last message's large strings/documents
**Severity: Medium — fixed**

Fields such as `cachedSplit`, `splitDocument`, `splitLineNumbers`, `splitDisplay`,
`projectionRaw` and `projectionDisplay` were only released with the view itself.

**Fix:** `dataChanged()` now compares the model's message identity. When the message object actually
changes, `clearPerViewCaches()` drops all message-sized artifacts (and the body/query round-trip
state). A same-message notification keeps them: the split/document reuse and the header/body
round-trip behaviour are preserved, and a data change is still caught by the existing cache keys.

### 4. `HttpTokenMaker.contentTypeValue` unbounded growth
**Severity: Low — fixed**

The header-value accumulator could grow with a pathological or hostile Content-Type value (including
repeatedly appending a wrapped header's value).

**Fix:** The captured value is capped at 512 characters (`MAX_CONTENT_TYPE_CHARS`), far beyond the
type/subtype the delegate selection needs. While there, the per-line `"body:" + style` cache key is
now computed once when the delegate is (re)resolved instead of allocating one string per body line.

### 5. `TextAlignment.applyEditToRaw` rescanned the texts
**Severity: High — fixed**

The save path revalidated the display/raw pair and rescanned from the start to map the changed
range.

**Fix:** Added `applyEditToRaw(raw, formatted, edited, Mapper)` and `getMapper(...)`. `save()` creates
one validated `Mapper` and applies the edit through its forward cursors. The overload preserves the
hidden-header-terminator and blank-line rules of the original method. Tests assert equality with the
three-argument method over JSON edits, header edits, CRLF/LF whitespace runs and stripped
terminators.

### 6. `PrettyPrint.indexOfIgnoreCase` scanned every glyph at every offset
**Severity: High — fixed**

`indexOfIgnoreCase` called `regionMatches(true, ...)` at every offset while looking for a raw-text
closing tag, which is O(n·m).

**Fix:** Check the needle's first character (both cases) before calling `regionMatches`; closing tags
start with `<`, which is rare in script/style bodies.

### 7. `PrettyPrint.tokenizeMarkup` scanned each tag twice
**Severity: Medium — fixed**

`isTagStart()` called `findTagEnd()` and then the tag branch called `findTagEnd()` again for every
letter-initial tag.

**Fix:** Compute `findTagEnd()` once and validate the following character (`/` or a letter) from the
same scan; removed the now-redundant `isTagStart()`.

### 8. `PrettifyViewOptionsMenu` pinned the last text area
**Severity: Medium — fixed**

The popup menu is a long-lived singleton, but held a strong reference to the last
`PrettifyTextArea` it was shown for, retaining that view's document and message.

**Fix:** Store the text area in a `WeakReference`; the menu only needs it while the popup is open.

### 9. `ScriptTokenHighlighter.recolor` allocated a list per painted line
**Severity: Medium — fixed**

Every visible line's token list was copied into an `ArrayList` before scanning, adding an allocation
per line per repaint while scrolling.

**Fix:** Walk the RSTA token linked list directly. `nextVisible()` and `methodBodyFollows(Token)`
reproduce the previous skip rules (empty, invisible and trailing state-only tokens) without boxing
or copying.

### 10. `LineCache.put` miscounted retained size on replacement
**Severity: Medium — fixed**

Replacing an existing key subtracted only the old value's cost, not the retained key line, so the
budget accounting drifted and evicted too little.

**Fix:** Subtract both the old value and the old key line on replacement.

## Reviewed — no change needed

### 11. `DisplayTextSplitter.Key` stores the line text
The key keeps the line string because `equals()` must compare the actual line to be correct; a hash
alone would make collisions return wrong tokens. The string is already the split's line object (not
an extra copy), and its retained size is included in the 16 MB budget. Replacing it with a weaker
key would trade correctness for a small memory saving.

### 12. `LineNumbers.forDisplay`
The walk is already linear and its blank-line numbering rules are not expressible with the simple
`TextAlignment.Mapper`. Leaving it avoids a riskier rewrite for no asymptotic gain.

### 13. `DisplayTextSplitter.copyChunked`
The character scan only runs for lines longer than 2000 characters and is needed to avoid breaking
inside strings/comments. A quote-presence fast path would still have to scan the line once, so the
gain is marginal.

### 14. `PrettifyExecutor` shutdown/handover
`get()` recreates the executor when it is null or shut down, and the worker threads are daemons. The
brief overlap during an unload/reload is bounded and harmless; no lock/delay was added.

### 15. `OptionsChangedListener`
The add-on has no global option of its own. Look-and-feel changes are already handled by
`PrettifyTextArea.updateUI()`, so an extension-level listener would add no behaviour.

## Fixed — second pass

### 16. `FormUrlEncodedTokenMaker` rescanned the line for every parameter
**Severity: High — fixed**

For a name, the token maker searched separately for `=` and `&` across the whole remaining line. A body without values (`a&b&c&...`) then rescanned the rest of the line once per parameter, which is O(n²).

**Fix:** One forward scan finds the next `=` or `&`; the scan position now only moves forward. A regression test covers unvalued parameters mixed with valued ones.

### 17. `ScriptTokenHighlighter.recolor` allocated strings per token
**Severity: Medium — fixed**

The direct token walk still called `getLexeme()` on every painted token and used string equality for `{`, `,`, `:` and `(`. That allocates a string per token per visible line per repaint (and in `methodBodyFollows`). The key-position logic also carried a redundant `previous` lexeme.

**Fix:** Compare tokens with `charAt`/`length` (`isSingle`) and track key position as a boolean set by `{`/`,`; no lexeme strings are created. Added `ScriptTokenHighlighterTest` covering quoted/unquoted keys, method shorthand versus a call, continuation lines, and script-range filtering.

### 18. `PrettifyDocument.advanceTo` grew the line-state array by one line
**Severity: Medium — fixed**

Each inserted line reallocated the whole `lineState` array to exactly the new line count, making a multi-line edit quadratic.

**Fix:** Grow geometrically (`max(count, max(16, length * 2))`).

### 19. `DisplayTextSplitter.shiftOffsets` scanned and copied every continuation row
**Severity: Medium — fixed**

Every insert/remove event walked and copied the whole continuation-offset array, which is large for a wrapped message.

**Fix:** The offsets are sorted, so two binary searches locate the kept prefix and the shifted suffix; only the prefix is copied and only the suffix is moved. Existing semantics (offsets exactly at the insertion move; offsets inside a removal are dropped) are covered by `shiftOffsetsKeepsThePrefixAndMovesOnlyTheSuffix`.

### 20. Search highlighting revalidated the display/raw pair for every match
**Severity: High — fixed**

`highlight()` and `search()` built a fresh `TextAlignment.Mapper` each time, and building one verifies that both texts contain the same non-whitespace characters: a full message walk. Stepping through N search results was O(N·message).

**Fix:** Added a per-view mapper validation cache keyed by the identity of the immutable display/raw strings. `install()` and `save()` invalidate it; `clearPerViewCaches()` does too. Mapper calls are synchronized because the mapper keeps mutable forward cursors. The header/body separator search (`bodyStartInLf`) is cached for the same raw string, so stepping through results in an All view does not rescan the message per result. `search()` now also uses `displaySource` instead of copying the document text for read-only views, and keeps editable views on `textArea.getText()`.

### 21. Editable gutter renumbered every line on every keystroke
**Severity: Medium — fixed**

`updateEditableNumbering()` walked the whole document (one `getText` per line) and rebuilt the number array on every document event, which makes typing in a large editable message lag.

**Fix:** The view keeps `editableLineBlank`/`editableLineNumbers`; document events record the dirty line range, and an edit with an unchanged line count re-reads only those lines then renumbers the suffix. A line-count change (Enter, paste, deleting a line break) falls back to a full rebuild, which is also used on install. `editableGutterNumbersFollowBlankLinesAndEdits` covers insertion of a blank line, typing on it, and deleting the text again.

## Reviewed — no change needed (second pass)

### 22. `HttpPanel.removeView` and the model listener
`HttpPanelComponentViewsManager.removeView` removes the view from its maps and panel but does not call `removeHttpPanelViewModelListener`. No cleanup is needed here: `ExtensionPrettify` creates a new model per view (`models.get()`), and only that view registers on it. The model→view reference is a closed cycle owned by the view, so the view and its model become unreachable together; no other component keeps the model. Adding an unregister call would require a removal hook ZAP does not provide through `HttpPanelView`.

### 23. `DisplayTextSplitter.Split.toString`
It prints the full break array, but it is debug-only and never called from the render path.

### 24. `rememberBody` copies the body for the All views
The body is obtained with `substring` at most once per render/data-changed event; keeping the copy is required for the request body/query round trip and is not on a per-character hot path.

### 25. 64 KB header scan
`PrettifyDocument.bodyStart()`/`headerBlock()` read up to 64 KB once per document build (not per line), lowercasing only that slice. The bound is needed to distinguish a body that looks like a header from real headers; the cost is linear in the header bound.

### 26. `TextAlignment.toOriginalOffset`
The linear scan is used by tests/tools, not by the view's render path.

## Threading / lifecycle checks

- `LineCache` methods are `synchronized`; `CHAR_WIDTHS` accesses all hold the map monitor.
- `PrettifyHttpPanelView` still uses its generation counter to cancel superseded background work.
- The session listener performs only cache clears and `shutdownNow()` (which does not wait); no EDT work is blocked.
- The mapper cache is only touched under the view monitor, and mapper cursors are only advanced under the mapper monitor.
- Editable gutter arrays and dirty ranges are only mutated from document events and the coalesced EDT update.

## Verification

`./gradlew check` — BUILD SUCCESSFUL, 137 tests, 0 failures, 0 skipped (JUnit 5).

