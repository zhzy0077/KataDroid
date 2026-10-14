# Model conversion and provenance

The app includes paired original descriptors, converted networks and reference
fixtures for official g170 b6c96 and b10c128 weights. No retraining or INT8
calibration was performed. FLOAT32 tensor interfaces do not imply all vendor
kernels execute internally in FP32; the tested QNN path uses FP16 kernels.

| Model | Official archive name | Parameters |
| --- | --- | ---: |
| b6c96 | g170-b6c96-s175395328-d26788732 | 1,027,911 |
| b10c128 | g170e-b10c128-s1141046784-d204142634 | about 2.99 million |

Source archive hashes, TFLite hashes and original descriptor hashes are recorded
in `tools/prepare_katago.py`, `EngineConfig.kt` and each asset fixture manifest.
The g170e b10 archive filename differs from its embedded g170 descriptor name;
the model catalog records both. These g170 weights are [CC0](../THIRD_PARTY_NOTICES.md).

## Reproduce an export

Use Python 3.12 and an official KataGo **v1.18.2** executable built with the ONNX
backend and libzip support for NPZ export. Follow the upstream
[compilation guide](https://github.com/lightvector/KataGo/blob/v1.18.2/Compiling.md).
CPU app builds do not need this conversion environment.

```bash
uv venv --python python3.12 .venv-model
uv pip install --python .venv-model/bin/python -r tools/model-requirements.txt
.venv-model/bin/python tools/prepare_katago.py --katago /path/to/katago --model b6c96
.venv-model/bin/python tools/prepare_katago.py --katago /path/to/katago --model b10c128
python3 tools/check_integrity.py
```

The exporter runs `dumponnx -require-exact-nnlen -skip-scale8`, fixes batch size
one, removes `InputMask` only after verifying it has no consumers, then uses
onnx2tf for NHWC TFLite. Named signatures are added and every output is compared
with the untouched official ONNX export before app assets are written.

| Tensor | Shape | Meaning |
| --- | --- | --- |
| InputSpatial | 1×19×19×22 | Board, liberties, history, ladders and other spatial features |
| InputGlobal | 1×1×1×19 | Rules, komi and other global features |
| OutputPolicy | 1×19×19×1 | Board policy logits |
| OutputPolicyPass | 1×1×1×1 | Pass logit |
| OutputValue | 1×1×1×3 | Current-player win/loss/no-result logits |
| OutputScoreValue | 1×1×1×4 | Raw score-related outputs |
| OutputOwnership | 1×19×19×1 | Ownership before tanh |

Fixtures cover an empty board, an eight-move opening and a capture followed by
White to play. Official `evalsgf -dump-npz-input-to` supplies the input features;
Kotlin does not recreate the planes. Device tests also check official features
and postprocessed probabilities, score and ownership, including symmetry.

## Portable graph optimization

`tools/optimize_tflite.py` optimizes a portable TFLite graph before any vendor
compilation. It needs LiteRT's CPU interpreter and flatbuffer schema; it does not
need MediaTek, Qualcomm, or another vendor SDK.

```bash
uv venv --python python3.12 .local/portable-model-env
uv pip install --python .local/portable-model-env/bin/python \
  -r tools/portable-model-requirements.txt
.local/portable-model-env/bin/python tools/optimize_tflite.py \
  .local/models/source.tflite .local/models/portable.tflite \
  --sample .local/models/empty.npz \
  --sample .local/models/opening.npz \
  --sample .local/models/capture.npz \
  --report .local/models/optimization.json
.local/portable-model-env/bin/python -m unittest discover \
  -s tools/tests -p test_optimize_tflite.py -v
```

Each NPZ must contain every input keyed by its exact **tensor name** (as returned
by `Interpreter.get_input_details()`), with the original shape and dtype. Use
engine-generated features or an upstream reference export. Preserve the source
model separately. The command compares every output on every supplied sample
with built-in CPU kernels at absolute and relative tolerances of `1e-5`, checks
finite values, and publishes the optimized graph only after verification. The
JSON report records hashes, operation counts, transformations and maximum errors.
Signatures, original boundary tensors and metadata are retained.

The passes are conservative:

- Evaluate a whitelist of deterministic operations only when all operands are
  constants. This precomputes fixed-board learned rotary-position tables.
- Lower static FLOAT32 rank-five concatenations with a singleton leading batch
  to rank four, retaining interleaving order through explicit reshapes. Both
  shape operands and reshape options are emitted for compiler compatibility.
- Leave dynamic, quantized, non-singleton-batch and batch-axis concatenations
  unchanged. Reject stateful, custom/vendor-compiled, external-buffer and
  multi-subgraph models.

Small executable regression graphs run in the `Portable model tools` CI workflow,
including supported axes, input-dependent operations, output signatures,
idempotence and rejection cases. Ordinary Android builds do not install model
conversion dependencies. The standalone tool does not rewrite bundled assets or select a runtime backend.
For supported paired exports, `prepare_katago.py --optimize-portable` optionally
runs these passes on all engine-generated fixtures after the official ONNX
checks and before publishing assets. Its fixture manifest includes the
optimization report. The exporter's default behavior stays unchanged.

## Optional transformer packages

Download verified `.kdmodel` assets from
[KataDroid v1.1.0-rc.1](https://github.com/zhzy0077/KataDroid/releases/tag/v1.1.0-rc.1).
Install its compatible APK, download one package and select **Engine → Import
model**. Start with CPU. `MODEL_SHA256SUMS` verifies downloads. Each package
manifest embeds the official network license. Earlier app releases do not have
this importer.


Settings → **Engines & models → Import model package** accepts a `.kdmodel` ZIP.
Importing adds an optional model to the catalog; selecting it is a separate action.
The two bundled models and the default configuration stay unchanged. Installed
packages live in app-private, no-backup storage and are rediscovered on startup.

`ModelContract` defines two explicit graph ABIs:

| Contract | Descriptor | Spatial layout | Raw graph heads |
| --- | --- | --- | --- |
| `katago-onnx-v8` | Version 8 | NHWC | The five named ONNX heads above |
| `katago-pytorch-v17` | Version 17 | NCHW | `output_0` policy `[1,6 or 8,362]`, `output_1` value `[1,3]`, `output_2` misc `[1,10]`, `output_3` more-misc `[1,8]`, `output_4` ownership `[1,1,19,19]` |

Both require batch-one FLOAT32 19×19 graphs, 22 spatial features, 19 global
features, the `serving_default` signature, and no SGF metadata encoder. The v17
inputs are `args_0` and `args_1`. Every tensor boundary is shape/type-checked.
The native bridge validates the descriptor name, version and head channel counts.
Its 1,094-float normalized ABI contains base and optimistic policies, three value
logits, six score/error logits, and ownership. As in the official PyTorch exporter,
training policy channels 0 and 5 supply search policies; misc channels 0–3 and
more-misc channels 0–1 supply scores and uncertainty. Native code interpolates
policy optimism, reverses symmetry and delegates all nonlinear postprocessing,
no-result handling, perspective conversion and legality to the preserved
upstream NNEvaluator. Chinese simple-ko rules retain the no-result class.

### Build a package

Install the portable-tool requirements and a CPU PyTorch wheel compatible with
the upstream checkpoint (`torch==2.14.1` was used for the reference checks).
Use the original upstream Python model, with conversion-time monkeypatches absent:

```bash
python tools/package_model.py \
  --graph .local/models/portable.tflite \
  --descriptor .local/models/model.bin.gz \
  --checkpoint .local/models/model.ckpt \
  --upstream .local/upstream-katago \
  --fixtures app/src/main/assets/katago/fixtures.json \
  --source-name model-name \
  --license-file app/src/main/assets/licenses/katago-neural-networks.txt \
  --output .local/models/model.kdmodel
```

The tool checks original PyTorch and portable CPU outputs with absolute and
relative tolerances of `1e-4`, on every supplied position under all eight
symmetries. The package contains exactly four top-level files:

- `manifest.json`: schema version 1, contract, descriptor version/name, source
  name and SHA-256 hashes of the other three files.
- `model.tflite`: the portable graph, retaining CPU execution.
- `descriptor.bin`: matching uncompressed official descriptor.
- `fixtures.json`: NHWC engine features, normalized PyTorch RAW outputs,
  per-symmetry references and descriptor postprocessing multipliers.

The importer rejects unknown/duplicate entries, traversal paths, missing files,
checksum mismatches, unsupported contracts and non-finite/wrong-sized outputs.
A bounds-checked, mapped TFLite v3 envelope reader rejects custom/vendor bytecode,
control-flow/stateful/random operators, variable tensors, external buffers and
multiple subgraphs before native compilation.
Manifest, fixtures, graph and descriptor limits are 64 KiB, 32 MiB, 512 MiB and
512 MiB respectively, with a 1 GiB total expansion limit. It checks 3–32 distinct
CPU reference cases at `atol=0.001, rtol=0.0001`, runs the official descriptor
parser, and publishes the directory atomically after successful validation.
Analysis cache identity includes graph, descriptor, references and contract for
imported models. Failed imports leave the catalog unchanged.

`OptionalModelIntegrationTest` takes an explicit `modelPackage` filename in the
app's external-files directory. It verifies upstream RAW outputs, all eight
symmetries, exact native postprocessing (including score uncertainty), legal
search results and cancellation. Large weights are optional local test inputs;
ordinary CI runs small ABI, symmetry and archive-safety regression tests.

### Accelerator acceptance

Imported models require the same CPU reference tolerance on every selected
backend before a native search session opens. Existing bundled-model accelerator
checks keep their previous acceptance limits. A reduced-precision transformer
policy requires separate accuracy limits and device validation. CPU integration
checks validate CPU execution; NPU execution still needs the native delegation
audit in [npu.md](npu.md).

Manual GPU mode requests FP32 through LiteRT's published GPU accelerator. GPU
creation, invocation, readback and cleanup run on one context-owning executor;
native NN callbacks wait for that executor. GPU sessions require CPU reference
tolerances on bundled and imported models. Both bundled models passed physical
FP32 GPU reference/native-postprocessing tests, search, cancellation and cleanup.
The tested portable tf2 transformer fails LiteRT 2.2 GPU compilation on remaining
rank-five reshape tensors; GPU transformer acceptance requires further lowering
and device checks. Manual backend errors are reported.

The existing AUTO policy tries a packaged NPU runtime and falls back to CPU.
Measured CPU/GPU/NPU selection, runtime/firmware-keyed calibration caches and
optional SoC-specific AOT deployment are subsequent runtime work. Vendor
compiler/dispatch components remain behind the common LiteRT interface.
