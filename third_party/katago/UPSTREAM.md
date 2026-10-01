# KataGo source subset

Unmodified files from [KataGo v1.18.2](https://github.com/lightvector/KataGo/tree/fd0723fdbc0e9d82cf269c9630af8c27c57c07c4),
commit `fd0723fdbc0e9d82cf269c9630af8c27c57c07c4`.

`upstream-files.json` records every copied path and SHA-256. The Android build
uses the official board rules, history, input features, neural output
postprocessing and search. Its LiteRT backend and JNI adapter live separately
in `app/src/main/cpp/katago_jni.cpp`. No upstream source was patched.

See `LICENSE`, `CONTRIBUTORS` and the licenses within `cpp/external/`.
