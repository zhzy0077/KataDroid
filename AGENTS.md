# Development conventions

- UI, interaction, general features, CPU inference and regression tests may run
  on Android Studio virtual devices or connected physical devices. Do not change
  physical devices' display settings as part of routine testing.
- Every device operation must select an explicit serial (`adb -s <serial>`).
  Inspect `adb devices -l` first. AVD serials are not stable identifiers.
- CPU results do not validate NPU execution. NPU tests skip when the packaged
  runtime is absent; the dedicated audit also requires native delegation evidence.
- Keep raw test reports, device identifiers, local paths, signing keys and
  screenshots of other applications out of Git. Use the ignored `.local/` directory.
- Preserve `third_party/katago/` unchanged. Its hash manifest identifies the exact
  upstream source; Android adapters belong in `app/src/main/cpp/`.
- User-facing text belongs in Android resources, with English and Chinese values.
  Keep SGF data and internal identifiers independent of the UI language.

See [CONTRIBUTING.md](CONTRIBUTING.md) for build and verification commands.
