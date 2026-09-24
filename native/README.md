The mobile core keeps game state, observation encoding, legal masks, action decoding, and riichi forks in Rust. Kotlin runs the exported Mortal graph locally with ONNX Runtime's CPU provider. JNI accepts IDs from a synchronized registry; no Java-provided pointer is dereferenced. Native exceptions and panics become Java errors, and the coordinator must resynchronize before showing advice again.

The four-player source is pinned to shinkuan/Mortal_v4 `e11e17452cc49f2a3cd8e26286130bb4448d3285`. The three-player rules candidate is pinned separately; its legacy encoder corrections are documented in [vendor/mortal3p/MODIFICATIONS.md](vendor/mortal3p/MODIFICATIONS.md) and [fixtures/SANMA_LAYOUT.md](fixtures/SANMA_LAYOUT.md). Source licenses and original file hashes are included in both vendor directories.

Run builds and exports only through GitHub Actions:

```sh
cargo test --manifest-path native/Cargo.toml --workspace
cargo build --manifest-path native/Cargo.toml --release -p akagi-mobile-core --bin mortal-fixtures
python scripts/mobile/export_models.py --native-cli native/target/release/mortal-fixtures --validate-sanma
cd native
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build --release -p akagi-mobile-core --lib
```

The exporter uses the shipped CPython 3.12 desktop libraries to check full observations, masks and every legal decoded action, and the repository's exact `network.py` to compare PyTorch and ONNX scores. It verifies checkpoint digests before loading weights, fixes inference to FP32 and deterministic argmax, and retains the legal-action-only dueling advantage mean. Model manifests approve each rules variant only after its native comparison succeeds. Android's on-device model check runs saved real observations against the same expected scores and decisions.

`mortal-fixtures TRACE.jsonl PLAYER_ID PLAYERS` emits one JSON record per actionable event, including normal/kan observations, masks and decoded legal actions. The CI diagnostics preserve these alongside compressed desktop references. JNI is platform glue; the host driver exercises the same `Session` implementation. Hypothetical riichi uses a cloned native session and shares the existing ONNX session, leaving live state unchanged.
