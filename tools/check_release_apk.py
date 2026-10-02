"""Require both model pairs and both vendors' arm64 NPU plugins in a release APK."""
import sys
import zipfile


def main():
    with zipfile.ZipFile(sys.argv[1]) as apk:
        required_libs = (
            "libLiteRtCompilerPlugin_Qualcomm.so",
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnIr.so",
            "libQnnSaver.so",
            "libLiteRtCompilerPlugin_MediaTek.so",
            "libLiteRtDispatch_MediaTek.so",
        )
        names = set(apk.namelist())
        for library in required_libs:
            path = f"lib/arm64-v8a/{library}"
            if path not in names:
                raise RuntimeError(f"Release APK is missing {path}")
            header = apk.read(path)[:20]
            if (header[:5] != b"\x7fELF\x02" or header[5] != 1
                    or int.from_bytes(header[18:20], "little") != 183):
                raise RuntimeError(f"Expected arm64 ELF library: {path}")
        for abi in ("arm64-v8a", "x86_64"):
            if f"lib/{abi}/libkatadroid.so" not in names:
                raise RuntimeError(f"Release APK is missing KataGo for {abi}")
        for model in ("b6c96", "b10c128"):
            for extension in ("bin", "tflite"):
                path = f"assets/katago/{model}.{extension}"
                if path not in names:
                    raise RuntimeError(f"Release APK is missing model asset {path}")
    print("Release APK contains both ABIs, both networks and both vendors' NPU plugins.")


if __name__ == "__main__":
    main()
