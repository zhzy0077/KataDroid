"""Verify bundled upstream bytes, model pairs and localized resource coverage."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    upstream = ROOT / "third_party/katago"
    manifest = json.loads((upstream / "upstream-files.json").read_text())
    for name, expected in manifest["sha256"].items():
        if sha256(upstream / name) != expected:
            raise SystemExit(f"Upstream file mismatch: {name}")
    assets = ROOT / "app/src/main/assets/katago"
    catalog = (ROOT / "app/src/main/java/io/github/zhzy0077/katadroid/engine/EngineConfig.kt").read_text()
    for model, fixture in (("b6c96", "fixtures.json"), ("b10c128", "b10c128-fixtures.json")):
        data = json.loads((assets / fixture).read_text())
        for suffix, key in (("tflite", "tfliteSha256"), ("bin", "descriptorSha256")):
            expected = data[key]
            if sha256(assets / f"{model}.{suffix}") != expected or expected not in catalog:
                raise SystemExit(f"Model/fixture/catalog mismatch: {model}.{suffix}")
        if len(data["cases"]) != 3:
            raise SystemExit(f"Reference positions missing: {model}")
    res = ROOT / "app/src/main/res"
    strings = [{s.attrib["name"]: s.text or "" for s in ET.parse(res / locale / "strings.xml").getroot()}
               for locale in ("values", "values-zh")]
    if strings[0].keys() != strings[1].keys():
        raise SystemExit(f"Translation coverage mismatch: {strings[0].keys() ^ strings[1].keys()}")
    for name in strings[0]:
        placeholders = [sorted(re.findall(r"%(\d+)\$[\d.]*([a-zA-Z])", s[name])) for s in strings]
        if placeholders[0] != placeholders[1]:
            raise SystemExit(f"Translation format mismatch: {name}")
    for source, asset in (("LICENSE", "KataDroid-MIT.txt"), ("third_party/katago/LICENSE", "KataGo-LICENSE.txt"),
                          ("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")):
        if (ROOT / source).read_bytes() != (ROOT / "app/src/main/assets/licenses" / asset).read_bytes():
            raise SystemExit(f"Packaged license mismatch: {asset}")
    print(f"Verified {len(manifest['sha256'])} upstream files, two model pairs and {len(strings[0])} bilingual strings.")


if __name__ == "__main__":
    main()
