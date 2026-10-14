"""Small executable graphs exercise portable passes without TensorFlow or vendor SDKs."""
import importlib.util
from pathlib import Path
import sys
import unittest
import subprocess
import tempfile
import json

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
AVAILABLE = all(importlib.util.find_spec(name) for name in ("numpy", "flatbuffers", "ai_edge_litert"))
if AVAILABLE:
    import numpy as np
    import optimize_tflite as tool
    s = tool.schema


@unittest.skipUnless(AVAILABLE, "Install tools/portable-model-requirements.txt")
class OptimizeTfliteTest(unittest.TestCase):
    def graph(self, shape=(1, 2, 2, 2, 2), axis=-1):
        m = s.ModelT()
        m.version = 3
        m.description = b"portable regression graph"
        m.buffers = [s.BufferT(), s.BufferT()]
        m.buffers[1].data = np.frombuffer(np.full(shape, 0.3, np.float32).tobytes(), np.uint8).copy()
        m.operatorCodes = []
        for builtin in (s.BuiltinOperator.COS, s.BuiltinOperator.ADD, s.BuiltinOperator.CONCATENATION):
            code = s.OperatorCodeT()
            code.builtinCode = code.deprecatedBuiltinCode = builtin
            m.operatorCodes.append(code)
        g = s.SubGraphT()
        g.name = b"main"
        g.tensors = []
        out_shape = list(shape)
        out_shape[axis] *= 2
        for name, tensor_shape, buffer in ((b"input", shape, 0), (b"constant", shape, 1),
                                           (b"cos", shape, 0), (b"sum", shape, 0),
                                           (b"output", out_shape, 0)):
            t = s.TensorT()
            t.name = name
            t.shape = np.asarray(tensor_shape, np.int32)
            t.type = s.TensorType.FLOAT32
            t.buffer = buffer
            # Converters commonly emit an empty quantization record on FP32.
            t.quantization = s.QuantizationParametersT()
            g.tensors.append(t)
        g.inputs = np.asarray([0], np.int32)
        g.outputs = np.asarray([4, 2], np.int32)
        g.operators = []
        for code, inputs, outputs in ((0, [1], [2]), (1, [0, 2], [3]), (2, [3, 0], [4])):
            op = s.OperatorT()
            op.opcodeIndex = code
            op.inputs, op.outputs = np.asarray(inputs, np.int32), np.asarray(outputs, np.int32)
            g.operators.append(op)
        g.operators[1].builtinOptionsType = s.BuiltinOptions.AddOptions
        g.operators[1].builtinOptions = s.AddOptionsT()
        g.operators[2].builtinOptionsType = s.BuiltinOptions.ConcatenationOptions
        g.operators[2].builtinOptions = s.ConcatenationOptionsT()
        g.operators[2].builtinOptions.axis = axis
        m.subgraphs = [g]
        sig = s.SignatureDefT()
        sig.signatureKey = "serving_default"
        sig.subgraphIndex = 0
        def entry(name, index):
            e = s.TensorMapT()
            e.name, e.tensorIndex = name, index
            return e
        sig.inputs = [entry("input", 0)]
        sig.outputs = [entry("output", 4), entry("constant_output", 2)]
        m.signatureDefs = [sig]
        return m

    def samples(self, shape):
        rng = np.random.default_rng(8)
        return [{"input": rng.normal(size=shape).astype(np.float32)} for _ in range(3)]

    def test_constants_and_all_supported_axes(self):
        for axis in (-1, 1, 2, 3, 4):
            with self.subTest(axis=axis):
                model = self.graph(axis=axis)
                data, report = tool.optimize(tool.serialize(model), self.samples((1, 2, 2, 2, 2)))
                self.assertEqual(report.folded_ops, 1)
                self.assertEqual(report.lowered_concatenations, 1)
                self.assertEqual(report.samples_verified, 3)
                self.assertTrue(all(error < 1e-6 for error in report.output_max_abs_errors))
                optimized = tool.parse(data)
                self.assertEqual(optimized.description, model.description)
                self.assertEqual(optimized.signatureDefs[0].signatureKey, b"serving_default")
                self.assertEqual(list(optimized.subgraphs[0].outputs), [4, 2])
                self.assertEqual(tool.interpreter(data).get_signature_list(),
                                 tool.interpreter(tool.serialize(model)).get_signature_list())
                for op in optimized.subgraphs[0].operators:
                    if optimized.operatorCodes[op.opcodeIndex].builtinCode == s.BuiltinOperator.RESHAPE:
                        self.assertEqual(len(op.inputs), 2)
                        self.assertEqual(optimized.subgraphs[0].tensors[op.inputs[1]].type, s.TensorType.INT32)

    def test_non_singleton_batch_is_not_lowered(self):
        _, report = tool.optimize(tool.serialize(self.graph(shape=(2, 2, 2, 2, 2))),
                                  self.samples((2, 2, 2, 2, 2)))
        self.assertEqual(report.lowered_concatenations, 0)

    def test_batch_axis_is_not_lowered(self):
        _, report = tool.optimize(tool.serialize(self.graph(axis=0)), self.samples((1, 2, 2, 2, 2)))
        self.assertEqual(report.lowered_concatenations, 0)

    def test_dynamic_shape_is_not_lowered(self):
        model = self.graph()
        model.subgraphs[0].tensors[0].shapeSignature = np.asarray([1, -1, 2, 2, 2], np.int32)
        self.assertEqual(tool.lower_concatenations(model), 0)

    def test_quantized_or_non_float_contract_is_not_lowered(self):
        model = self.graph()
        model.subgraphs[0].tensors[0].quantization.scale = np.asarray([0.1], np.float32)
        self.assertEqual(tool.lower_concatenations(model), 0)
        model = self.graph()
        model.subgraphs[0].tensors[0].type = s.TensorType.INT32
        self.assertEqual(tool.lower_concatenations(model), 0)

    def test_idempotence(self):
        samples = self.samples((1, 2, 2, 2, 2))
        data, _ = tool.optimize(tool.serialize(self.graph()), samples)
        twice, report = tool.optimize(data, samples)
        self.assertEqual(report.folded_ops, 0)
        self.assertEqual(report.lowered_concatenations, 0)
        self.assertEqual(data, twice)

    def test_invalid_samples_and_tolerances(self):
        data = tool.serialize(self.graph())
        for samples in ([], [{}], [{"input": np.zeros((1,), np.float32)}],
                        [{"input": np.zeros((1, 2, 2, 2, 2), np.float64)}],
                        [{"input": np.full((1, 2, 2, 2, 2), np.nan, np.float32)}]):
            with self.subTest(samples=str(samples)[:30]), self.assertRaises(ValueError):
                tool.optimize(data, samples)
        for tolerance in (-1, float("nan"), float("inf")):
            with self.assertRaises(ValueError):
                tool.optimize(data, self.samples((1, 2, 2, 2, 2)), atol=tolerance)

    def test_rejects_nonportable_models(self):
        with self.assertRaises(ValueError):
            tool.parse(b"not a model")
        for mutation in ("variable", "external", "custom", "stateful", "random", "subgraph"):
            model = self.graph()
            if mutation == "variable": model.subgraphs[0].tensors[0].isVariable = True
            elif mutation == "external": model.buffers[1].offset = 128
            elif mutation == "custom": model.operatorCodes[0].builtinCode = s.BuiltinOperator.CUSTOM
            elif mutation == "stateful": model.operatorCodes[0].builtinCode = s.BuiltinOperator.VAR_HANDLE
            elif mutation == "random": model.operatorCodes[0].builtinCode = s.BuiltinOperator.RANDOM_UNIFORM
            else: model.subgraphs.append(model.subgraphs[0])
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                tool.parse(tool.serialize(model))

    def test_legacy_builtin_fields_are_interpreted_consistently(self):
        model = self.graph()
        for code in model.operatorCodes:
            code.builtinCode = 0
        _, report = tool.optimize(tool.serialize(model), self.samples((1, 2, 2, 2, 2)))
        self.assertEqual(report.folded_ops, 1)
        self.assertEqual(report.lowered_concatenations, 1)

    def test_cli_publication_and_failure_preserves_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output, sample, report = [root / name for name in
                                              ("source.tflite", "output.tflite", "sample.npz", "report.json")]
            source.write_bytes(tool.serialize(self.graph()))
            np.savez(sample, **self.samples((1, 2, 2, 2, 2))[0])
            command = [sys.executable, str(Path(tool.__file__)), str(source), str(output),
                       "--sample", str(sample), "--report", str(report)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(report.read_text())["samples_verified"], 1)
            previous = output.read_bytes()
            np.savez(sample, input=np.zeros((1,), np.float32))
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_bytes(), previous)
            self.assertFalse(list(root.glob("tmp*")))
            result = subprocess.run([sys.executable, str(Path(tool.__file__)), str(source),
                                     str(source), "--sample", str(sample)], capture_output=True)
            self.assertEqual(result.returncode, 2)

    def test_nonfinite_constants_are_rejected(self):
        model = self.graph()
        model.buffers[1].data = np.frombuffer(np.full((1, 2, 2, 2, 2), np.inf, np.float32).tobytes(), np.uint8).copy()
        with self.assertRaises(ValueError):
            tool.optimize(tool.serialize(model), self.samples((1, 2, 2, 2, 2)))

    def test_data_dependent_ops_are_retained(self):
        model = self.graph()
        model.subgraphs[0].operators[0].inputs = np.asarray([0], np.int32)
        _, report = tool.optimize(tool.serialize(model), self.samples((1, 2, 2, 2, 2)))
        self.assertEqual(report.folded_ops, 0)


if __name__ == "__main__":
    unittest.main()
