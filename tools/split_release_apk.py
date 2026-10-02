"""Create standalone ABI APKs from one build; outputs must be aligned and signed."""
import argparse
import copy
from pathlib import Path
import re
import zipfile

ABIS = ("arm64-v8a", "x86_64")
SIGNATURE = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$", re.IGNORECASE)


def split_apk(source: Path, destination: Path):
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source) as original:
        present = {name.split("/")[1] for name in original.namelist()
                   if name.startswith("lib/") and name.endswith(".so")}
        if present != set(ABIS):
            raise ValueError(f"Expected a universal arm64/x86_64 APK, found {sorted(present)}")
        for abi in ABIS:
            output = destination / f"{abi}-unsigned.apk"
            if output.resolve() == source.resolve():
                raise ValueError("Output cannot replace the source APK")
            with zipfile.ZipFile(output, "w") as variant:
                for entry in original.infolist():
                    # Strip prior v1 signatures; v2/v3 blocks are outside ZIP entries.
                    if SIGNATURE.fullmatch(entry.filename):
                        continue
                    parts = entry.filename.split("/")
                    if parts[0] == "lib" and len(parts) > 2 and parts[1] != abi:
                        continue
                    variant.writestr(copy.copy(entry), original.read(entry))
            print(f"Created {abi} APK; shared code/resources and retained native binaries are unchanged.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    split_apk(args.source, args.destination)


if __name__ == "__main__":
    main()
