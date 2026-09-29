# Android game assistance

The settings panel has two independent switches:

- **Autoplay** follows local model decisions after a newly sampled delay between 1,000 and 3,000 milliseconds. The chip displays `AUTO` while enabled. Close settings and collapse advice to return to the game and resume autoplay. Touch the game or turn the switch off to stop. Opening advice/settings, switching apps, navigation, a disconnected socket, or a new turn cancels pending work. Autoplay is off when the app starts.
- **Highlight moves** outlines the recommended discard, riichi discard, or consumed tiles in your hand and shows a nearby action label for calls, kan, kita, wins, and passes. Touches pass through the overlay. This preference survives restarts and works with autoplay off.

Autoplay uses the exact operation and combination offered by the server. Riichi uses Mortal's follow-up discard. Red fives, ordinary fives, and the separately drawn tile retain their identity. A missing or incompatible operation leaves that move to the player. A response error or missing acknowledgement stops autoplay without retrying the move.

## Current-turn checks

`MjaiAdapter` exposes a copy of the sorted concealed hand plus the drawn tile. `MahjongSoulProtocol` attaches it to the current document, socket, operation list, and capture revision. `GameDecision` validates the model's action against those operations and supplies a concrete input request and tile indices to the browser.

`assistance.js` and `capture.js` run before game scripts, on the existing exact allowed origins. Native commands travel through the WebView message reply associated with the originating document. The browser checks the live socket revision again immediately before sending. This closes the gap between a new game frame arriving and the native model worker processing it. Manual input, duplicate recommendations, and delayed results cannot send the same decision twice.

The server's `time_fixed` and `time_add` are milliseconds. The outgoing `timeuse` field is whole seconds. Inference and scheduling time count against the remaining turn budget. If fewer than 1,000 milliseconds plus a small delivery margin remain, autoplay leaves that turn to the player instead of making a faster move.

The transport uses the game's already open, authenticated socket. It tracks outstanding RPC identifiers, reserves a free identifier for its own move, and consumes its own response before the game's handler sees it. If the client concurrently chooses that identifier, its request is assigned another free wire identifier and its response is translated back. Capture retains the wire identifiers so native request/response decoding remains consistent.

Input fields were checked against the public Mahjong Soul client (`v0.11.252.w/code.js`) and the bundled Liqi schema. The current English entry page uses Unity WebGL; the interaction path does not depend on undocumented Unity object names or fixed screen taps.

## Tile positioning

The overlay reads the game canvas immediately after a rendering callback and finds the complete row of light tile faces. It requires the observed tile count to match the protocol hand and waits for two stable frames before outlining tiles. Positions scale with the actual canvas rectangle, including its portrait rotation. The usual sorted hand layout is required.

If a tile skin, animation, or obstructed table prevents finding the complete row, only the nearby action cue appears. The detector does not guess a tile coordinate. Highlights clear when advice is invalidated or expires. Autoplay operates independently of visual detection.

## Validation

- `node --test scripts/mobile/test_assistance.cjs` exercises the production capture/assistance scripts with ordered sockets and a controlled clock. It covers delays, deadlines, cancellation, request collisions, response errors, duplicate decisions, and tile detection at different sizes and concealed hand counts.
- `GameDecisionTest` covers red fives, tsumogiri, kuikae, riichi, exact call combinations, all kan types, kita, wins, and passes. `MahjongSoulProtocolTest` checks decision identity, millisecond deadlines, status-message ordering, manual inputs, and invalidation using serialized game traces.
- `AssistanceTest` drives the real settings and WebView against a local canvas/socket fixture. It checks independent highlighting, touch pass-through, portrait rotation, one automatic move, and reply isolation. It also checks that expanded advice pauses autoplay and that switching autoplay off or touching the game keeps subsequent turns manual. It saves screenshots alongside the existing Android emulator evidence.
- Both Android workflows run these checks in GitHub Actions. No local Android or native compilation is used.

The local fixture does not constitute verification in a signed-in live match. Custom tile skins and the actual public game remain useful device checks when a phone is available.
