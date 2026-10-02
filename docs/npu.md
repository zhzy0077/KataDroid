# Optional NPU runtime

A clean checkout builds and runs on CPU without extra files. The Qualcomm NPU
integration uses LiteRT 2.2.0 and QNN 2.47.0. It does not gate the app by
phone model or SoC. The driver, runtime and model compiler determine whether NPU
execution is available; Auto falls back to CPU on initialization failure.

```bash
python3 tools/prepare_npu.py
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The script downloads the pinned LiteRT Qualcomm JIT compiler/dispatch package and
the matching QAIRT SDK's `libQnnIr.so` / `libQnnSaver.so`. The Maven QNN AAR supplies
other runtime libraries. Downloads have SHA-256 checks. Generated files go under
the ignored `app/src/main/jniLibs/arm64-v8a/`; they are not Git source files.
The Qualcomm preparation script uses the upstream `qualcomm_runtime_v79`
package, validated on Snapdragon 8 Elite. This does not establish compatibility
with every other Qualcomm generation. Experimental MediaTek setup is below;
Samsung plugins are not bundled.
See [third-party terms](../THIRD_PARTY_NOTICES.md) before distributing their binaries.

## Combined-vendor plugin selection

LiteRT 2.2.0 scans the dispatch directory and chooses the first matching library.
The v1.0.0 combined APK could therefore load MediaTek dispatch on a Qualcomm
phone, fail to initialize Neuron, and expose `HostMemory` buffers. The strict
buffer validation correctly rejected that execution.

The source fix prepares a vendor-specific directory with symlinks to that vendor's
packaged compiler/dispatch libraries, plus QNN support libraries on Qualcomm.
Both LiteRT plugin-directory options use this directory. Compiler loading uses the
original extracted library path; links are repaired after an APK update. Packaged
native files stay intact. NPU buffers and native delegation still require validation.
The release-signed 1.0.1 candidate passed the numerical, both-model search,
cancellation/restart and timed-search suites on Snapdragon 8 Elite and Dimensity
9300. These checks do not change the published v1.0.0 APK. Sanitized evidence is in
[vendor-isolation-validation.json](benchmarks/vendor-isolation-validation.json).

## Audit on an explicitly selected device

Install both APKs using `adb -s <serial>` after inspecting `adb devices -l`.
Run only the intended NPU suite:

```bash
python3 tools/test_katago_device.py --serial <npu-device-serial> --suite probe --output-dir .local/npu
python3 tools/test_katago_device.py --serial <npu-device-serial> --suite engine --output-dir .local/npu
python3 tools/test_katago_device.py --serial <npu-device-serial> --suite models --output-dir .local/npu
```

`probe` checks fixed network outputs. `engine` checks official postprocessing,
search, cancellation and reopening. `models` checks both models and fresh-search
benchmarks. These instrumentations do not open an Activity. A skipped test is not
a pass: the helper requires completion markers, QNN graph evidence, complete
DispatchDelegate replacement and no CPU delegate assignment during the NPU section.

Every input/output is checked for QNN buffer types, but boundary buffers alone
are insufficient proof that every operator runs on NPU. The native-log audit is
therefore required. Logs stay local and are filtered to the test process. On a
timeout the helper retains partial evidence and stops the test app only.

## Experimental MediaTek runtime

The application can also load LiteRT's MediaTek compiler/dispatch plugins, using
Neuron hardware buffers (AHWB/DMA-BUF) rather than Qualcomm FastRPC buffers.
The optional native-library manifest entries expose the device's public Neuron
libraries to the app. Plugin availability does not establish model compatibility.

The LiteRT v2.2.0 release archives currently omit the MediaTek plugins. Build
these from the matching upstream tag in an ignored local checkout. On Linux
x86_64 with the project's Android SDK/NDK installed, the preparation script
downloads checksum-pinned LiteRT 2.2.0 and Bazel 7.7.0, applies the patch, builds
the plugins and copies them to the ignored JNI library directory:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
bash tools/prepare_mediatek_npu.sh
```

The tagged release workflow runs both Qualcomm and MediaTek preparation scripts
and verifies that their arm64 plugins are in the signed APK. NPU execution still
depends on vendor drivers and hardware. For a manual source build:

Apply [the runtime selection patch](../tools/patches/litert-2.2.0-mediatek-runtime-selection.patch)
to that LiteRT checkout first. Upstream v2.2.0 keeps iterating after loading a
usable Neuron library, so an older MGVI library can overwrite the public USDK
runtime. The patch stops at the first usable library. It does not modify KataGo.

```bash
bazel build --config=android_arm64 -c opt \
  //litert/vendors/mediatek/compiler:compiler_plugin_so \
  //litert/vendors/mediatek/dispatch:dispatch_api_so
```

The upstream workspace downloads the NeuroPilot SDK. Its terms apply; keep that
SDK outside Git. Copy `libLiteRtCompilerPlugin_MediaTek.so` and
`libLiteRtDispatch_MediaTek.so` from the respective `bazel-bin` directories into
the ignored `app/src/main/jniLibs/arm64-v8a/`, then rebuild both APKs.
Package only the intended vendor's compiler/dispatch pair for a dedicated audit.

After installing both APKs on the explicitly selected NPU test device:

```bash
python3 tools/test_katago_device.py --serial <npu-device-serial> --vendor mediatek --suite probe --output-dir .local/mediatek
python3 tools/test_katago_device.py --serial <npu-device-serial> --vendor mediatek --suite engine --output-dir .local/mediatek
python3 tools/test_katago_device.py --serial <npu-device-serial> --vendor mediatek --suite models --output-dir .local/mediatek
```

The MediaTek audit requires Neuron runtime and native graph-loading evidence,
complete DispatchDelegate replacement, hardware boundary buffers, numerical
completion and no CPU delegate assignment. Completion and successful session
closure are reported through instrumentation as well as logcat, because native
driver logging can lose the final application log records. The probe suite now runs only the NPU model;
CPU numerical tests remain on an AVD.

No new physical-device run is required for UI or CPU changes. The current known
coverage and earlier incomplete b10 NPU lifecycle run are documented in
[performance notes](models-and-benchmarks.md), alongside the completed MediaTek Dimensity 9300
(MT6989) audit. The incomplete b10 run refers to the earlier Qualcomm testing.
