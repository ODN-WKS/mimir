# Third-party notices

Mímir includes or downloads the parts below. Each keeps its own licence.

## Included in the repository

| Part | Where | Licence |
| --- | --- | --- |
| Cinzel typeface | `assets/fonts/`, `android/app/src/main/assets/` | SIL Open Font License 1.1. Copyright 2020 The Cinzel Project Authors (https://github.com/NDISCOVER/Cinzel). The full text is in `OFL-Cinzel.txt` beside each copy. |
| espeak-ng data (English part, from sherpa-onnx's copy) | `android/app/src/main/assets/voices/espeak-ng-data/` | GPL 3.0 (espeak-ng). Used for words the read-aloud voice speaks by sound. See `voices/SOURCES.txt`. |
| Piper "alba" token list (British English, medium) | `android/app/src/main/assets/voices/alba-tokens.txt` | CC BY 4.0 |

## Added when the apps are built or when a voice is first used

| Part | How it arrives | Licence |
| --- | --- | --- |
| Piper "alba" voice model (Ingrid) | Fetched by the phone app's build (address and checksum in `android/app/build.gradle.kts`) | CC BY 4.0 (the alba voice data) |
| sherpa-onnx 1.13.8 with ONNX Runtime | The phone app's build links its Android library; the PC downloads its Linux library the first time a voice is turned on | Apache 2.0 (sherpa-onnx); MIT (ONNX Runtime) |
| llama.cpp (including ggml and its vision library) | Built into the phone app's model runner (`android/standalone/`, version pinned in `tools/llama-cpp.version`) | MIT |

Models that people download themselves keep the licence their authors gave them.

## Libraries

The PC program reads PDFs with `lopdf` (MIT) and reads Word, Excel, PowerPoint, OpenDocument and
epub files with `roxmltree` (MIT or Apache 2.0) and `flate2` (MIT or Apache 2.0); the zip reading
itself is Mímir's own. These are in `crates/core/Cargo.toml`.

The PC program's Rust crates (listed in `Cargo.lock`) and the phone app's Android
and Kotlin libraries (listed in `android/app/build.gradle.kts`) are each under
their own licences, shown in their own packages.
