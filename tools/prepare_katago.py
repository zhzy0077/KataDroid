"""Export a supported KataGo network and engine-generated 19x19 fixtures.

Run with .venv-model/bin/python; requires KataGo v1.18.2 with the ONNX backend.
Generated inputs come from NNInputs::fillRowV7, never guessed feature planes.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import numpy as np
import flatbuffers
import onnx
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter
from ai_edge_litert import schema_py_generated as schema

ROOT = Path(__file__).resolve().parents[1]
MODELS = {
    "b6c96": ("g170-b6c96-s175395328-d26788732",
              "f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1", "katago-b6"),
    "b10c128": ("g170e-b10c128-s1141046784-d204142634",
                "1a8e05a4ea3fca20dab79410cbb566c760767fcdd2fa0b701cfe259a84cc8b04", "katago-b10"),
}
FIXTURES = {
    "empty": ("空棋盘", "(;GM[1]FF[4]SZ[19]KM[7.5]RU[Chinese])", 0),
    "opening": ("布局八手", "(;GM[1]FF[4]SZ[19]KM[7.5]RU[Chinese];B[pd];W[dd];B[qp];W[dp];B[fq];W[cn];B[qf];W[nc])", 8),
    "capture": ("提子后白走", "(;GM[1]FF[4]SZ[19]KM[7.5]RU[Chinese];B[dd];W[cd];B[cc];W[qq];B[bd];W[pp];B[ce])", 7),
}


def run(args, log):
    print("Running:", " ".join(map(str, args)), flush=True)
    with log.open("w") as out:
        subprocess.run(list(map(str, args)), stdout=out, stderr=subprocess.STDOUT, check=True,
                       env={**os.environ, "TF_CPP_MIN_LOG_LEVEL": "2",
                            "PATH": str(Path(sys.executable).parent) + os.pathsep + os.environ["PATH"]})


def add_signature(path, graph):
    """onnx2tf 1.28.8 emits its concrete-function TFLite without SignatureDefs.

    Bind Identity outputs in the converter's ONNX graph-output order, then verify
    every named output numerically against ONNX Runtime on all engine fixtures.
    """
    model = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(path.read_bytes(), 0))
    subgraph = model.subgraphs[0]
    signature = schema.SignatureDefT()
    signature.signatureKey = "serving_default"
    signature.subgraphIndex = 0
    signature.inputs, signature.outputs = [], []
    for index in subgraph.inputs:
        entry = schema.TensorMapT()
        entry.name = subgraph.tensors[index].name.decode()
        entry.tensorIndex = int(index)
        signature.inputs.append(entry)
    assert len(subgraph.outputs) == len(graph.graph.output)
    for i, (index, output) in enumerate(zip(subgraph.outputs, graph.graph.output)):
        name = subgraph.tensors[index].name.decode()
        assert name == ("Identity" if i == 0 else f"Identity_{i}"), name
        entry = schema.TensorMapT()
        entry.name, entry.tensorIndex = output.name, int(index)
        signature.outputs.append(entry)
    model.signatureDefs = [signature]
    builder = flatbuffers.Builder(0)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    path.write_bytes(builder.Output())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--katago", required=True, type=Path)
    parser.add_argument("--model", choices=MODELS, default="b6c96")
    parser.add_argument("--skip-conversion", action="store_true")
    args = parser.parse_args()
    katago = args.katago.resolve()
    version = subprocess.check_output([katago, "version"], text=True)
    if "1.18.2" not in version:
        raise RuntimeError(f"Expected KataGo 1.18.2, got {version}")
    model_name, source_hash, stem = MODELS[args.model]
    url = f"https://katagoarchive.org/g170/neuralnets/{model_name}.bin.gz"
    work = ROOT / "models" if args.model == "b6c96" else ROOT / "models" / args.model
    work.mkdir(parents=True, exist_ok=True)
    source = ROOT / "models" / f"{model_name}.bin.gz"
    if not source.exists():
        temporary = source.with_suffix(".download")
        subprocess.run(["curl", "-fL", "--max-time", "120", "-o", str(temporary), url], check=True)
        assert hashlib.sha256(temporary.read_bytes()).hexdigest() == source_hash, "Download hash mismatch"
        temporary.replace(source)
    assert hashlib.sha256(source.read_bytes()).hexdigest() == source_hash, "Source model hash mismatch"
    exported = work / f"{stem}.onnx"
    run([katago, "dumponnx", "-model", source, "-out", exported,
         "-require-exact-nnlen", "-skip-scale8"], work / "export.log")
    graph = onnx.load(exported)
    metadata = {p.key: p.value for p in graph.metadata_props}
    assert metadata["katago.modelVersion"] == "8"
    assert float(metadata.get("katago.postProcess.outputScaleMultiplier", "1")) == 1
    # An exact-19x19 graph never reads InputMask. Remove only the unused graph input.
    assert not any("InputMask" in node.input for node in graph.graph.node)
    for item in list(graph.graph.input):
        if item.name == "InputMask":
            graph.graph.input.remove(item)
        else:
            item.type.tensor_type.shape.dim[0].dim_value = 1
    onnx.checker.check_model(graph)
    fixed = work / f"{stem}-fixed.onnx"
    onnx.save(graph, fixed)
    converted = work / "converted"
    if not args.skip_conversion:
        run([Path(sys.executable).with_name("onnx2tf"), "-i", fixed, "-o", converted,
             "-b", "1", "-osd", "-v", "warn"], work / "conversion.log")
    tflite = converted / f"{stem}-fixed_float32.tflite"
    add_signature(tflite, graph)
    interpreter = Interpreter(model_path=str(tflite), num_threads=1)
    interpreter.allocate_tensors()
    signatures = interpreter.get_signature_list()
    assert len(signatures) == 1, signatures
    signature = next(iter(signatures))
    runner = interpreter.get_signature_runner(signature)
    input_details, output_details = runner.get_input_details(), runner.get_output_details()
    assert set(input_details) == {"InputSpatial", "InputGlobal"}, input_details
    assert input_details["InputSpatial"]["shape"].tolist() == [1, 19, 19, 22]
    assert input_details["InputGlobal"]["shape"].tolist() == [1, 1, 1, 19]
    assert {k: int(np.prod(v["shape"])) for k, v in output_details.items()} == {
        "OutputPolicy": 361, "OutputPolicyPass": 1, "OutputValue": 3,
        "OutputScoreValue": 4, "OutputOwnership": 361,
    }, "This Android backend only supports the version-8 five-head export"
    # Reference the untouched official export, before onnx2tf/onnxsim optimization.
    session = ort.InferenceSession(str(exported), providers=["CPUExecutionProvider"])
    out_names = [v.name for v in session.get_outputs()]
    assert set(output_details) == set(out_names), output_details
    cfg = work / "reference.cfg"
    cfg.write_text("""onnxProvider = cpu
