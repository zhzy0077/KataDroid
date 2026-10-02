import tempfile
import unittest
from pathlib import Path
import zipfile
from tools.split_release_apk import split_apk


class SplitApkTest(unittest.TestCase):
    def test_both_variants_preserve_shared_and_matching_native_entries(self):
        entries = {
            "AndroidManifest.xml": b"manifest",
            "classes.dex": b"bytecode",
            "assets/katago/b6c96.tflite": b"model",
            "lib/arm64-v8a/libkatadroid.so": b"arm64",
            "lib/x86_64/libkatadroid.so": b"x86",
            "META-INF/CERT.RSA": b"obsolete signature",
            "META-INF/library.kotlin_module": b"metadata",
        }
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "universal.apk"
            with zipfile.ZipFile(source, "w") as apk:
                for name, data in entries.items():
                    apk.writestr(name, data)
            before = source.read_bytes()
            split_apk(source, Path(directory) / "variants")
            self.assertEqual(before, source.read_bytes())
            for abi in ("arm64-v8a", "x86_64"):
                expected = {name: data for name, data in entries.items()
                            if name != "META-INF/CERT.RSA" and
                            (not name.startswith("lib/") or name.startswith(f"lib/{abi}/"))}
                with zipfile.ZipFile(Path(directory) / "variants" / f"{abi}-unsigned.apk") as apk:
                    self.assertEqual(expected, {name: apk.read(name) for name in apk.namelist()})

    def test_unsupported_abi_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "armv7.apk"
            with zipfile.ZipFile(source, "w") as apk:
                apk.writestr("lib/armeabi-v7a/libkatadroid.so", b"armv7")
            with self.assertRaises(ValueError):
                split_apk(source, Path(directory) / "variants")
