# Akagi for Android 16

The app opens the English Mahjong Soul site in a persistent WebView and displays local advice in a small overlay. Tap the chip to expand it; hold and drag to move it. Its visible surface is 34 dp high with a 48 dp touch target. Movement stays in the upper part of the game, away from the player's hand. Settings include an optional portrait mode, session reload, the bundled saved-hand check, model validation, and Yostar account help.

Use Yostar email sign-in. Existing Google players should first bind an unused Yostar account from the game's account settings in a browser or app where their Google login works. Google authorization navigation inside the app opens the binding explanation. It does not transfer browser cookies into the game.

`MainActivity` supplies `UiState` to `AkagiApp`. Only the coordinator supplies advice. A null `UiAdvice` immediately removes the previous action. Diagnostics appear inside settings. The browser calls `onSessionReset` at navigation, capture failure, and renderer recovery, and sends capture JSON through `onCapture`.

The manifest keeps the Activity through rotation, resizing, font changes, and theme changes. The shell handles immersive bars, WebView pause/resume, cookies, back navigation, and renderer replacement. Persistent cookies and web storage belong to Android WebView; app data is excluded from backup.

## Capture contract

`assets/browser/capture.js` is registered with AndroidX `addDocumentStartJavaScript` after `addWebMessageListener`, before the first navigation. Both use exact allowed game origins. Native delivery checks the origin again. The installed WebView must support both features; otherwise the game remains available and the overlay explains how to enable capture by updating WebView. [AndroidX WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat)

Every message has `type`, `generation`, `sequence`, `connectionId`, `url`, `direction`, and native `sourceOrigin` / `mainFrame` annotations. Document generations are independent for trusted frames. Sequence numbers start at 1 and include `capture_ready`, `websocket_created`, `websocket`, `websocket_closed`, and `capture_error`. Binary payloads have `binary: true` and base64 `data`; text has `binary: false` and unchanged text `data`.

One promise queue per document preserves ordering across all connections and asynchronous Blob conversions. Typed arrays and ArrayBuffers are copied at observation time. Bounded buffers report a capture error on overflow. Native WebSocket construction, constants, inheritance, send return values, errors, and payload delivery stay intact.

This hook observes document and matching-frame WebSockets. Sockets created inside workers require separate coverage verification against the live game before claiming complete worker support. Browser recovery clears advice until the protocol coordinator resynchronizes.

## Builds and validation

Builds run in GitHub Actions under the installed no-local-compilation skill. The wrapper pins Gradle 8.13 with its distribution checksum. The project pins AGP 8.13.2, Kotlin 2.2.21, Compose BOM 2025.10.01, and WebKit 1.14.0. Java 17 and API 36 are required. Default ABIs are `arm64-v8a,x86_64`; CI may select an ABI with `-PandroidAbis=arm64-v8a`. Models are stored without ZIP compression and JNI libraries use current APK packaging. [AGP compatibility](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes), [Compose BOM](https://developer.android.com/jetpack/compose/bom/bom-mapping)

The internal debug-only `BrowserFixtureActivity` hosts the same shell without an inference coordinator. Its instrumentation tests cover fullscreen geometry, touch targets and game input, advice clearing, moving and collapsing controls, settings and account help, rotation/background preservation, cookies across recreation, reload generations, and larger system text. Separate tests use a real local HTTP/WebSocket echo server to verify document-start injection, Blob ordering, typed-array mutation, native WebSocket semantics, frame generations, and restricted bridge origins.

Tests write screenshots to the target app's `externalFilesDir/screenshots`, normally `/sdcard/Android/data/org.akagi.mobile.debug/files/screenshots`. Screenshot names include `fixture` labels to distinguish deterministic UI fixtures from actual Mahjong Soul sessions. Any displayed test advice is explicit instrumentation input, not a model result.

The debug build accepts the Intent extra `akagi.debug.fixture=table` for the offline game-layout fixture. Instrumentation uses `akagi.debug.fixture=capture` and `akagi.debug.fixturePort` for its emulator-loopback server. Release builds ignore these extras, exclude fixture assets and the fixture Activity, and prohibit cleartext traffic. The default launch always opens the real game.
