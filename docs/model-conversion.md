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

The model catalog accepts verified paired exports. Importing arbitrary TFLite or
newer KataGo architectures is not supported yet: update the adapter, output
contract, hashes and reference tests together when adding a new architecture.
