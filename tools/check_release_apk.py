"""Require both model pairs and both vendors' arm64 NPU plugins in a release APK."""
import argparse
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--abis", nargs="+", choices=("arm64-v8a", "x86_64"), default=["arm64-v8a", "x86_64"])
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        required_libs = (
            "libLiteRtCompilerPlugin_Qualcomm.so",
            "libLiteRtDispatch_Qualcomm.so",
            "libQnnIr.so",
            "libQnnSaver.so",
            "libLiteRtCompilerPlugin_MediaTek.so",
            "libLiteRtDispatch_MediaTek.so",
        )
        names = set(apk.namelist())
        actual_abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        if actual_abis != set(args.abis):
            raise RuntimeError(f"Expected ABIs {args.abis}, found {sorted(actual_abis)}")
        for library in required_libs if "arm64-v8a" in args.abis else ():
            path = f"lib/arm64-v8a/{library}"
            if path not in names:
                raise RuntimeError(f"Release APK is missing {path}")
            header = apk.read(path)[:20]
            if (header[:5] != b"\x7fELF\x02" or header[5] != 1
                    or int.from_bytes(header[18:20], "little") != 183):
                raise RuntimeError(f"Expected arm64 ELF library: {path}")
        for abi in args.abis:
            if f"lib/{abi}/libkatadroid.so" not in names:
                raise RuntimeError(f"Release APK is missing KataGo for {abi}")
        for model in ("b6c96", "b10c128"):
            for extension in ("bin", "tflite"):
                path = f"assets/katago/{model}.{extension}"
                if path not in names:
                    raise RuntimeError(f"Release APK is missing model asset {path}")
    print(f"Release APK verified: {', '.join(args.abis)}, both networks, and NPU plugins when arm64 is included.")


if __name__ == "__main__":
    main()