onnxSkipScale8 = true
maxVisits = 1
numSearchThreads = 1
numNNServerThreadsPerModel = 1
nnMaxBatchSize = 1
nnCacheSizePowerOfTwo = 10
nnMutexPoolSizePowerOfTwo = 8
nnRandomize = false
nnSymmetry = 0
rootNumSymmetriesToSample = 1
logToStderr = false
""")
    cases = []
    for name, (label, sgf, move_num) in FIXTURES.items():
        sgf_path, npz_path = work / f"{name}.sgf", work / f"{name}.npz"
        sgf_path.write_text(sgf)
        run([katago, "evalsgf", "-model", source, "-config", cfg, sgf_path,
             "-move-num", move_num, "-visits", "1", "-dump-npz-input-to", npz_path], work / f"{name}-engine.log")
        features = np.load(npz_path)
        feed = {"InputSpatial": features["binaryInputNCHW"],
                "InputGlobal": features["globalInputNC"].reshape(1, 19, 1, 1)}
        assert feed["InputSpatial"].shape == (1, 22, 19, 19)
        assert np.all(feed["InputSpatial"][:, 0] == 1)
        reference = dict(zip(out_names, session.run(out_names, {
            **feed, "InputMask": feed["InputSpatial"][:, :1].copy(),
        })))
        lite_feed = {k: v.transpose(0, 2, 3, 1).copy() for k, v in feed.items()}
        for key, arr in lite_feed.items():
            assert tuple(input_details[key]["shape"]) == arr.shape, (key, input_details[key], arr.shape)
        actual = runner(**lite_feed)
        reference_nhwc = {k: v.transpose(0, 2, 3, 1).copy() for k, v in reference.items()}
        errors = {}
        for key, expected in reference_nhwc.items():
            np.testing.assert_allclose(actual[key], expected, atol=0.001, rtol=0.0001,
                                       err_msg=f"{name}/{key}")
            errors[key] = float(np.max(np.abs(actual[key] - expected)))
        print(name, "LiteRT CPU max errors", errors, flush=True)
        cases.append({"name": name, "label": label, "sgf": sgf, "moveNum": move_num,
                      "inputs": {k: v.flatten().tolist() for k, v in lite_feed.items()},
                      "outputs": {k: v.flatten().tolist() for k, v in reference_nhwc.items()},
                      "hostCpuMaxErrors": errors})
    descriptor = gzip.decompress(source.read_bytes())
    manifest = {"schemaVersion": 1, "modelName": model_name, "sourceUrl": url, "sourceSha256": source_hash,
                "descriptorName": descriptor.split(b"\n", 1)[0].decode(),
                "descriptorSha256": hashlib.sha256(descriptor).hexdigest(),
                "engineVersion": version.strip(), "onnxMetadata": metadata,
                "tfliteSha256": hashlib.sha256(tflite.read_bytes()).hexdigest(),
                "signature": signature,
                "inputShapes": {k: v["shape"].tolist() for k, v in input_details.items()},
                "outputShapes": {k: v["shape"].tolist() for k, v in output_details.items()}, "cases": cases}
    assets = ROOT / "app/src/main/assets/katago"
    assets.mkdir(parents=True, exist_ok=True)
    # Publish assets only after all reference checks have passed.
    shutil.copyfile(tflite, assets / f"{args.model}.tflite")
    # The Android search backend uses the official descriptor parser for model
    # version/postprocessing metadata; neural inference still runs the TFLite.
    # Android's asset merger automatically strips .gz; package the explicit
    # uncompressed descriptor and let APK compression handle it instead.
    (assets / f"{args.model}.bin").write_bytes(descriptor)
    fixture_file = "fixtures.json" if args.model == "b6c96" else f"{args.model}-fixtures.json"
    (assets / fixture_file).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("Validated assets:", assets)


if __name__ == "__main__":
    main()
