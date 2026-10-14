#!/usr/bin/env python3
"""Package a v17 PyTorch export with references from unmodified upstream inference.

Torch and the upstream KataGo python tree are needed only for this host tool.
The Android package contains a portable TFLite graph, not vendor bytecode.
"""
from __future__ import annotations
import argparse
import gzip
import hashlib
import json
import os
import re
from pathlib import Path
import sys
import tempfile
import zipfile

import numpy as np
from optimize_tflite import parse, interpreter


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(outputs):
    """Match ModelContract.PYTORCH_V17 and official export_model_pytorch.py."""
    policy, value, misc, moremisc, ownership = [np.asarray(x, np.float32) for x in outputs[:5]]
    if policy.shape not in [(1, 6, 362), (1, 8, 362)] or value.shape != (1, 3):
        raise ValueError("Unsupported policy/value contract")
    if misc.shape != (1, 10) or moremisc.shape != (1, 8) or ownership.shape != (1, 1, 19, 19):
        raise ValueError("Unsupported score/ownership contract")
    result = np.concatenate((policy[0, 0], policy[0, 5], value[0], misc[0, :4],
                             moremisc[0, :2], ownership.reshape(-1)))
    if result.size != 1094 or not np.isfinite(result).all():
        raise ValueError("Non-finite reference outputs")
    return result


def symmetry(spatial, index, inverse=False):
    if inverse and index & 4:
        spatial = spatial.swapaxes(1, 2)
    if index & 1:
        spatial = spatial[:, ::-1]
    if index & 2:
        spatial = spatial[:, :, ::-1]
    if not inverse and index & 4:
        spatial = spatial.swapaxes(1, 2)
    return spatial.copy()


def inverse_raw(raw, index):
    result = raw.copy()
    for start in (0, 362, 733):
        result[start:start + 361] = symmetry(raw[start:start + 361].reshape(1, 19, 19, 1),
                                            index, inverse=True).reshape(-1)
    return result


def build(graph: bytes, descriptor: bytes, source_name: str, cases, reference):
    parse(graph)  # Reject custom/compiled, stateful, external-buffer and multi-subgraph models.
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,120}", source_name):
        raise ValueError("Source name must be 1 to 120 ASCII letters, digits, dots, underscores or hyphens")
    lines = descriptor.split(b"\n", 4)
    name, version, spatial_channels, global_channels = [x.decode("ascii") for x in lines[:4]]
    if (version, spatial_channels, global_channels) != ("17", "22", "19"):
        raise ValueError("Package requires a version-17, 22/19-feature descriptor")
    if not 3 <= len(cases) <= 32 or len({c['name'] for c in cases}) != len(cases):
        raise ValueError("Supply 3 to 32 distinct reference cases")
    cpu = interpreter(graph)
    runner = cpu.get_signature_runner("serving_default")
    fixtures = []
    for case in cases:
        spatial = np.asarray(case['inputs']['InputSpatial'], np.float32).reshape(1, 19, 19, 22)
        global_features = np.asarray(case['inputs']['InputGlobal'], np.float32).reshape(1, 19)
        symmetry_references = []
        for index in range(8):
            nchw = symmetry(spatial, index).transpose(0, 3, 1, 2).copy()
            expected = canonical(reference(nchw, global_features))
            outputs = runner(args_0=nchw, args_1=global_features)
            actual = canonical([outputs[f"output_{i}"] for i in range(5)])
            np.testing.assert_allclose(actual, expected, atol=1e-4, rtol=1e-4,
                                       err_msg=f"Upstream reference failed: {case['name']} symmetry {index}")
            symmetry_references.append(inverse_raw(expected, index).tolist())
        entry = {key: case[key] for key in ('name', 'sgf', 'moveNum', 'inputs') if key in case}
        fixtures.append({**entry, "nativeRaw": symmetry_references[0], "symmetryRaw": symmetry_references})
    parameters = [float(x) for x in descriptor.split(b"\n", 11)[4:11]]
    if not all(np.isfinite(x) and x > 0 for x in parameters):
        raise ValueError("Invalid descriptor postprocessing parameters")
    postprocess = dict(zip(('tdScore', 'scoreMean', 'scoreStdev', 'lead', 'varianceTime',
                            'shorttermValueError', 'shorttermScoreError'), parameters))
    fixture_data = json.dumps({"schemaVersion": 2, "postprocess": postprocess, "tfliteSha256": sha(graph),
                              "modelName": name, "signature": "serving_default", "cases": fixtures},
                             sort_keys=True, separators=(",", ":")).encode()
    manifest = json.dumps({"schemaVersion": 1, "contract": "katago-pytorch-v17", "modelVersion": 17,
                           "sourceName": source_name, "descriptorName": name,
                           "tfliteSha256": sha(graph), "descriptorSha256": sha(descriptor),
                           "fixturesSha256": sha(fixture_data)}, sort_keys=True).encode()
    return {"manifest.json": manifest, "fixtures.json": fixture_data,
            "model.tflite": graph, "descriptor.bin": descriptor}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--descriptor", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--upstream", type=Path, required=True, help="Upstream KataGo source root")
    parser.add_argument("--fixtures", type=Path, required=True, help="Engine-generated position/input JSON")
    parser.add_argument("--source-name", required=True)
    parser.add_argument("--license-file", type=Path, required=True,
                        help="Redistribution permission/copyright notice embedded in the manifest")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve() in {p.resolve() for p in (args.graph, args.descriptor, args.checkpoint, args.fixtures, args.license_file)}:
        parser.error("Output must be separate from source inputs")
    # Retain upstream attention and linear kernels. Never import conversion-time monkeypatches.
    os.environ["KATAGO_FLEX_ATTENTION"] = "0"
    sys.path.insert(0, str(args.upstream / "python"))
    import torch
    from katago.train.load_model import load_model
    model, swa, _ = load_model(str(args.checkpoint), use_swa=True, device="cpu", pos_len=19)
    model = (swa if swa is not None else model).eval()
    torch.set_num_threads(4)
    def reference(spatial, global_features):
        with torch.inference_mode():
            outputs = model(torch.from_numpy(spatial), torch.from_numpy(global_features))[0]
            return [x.detach().numpy() for x in outputs[:5]]
    descriptor = args.descriptor.read_bytes()
    if descriptor[:2] == b"\x1f\x8b":
        descriptor = gzip.decompress(descriptor)
    files = build(args.graph.read_bytes(), descriptor, args.source_name,
                  json.loads(args.fixtures.read_text())['cases'], reference)
    notice = args.license_file.read_text(encoding="utf-8")
    if not notice.strip():
        parser.error("License notice must be nonempty")
    manifest = json.loads(files["manifest.json"])
    manifest["licenseNotice"] = notice
    files["manifest.json"] = json.dumps(manifest, sort_keys=True).encode()
    if len(files["manifest.json"]) > 65536:
        parser.error("Manifest with license notice exceeds importer limit")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=args.output.parent, delete=False) as temporary:
        path = Path(temporary.name)
    try:
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
            for name, data in files.items():
                archive.writestr(name, data)
        os.replace(path, args.output)
    finally:
        path.unlink(missing_ok=True)
    print(f"Verified upstream references and packaged {len(files)} entries")


if __name__ == "__main__":
    main()
