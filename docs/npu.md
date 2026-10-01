# Optional NPU runtime

A clean checkout builds and runs on CPU without extra files. The optional NPU
integration uses LiteRT 2.2.0 and Qualcomm QNN 2.47.0. It does not gate the app by
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
The currently prepared LiteRT package is the upstream `qualcomm_runtime_v79`
package, validated on Snapdragon 8 Elite. This does not establish compatibility
with every other Qualcomm generation. Other vendor plugins are not bundled.
See [third-party terms](../THIRD_PARTY_NOTICES.md) before distributing their binaries.

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
DispatchDelegate replacement and no CPU delegate during the NPU section.

Every input/output is checked for QNN buffer types, but boundary buffers alone
are insufficient proof that every operator runs on NPU. The native-log audit is
therefore required. Logs stay local and are filtered to the test process. On a
timeout the helper retains partial evidence and stops the test app only.

No new physical-device run is required for UI or CPU changes. The current known
coverage and incomplete b10 NPU lifecycle run are documented in
[performance notes](models-and-benchmarks.md).
