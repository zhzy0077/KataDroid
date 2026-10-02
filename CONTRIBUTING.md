# Contributing

Use the toolchain in [README.md](README.md). A clean checkout builds a CPU-capable
APK from the included models and source; local conversion environments and NPU
plugins are optional. The application ID is `io.github.zhzy0077.katadroid`. Android treats it as a
separate app from earlier `com.example.katadroid` builds. Export existing games
as SGF in the old app and import them into the new app.

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
  io.github.zhzy0077.katadroid.test/androidx.test.runner.AndroidJUnitRunner
```

Tests cover official numerical references, rules/legality, SGF round trips,
search cancellation, model/backend cache separation, automatic play, English and
Chinese UI flows, touch offsets, landscape layout and chart navigation. Locale
rules change only this app's language and restore it after each test. NPU tests
skip on CPU-only environments; a skip is not a successful NPU validation.

For a single class, insert `-e class io.github.zhzy0077.katadroid.EnglishUiTest` before
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

## Tagged APK releases

[Release APK](.github/workflows/release.yml) runs when a tag such as `v1.0.0` or
`v1.0.0-rc.1` is pushed. It checks integrity, runs unit tests and release lint,
builds once, then aligns, signs and verifies standalone arm64-v8a and x86_64
APKs plus a universal APK. It creates a GitHub Release with all three APKs and
`SHA256SUMS`. Shared code, resources, models and retained native libraries are
identical across variants. ARMv7 is not supported. The release stays a draft
until asset uploads succeed.
Tags with a prerelease suffix create prereleases.
For an existing universal release, run [Add ABI release APKs](.github/workflows/package-release.yml)
with its tag. This downloads and checks the original APK, derives and signs the
ABI variants with the same certificate, and adds them plus updated checksums.
The original universal APK and published tag stay unchanged.
All APKs contain both bundled models and CPU inference. Arm64 and universal
APKs also contain Qualcomm NPU plugins and experimental MediaTek NPU plugins
built with the runtime selection patch. The workflow checks ABI contents and
both vendors' arm64 plugins before publishing. Plugin packaging does not validate execution on every SoC; physical NPU audits and AVD
UI tests remain separate checks.

MediaTek's hermetic host compiler dependencies need substantial disk space. The
release job removes unused preinstalled toolchains and emulator images on its
disposable runner, checks for 25 GiB free space, and deletes its dedicated Bazel
output directory after copying the plugins, before the Gradle build.

Before the first release, create a release key outside Git (or use your existing
release key). Keep a secure backup: subsequent APK updates require the same key.

```bash
mkdir -p .local/signing
keytool -genkeypair -keystore .local/signing/release.jks -alias katadroid \
  -keyalg RSA -keysize 4096 -validity 10000
```

Configure these repository Actions secrets under **Settings → Secrets and
variables → Actions**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing alias, e.g. `katadroid` |
| `ANDROID_KEY_PASSWORD` | Private-key password (often the same as the keystore password) |

For example, upload the keystore secret without printing it:

```bash
base64 -w 0 .local/signing/release.jks | gh secret set ANDROID_KEYSTORE_BASE64
```

Set the remaining secrets through GitHub's UI or interactive `gh secret set`.
The workflow fails before building if any signing secret is missing. Release
publishing uses the job's `GITHUB_TOKEN` with `contents: write`; no personal token
is needed. Repository or organization policy must allow this permission.

After committing and pushing the workflow and release changes:

```bash
git tag -a v1.0.0 -m 'KataDroid 1.0.0'
git push origin v1.0.0
```

The APK's version name comes from the tag without `v`; its version code is
`1000 + github.run_number`. Keep this workflow's identity and counter for future
releases, and publish tags in version order. Re-running a failed job retains its
version code. If an upload fails after creating a draft, delete that incomplete
draft before retrying. A release that already exists is not overwritten; use a
new tag for changes. These release-signed APKs cannot upgrade a debug-signed installation
in place; export saved games before changing signing identities.
