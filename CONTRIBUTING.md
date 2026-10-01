# Contributing

Use the toolchain in [README.md](README.md). A clean checkout builds a CPU-capable
APK from the included models and source; local conversion environments and NPU
plugins are optional. The production application ID stays `com.example.katadroid`
to preserve installed games and settings across upgrades.

## Checks

```bash
python3 tools/check_integrity.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

CI runs these checks without optional NPU plugins. Lint version suggestions do
not require an unrelated dependency upgrade. There is no signing material in Git.

## AVD integration tests

Use an Android Studio x86_64 virtual device with Android 13 or newer. The current
CPU/UI baseline uses an API 37 image with 16 KiB pages. Choose the actual serial
from `adb devices -l`; never run an unqualified multi-device install or test.

```bash
adb devices -l
adb -s <avd-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <avd-serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <avd-serial> shell am instrument -w -r \
  com.example.katadroid.test/androidx.test.runner.AndroidJUnitRunner
```

Tests cover official numerical references, rules/legality, SGF round trips,
search cancellation, model/backend cache separation, automatic play, English and
Chinese UI flows, touch offsets, landscape layout and chart navigation. Locale
rules change only this app's language and restore it after each test. NPU tests
skip on CPU-only environments; a skip is not a successful NPU validation.

For a single class, insert `-e class com.example.katadroid.EnglishUiTest` before
the instrumentation runner component. Raw output belongs in `.local/`, not Git.

## Physical NPU tests

Schedule these explicitly. Use [the NPU audit](docs/npu.md); do not run routine
UI tests, take screenshots or change a physical device's display settings as part
of normal development. The audit runs instrumentation without launching an Activity.

## Changes and release preparation

Keep English (`values/strings.xml`) and Chinese (`values-zh/strings.xml`) resources
in sync. Keep text out of persisted game identifiers; imported SGF names and
comments are user content and must not be translated. Preserve the original icon
sources in `design/icon/` and the byte-identical KataGo subset in `third_party/`.

Before a release, build from a clean checkout, run the checks and AVD tests,
update the [user guide](docs/android-ui.md), model/architecture documentation and
sanitized [performance samples](docs/benchmarks/), and refresh both language
screenshots. Screenshots must show the app only; exclude notification contents,
file-picker paths, accounts, device serials and other applications. State the
actual model, backend, visit budget and measurement method with performance data.
Never imply a skipped or incomplete hardware audit passed.

Sign release APKs outside Git. Preserve `LICENSE` and third-party notices when
distributing source or binaries; optional vendor runtimes have their own terms.
