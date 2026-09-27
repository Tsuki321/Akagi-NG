Validation recorded on 2026-09-27. Android source revision `2fd8edb241941b5501d6aef8e10d9833aee59d90` passed the complete [Android workflow](https://github.com/Tsuki321/Akagi-NG/actions/runs/36291249663) and [UI workflow](https://github.com/Tsuki321/Akagi-NG/actions/runs/36291249631). Later packaging changes are checked by their own workflow before a signed APK is delivered.

| Check | Observed result |
| --- | --- |
| Desktop/native four-player parity | 11 traces; observation tensors, legal masks, decoded legal actions and lookahead checked. |
| Desktop/native three-player parity | 25 traces, including all seat/caller pairs, kita and kan variants, riichi, wins, and historical 775-channel features. |
| PyTorch/ONNX score parity | 62 real observations, including kan selection; maximum legal-score error approximately 5.73e-6. Selected actions matched. |
| Kotlin Liqi/MJAI protocol tests | 14 passed, zero failures; wire fixtures generated with the original Python bridge. |
| A54-sized Android 16 emulator | 22 instrumentation tests passed at 1080×2340 and 420 dpi. |
| Compact Android 16 emulator | 22 instrumentation tests passed at 720×1600 and 320 dpi. |
| UI-focused emulator run | All 15 UI/capture tests passed on both sizes. |
| Interaction stress | 250 injected interactions per device; no reported application crash or ANR. |
| Embedded public game | The official Mahjong Soul page completed resource loading and displayed Login inside the app. |
| Native packaging | Every packaged shared library passed 16 KB ELF alignment; APK ZIP alignment passed. |

Both device suites exercised the actual ONNX Runtime library and native rules engine. The end-to-end game tests sent serialized Liqi messages through a real loopback WebSocket into the application's WebView, protocol adapter, coordinator, native engine and advice UI. They verified a real four-player discard and three-player kita, invalid-message suppression, complete-round recovery, disconnect, reconnect replay, and clearing after game end.

The dora transaction test compared model scores from a draw plus its trailing dora announcement with scores from applying the dora before the draw. The reach-payment test preserved a valid ron without premature furiten. Transaction errors, suppressed replay and foreground recomputation were also checked.

Physical UI tests verified taps reach the game button and hand tiles, including the last tile in portrait. They exercised the compact chip, dragging, expansion, settings dismissal with Back, large system text, cookie/session persistence, renderer recreation and stale-advice removal. Screenshots were visually inspected after the emulator display settled. Initial tests caught and led to fixes for unreadable overlay text, fixture/browser sizing, first-use fullscreen education handling, and asynchronous dialog/rotation capture.

In 30 saved-hand samples alternating between models, measured p95 inference times were 56 ms on the A54-sized emulator and 68 ms on the compact emulator. These timings include the particular test setup and are **not measurements on an Exynos Galaxy A54**. They do not establish phone thermal behavior, battery use or sustained online-match latency.

No credentials were submitted and no authenticated online match was played. Account binding, authenticated sessions and extended real-device play remain user/device checks. Public login screenshots establish that the game renders in the embedded browser; they do not establish account authentication.

Artifacts use distinct labels: `fixture` for the local test table; `live_3p`/`live_4p` for real local inference through loopback protocol traffic; and `public-game` for the actual Mahjong Soul website. Model parity reports, instrumentation output, screenshots, interaction logs and timings are retained in the linked Actions runs.
