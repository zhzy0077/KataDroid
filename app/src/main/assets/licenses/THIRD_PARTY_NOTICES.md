# Third-party notices

KataDroid's original code is licensed under [MIT](LICENSE). Third-party code,
weights and runtimes retain their respective licenses. MIT does not relicense
those dependencies. License copies for bundled source are also packaged under
`app/src/main/assets/licenses/` in the APK.

## KataGo source

The source subset in `third_party/katago/` is from KataGo v1.18.2, commit
`fd0723fdbc0e9d82cf269c9630af8c27c57c07c4`, without modifications. See its
[LICENSE](third_party/katago/LICENSE), [CONTRIBUTORS](third_party/katago/CONTRIBUTORS)
and [file hash manifest](third_party/katago/upstream-files.json).

Included external components have separate notices:

| Component | License / preserved notice |
| --- | --- |
| ghc filesystem 1.5.8 | [MIT](third_party/katago/cpp/external/filesystem-1.5.8/LICENSE) |
| TCLAP 1.2.5 | [MIT](third_party/katago/cpp/external/tclap-1.2.5/COPYING) |
| nlohmann/json | MIT and embedded acknowledgments in [json.hpp](third_party/katago/cpp/external/nlohmann_json/json.hpp) |
| SHA-2 implementation | BSD 3-Clause notice embedded in [sha2.cpp](third_party/katago/cpp/core/sha2.cpp) |

## Neural network weights

The bundled original descriptors and converted TFLite files derive from these
official, pre-distributed-training **g170** networks:

- `g170-b6c96-s175395328-d26788732`
- `g170e-b10c128-s1141046784-d204142634`

The official [KataGo neural network license page](https://katagotraining.org/network_license/),
section **Exceptions**, states that the old g170 networks are under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) and effectively
public domain. This exception is specific to g170; do not assume that every newer
KataGo or community model has the same license. Source URLs and SHA-256 hashes
are retained in the bundled fixture manifests and the conversion script.

## Build dependencies and optional runtimes

- AndroidX, Jetpack Compose and Google LiteRT use Apache License 2.0. Their own
  dependency distributions may include additional third-party notices.
- The Gradle wrapper is from Gradle, under Apache License 2.0.
- The NDK's LLVM C++ runtime uses Apache License 2.0 with LLVM exceptions.
- `com.qualcomm.qti:qnn-runtime:2.47.0` declares the **Qualcomm AI Hub Model License**
  in its Maven POM: [license PDF](https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf).
- Optional Qualcomm compiler/dispatch and QAIRT SDK libraries downloaded by
  `tools/prepare_npu.py` remain subject to the licenses delivered with those
  distributions. The script pins their versions and checksums. Downloaded
  vendor libraries are excluded from this Git repository.
- Optional MediaTek LiteRT compiler/dispatch plugins are built from LiteRT's
  Apache-2.0 source. The upstream build downloads NeuroPilot SDK headers under
  the SDK's own license agreement. The SDK and generated plugins are excluded
  from Git; device-provided Neuron runtime libraries are not copied or bundled.

Consult the dependency distributions and their terms when redistributing a
build containing vendor runtime binaries. This repository's MIT license applies
to KataDroid's own integration code, not to the vendor runtimes.
