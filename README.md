# KataDroid

[简体中文](README.zh-CN.md) · [User guide](docs/android-ui.md) · [Architecture](docs/katago-integration.md) · [Performance](docs/models-and-benchmarks.md)

An Android Go board with offline KataGo analysis, variations and SGF editing.
The app runs the official KataGo search and rules engine locally, with LiteRT
neural inference. No account or analysis server is required.

<p align="center">
  <img src="docs/images/analysis-en.png" width="31%" alt="Go board with KataGo candidates and clickable win-rate chart" />
  <img src="docs/images/settings-en.png" width="31%" alt="Settings with a single persistent Apply all changes button" />
  <img src="docs/images/engine-en.png" width="31%" alt="Model selection and measured search performance" />
</p>

## Features

- Play either color manually, let KataGo play Black or White, or enable both for self-play.
- Toggle analysis from the top-right switch; set a search budget from 1 to 50,000 visits.
- Candidate colors reflect **win-rate loss relative to the best evaluated move**,
  from the player-to-move perspective: near-best green, then yellow, then red.
  Three near-equal opening moves can all be green at about 50% win rate.
- Hold a candidate to preview its variation. Tap the win-rate chart or a tree
  node to navigate the game; earlier branches and saved analyses remain available.
- Import/export 19×19 SGF with variations, comments, player metadata and root setup stones.
- Chinese, Japanese and Korean rules; komi from −400 to 400, including decimals.
- Physical-pixel touch offset, drag-to-preview placement and a larger landscape board.
- Switch between bundled b6c96 and b10c128 networks, select Auto / CPU / NPU,
  and measure actual search **visits/s** on three fixed positions.
- English and Simplified Chinese interfaces, following Android's app/system language.

## Quick start

Build and install the app using the instructions below. Open the three-dot menu
to **Clear board**, **Import SGF**, **Export SGF** or open **Settings**. Tap an
intersection to play. The KataGo switch enables analysis; both colors are manual
by default. In Settings, edit the desired options and tap **Apply all changes**
once at the bottom. Open **Engines & models** to choose a model and benchmark it.

Android 13+ supports choosing English or Chinese in system Settings → Apps →
KataDroid → Language. The app initially opens an illustrative game; its analysis
and candidate values come from real search.

## Compatibility

- Android **13 / API 33 or newer**; `arm64-v8a` and `x86_64` builds.
- CPU inference is available independently of phone brand or SoC. There is no
  device-model or SoC allowlist.
- Auto tries an installed NPU runtime and falls back to CPU if initialization
  fails. Explicit NPU mode reports errors rather than silently changing backend.
- The optional NPU integration currently packages Qualcomm LiteRT/QNN runtimes.
  Support depends on the runtime, drivers, model and hardware. Other vendors can
  still run on CPU; this build does not include MediaTek or Samsung NPU plugins.
- UI and CPU regression testing uses Android Studio AVDs. Physical NPU validation
  so far has covered **Snapdragon 8 Elite only**; this is a test coverage statement,
  not an app restriction. See the [performance notes](docs/models-and-benchmarks.md).

## Build

Use Android Studio with API 37 support, or the Gradle wrapper. Toolchain:
Gradle 9.6.0, JDK 25 (Gradle daemon), Android SDK 37, Build Tools 36.0.0,
NDK 28.2.13676358 and CMake 3.22.1. Java source compatibility is 11.
Set the SDK path through Android Studio or your own ignored `local.properties`.

```bash
./gradlew :app:assembleDebug
adb devices -l
adb -s <target-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

The included assets make a CPU build possible without Python, model downloads or
conversion tools. For the optional NPU plugins, see [NPU setup](docs/npu.md).
Release APKs require your own signing configuration; never commit signing keys.

```bash
python3 tools/check_integrity.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

AVD instrumentation commands are in [CONTRIBUTING.md](CONTRIBUTING.md).

## How it works

Compose UI → game/SGF state → serialized analysis controller → JNI → official
KataGo rules, features and search → LiteRT CPU or optional NPU inference.
Models and actual backends have separate result caches. Search and benchmark
jobs share a single worker, so cancelled jobs cannot publish stale moves or free
buffers while native inference still uses them.

See [architecture](docs/katago-integration.md) and [model conversion](docs/model-conversion.md).

## License

KataDroid code is [MIT](LICENSE). KataGo source, external libraries, CC0 g170
weights and vendor runtime terms are documented in [Third-party notices](THIRD_PARTY_NOTICES.md).
