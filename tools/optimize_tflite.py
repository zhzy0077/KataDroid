"""Portable, CPU-verified TFLite graph optimization (no vendor SDK required).

Samples are NPZ files keyed by input tensor name. Outputs are published only
when every supplied sample passes CPU equivalence checks. This does not grant
an architecture compatibility or NPU correctness certification.
"""
import argparse
from dataclasses import asdict, dataclass
import copy
import hashlib
import json
from pathlib import Path
import tempfile

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as schema
from ai_edge_litert.interpreter import Interpreter, OpResolverType


# Only pure, deterministic operators are eligible. Control flow, random,
# resource/variable and custom operators are deliberately excluded.
CONSTANT_OPS = {
    getattr(schema.BuiltinOperator, name) for name in (
        "ADD", "SUB", "MUL", "DIV", "RESHAPE", "TRANSPOSE", "CONCATENATION",
        "COS", "SIN", "CAST", "FLOOR_DIV", "FLOOR_MOD", "STRIDED_SLICE",
        "SLICE", "GATHER", "PACK", "UNPACK", "EXPAND_DIMS", "SQUEEZE",
        "RANGE", "BROADCAST_TO", "NEG", "SIGN", "NOT_EQUAL", "EQUAL",
        "LOGICAL_AND", "SELECT", "SELECT_V2", "SHAPE", "TILE", "SUM",
        "MAXIMUM", "MINIMUM",
    ) if hasattr(schema.BuiltinOperator, name)
}


@dataclass(frozen=True)
class OptimizationReport:
    source_sha256: str
    optimized_sha256: str
    folded_ops: int
    lowered_concatenations: int
    original_ops: int
    optimized_ops: int
    samples_verified: int
    output_max_abs_errors: tuple[float, ...]


def serialize(model):
    builder = flatbuffers.Builder(1024)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    return bytes(builder.Output())


def parse(data):
    if len(data) < 8 or data[4:8] != b"TFL3":
        raise ValueError("Expected a TFLite flatbuffer")
    model = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(data, 0))
    if model.version != 3 or len(model.subgraphs) != 1:
        raise ValueError("Optimization requires one version-3 TFLite subgraph")
    if any(getattr(b, "offset", 0) or getattr(b, "size", 0) for b in model.buffers):
        raise ValueError("External tensor buffers are not supported")
    if any(t.isVariable for t in model.subgraphs[0].tensors):
        raise ValueError("Stateful models are not supported")
    forbidden = {getattr(schema.BuiltinOperator, name) for name in (
        "CALL", "CUSTOM", "IF", "WHILE", "CALL_ONCE", "VAR_HANDLE", "READ_VARIABLE",
        "ASSIGN_VARIABLE", "HASHTABLE", "HASHTABLE_FIND", "HASHTABLE_IMPORT", "HASHTABLE_SIZE",
        "RANDOM_STANDARD_NORMAL", "RANDOM_UNIFORM", "MULTINOMIAL")}
    if any(c.builtinCode in forbidden or max(c.builtinCode, c.deprecatedBuiltinCode) in forbidden or c.customCode is not None
           for c in model.operatorCodes):
        raise ValueError("Custom, stateful or control-flow operators are not supported")
    return model


def interpreter(data, preserve=False, builtin=False):
    # Compare the same built-in kernels on both sides. Delegates can select
    # different kernels after rewriting, introducing unrelated numerical drift.
    instance = Interpreter(
        model_content=data, num_threads=1,
        experimental_preserve_all_tensors=preserve,
        experimental_op_resolver_type=(OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES
                                       if preserve or builtin else OpResolverType.AUTO),
    )
    instance.allocate_tensors()
    return instance


def evaluate(instance, sample):
    inputs = instance.get_input_details()
    if set(sample) != {d["name"] for d in inputs}:
        raise ValueError("Sample keys must exactly match input tensor names")
    for detail in inputs:
        value = np.asarray(sample[detail["name"]])
        if value.dtype != detail["dtype"] or tuple(value.shape) != tuple(detail["shape"]):
            raise ValueError(f"Incorrect dtype or shape for {detail['name']}")
        if not np.isfinite(value).all():
            raise ValueError(f"Non-finite input: {detail['name']}")
        instance.set_tensor(detail["index"], value)
    instance.invoke()
    outputs = [instance.get_tensor(d["index"]) for d in instance.get_output_details()]
    if any(not np.isfinite(value).all() for value in outputs):
        raise ValueError("Non-finite reference or optimized output")
    return outputs


def fold_constants(model):
    graph = model.subgraphs[0]
    constants = {
        i for i, tensor in enumerate(graph.tensors)
        if i not in graph.inputs and not tensor.isVariable
        and model.buffers[tensor.buffer].data is not None
        and len(model.buffers[tensor.buffer].data) > 0
    }
    operators, constant_operators, constant_outputs = [], [], set()
    for op in graph.operators:
        opcode = model.operatorCodes[op.opcodeIndex]
        code = max(opcode.builtinCode, opcode.deprecatedBuiltinCode)
        if (code in CONSTANT_OPS and len(op.outputs) > 0
                and all(i < 0 or i in constants for i in op.inputs)
                and all(i >= 0 and not graph.tensors[i].isVariable for i in op.outputs)):
            constant_operators.append(op)
            constants.update(int(i) for i in op.outputs)
            constant_outputs.update(int(i) for i in op.outputs)
        else:
            operators.append(op)
    if constant_operators:
        # Evaluate only the constant dependency closure, not the expensive
        # input-dependent network. No intermediate can be discarded by a CPU
        # delegate, and sample inputs cannot influence the extracted values.
        extraction = copy.deepcopy(model)
        extraction.signatureDefs = []
        extraction.subgraphs[0].operators = copy.deepcopy(constant_operators)
        extraction.subgraphs[0].inputs = np.asarray([], np.int32)
        extraction.subgraphs[0].outputs = np.asarray(sorted(constant_outputs), np.int32)
        instance = interpreter(serialize(extraction), preserve=True)
        instance.invoke()
        for index in sorted(constant_outputs):
            value = instance.get_tensor(index)
            if not np.isfinite(value).all():
                raise ValueError("Non-finite constant expression")
            buffer = schema.BufferT()
            buffer.data = np.frombuffer(value.tobytes(), dtype=np.uint8).copy()
            model.buffers.append(buffer)
            graph.tensors[index].buffer = len(model.buffers) - 1
    graph.operators = operators
    return len(constant_operators)


def lower_concatenations(model):
    graph = model.subgraphs[0]
    reshape_index = next((i for i, code in enumerate(model.operatorCodes)
                          if max(code.builtinCode, code.deprecatedBuiltinCode) == schema.BuiltinOperator.RESHAPE), None)

    def tensor_like(index, shape):
        source = graph.tensors[index]
        tensor = schema.TensorT()
        tensor.name = (source.name or b"tensor") + b"_rank4"
        tensor.shape = np.asarray(shape, dtype=np.int32)
        tensor.type = source.type
        tensor.buffer = 0
        graph.tensors.append(tensor)
        return len(graph.tensors) - 1

    def reshape(source, destination, shape):
        nonlocal reshape_index
        if reshape_index is None:
            code = schema.OperatorCodeT()
            code.builtinCode = code.deprecatedBuiltinCode = schema.BuiltinOperator.RESHAPE
            model.operatorCodes.append(code)
            reshape_index = len(model.operatorCodes) - 1
        buffer = schema.BufferT()
        buffer.data = np.frombuffer(np.asarray(shape, np.int32).tobytes(), np.uint8).copy()
        model.buffers.append(buffer)
        tensor = schema.TensorT()
        tensor.name = b"portable_reshape_shape"
        tensor.shape = np.asarray([len(shape)], dtype=np.int32)
        tensor.type = schema.TensorType.INT32
        tensor.buffer = len(model.buffers) - 1
        graph.tensors.append(tensor)
        op = schema.OperatorT()
        op.opcodeIndex = reshape_index
        # Supply a shape operand as well as options: required by some vendor
        # compilers, even though the CPU interpreter accepts options alone.
        op.inputs = np.asarray([source, len(graph.tensors) - 1], dtype=np.int32)
        op.outputs = np.asarray([destination], dtype=np.int32)
        op.builtinOptionsType = schema.BuiltinOptions.ReshapeOptions
        op.builtinOptions = schema.ReshapeOptionsT()
        op.builtinOptions.newShape = np.asarray(shape, dtype=np.int32)
        return op

    operators = []
    lowered = 0
    for op in graph.operators:
        opcode = model.operatorCodes[op.opcodeIndex]
        code = max(opcode.builtinCode, opcode.deprecatedBuiltinCode)
        eligible = (code == schema.BuiltinOperator.CONCATENATION
                    and len(op.inputs) > 0 and len(op.outputs) == 1
                    and op.builtinOptions.axis in (-1, 1, 2, 3, 4)
                    and all(i >= 0 and len(graph.tensors[i].shape) == 5
                            and graph.tensors[i].shape[0] == 1
                            and np.all(graph.tensors[i].shape > 0)
                            and graph.tensors[i].type == schema.TensorType.FLOAT32
                            and (graph.tensors[i].quantization is None
                                 or (graph.tensors[i].quantization.scale is None
                                     or len(graph.tensors[i].quantization.scale) == 0))
                            and (graph.tensors[i].shapeSignature is None
                                 or np.all(graph.tensors[i].shapeSignature > 0))
                            for i in list(op.inputs) + list(op.outputs)))
        if not eligible:
            operators.append(op)
            continue
        inputs = []
        for index in op.inputs:
            shape = graph.tensors[index].shape[1:]
            lowered_input = tensor_like(index, shape)
            operators.append(reshape(index, lowered_input, shape))
            inputs.append(lowered_input)
        original_output = int(op.outputs[0])
        lowered_output = tensor_like(original_output, graph.tensors[original_output].shape[1:])
        op.inputs = np.asarray(inputs, np.int32)
        op.outputs = np.asarray([lowered_output], np.int32)
        if op.builtinOptions.axis >= 0:
            op.builtinOptions.axis -= 1
        operators.append(op)
        operators.append(reshape(lowered_output, original_output, graph.tensors[original_output].shape))
        lowered += 1
    graph.operators = operators
    return lowered


def optimize(data, samples, *, atol=1e-5, rtol=1e-5):
    if not samples:
        raise ValueError("At least one validation sample is required")
    if not np.isfinite(atol) or not np.isfinite(rtol) or atol < 0 or rtol < 0:
        raise ValueError("Tolerances must be finite and nonnegative")
    model = parse(data)
    before = len(model.subgraphs[0].operators)
    reference = interpreter(data, builtin=True)
    folded = fold_constants(model)
    lowered = lower_concatenations(model)
    optimized = serialize(model)
    candidate = interpreter(optimized, builtin=True)
    errors = [0.0] * len(reference.get_output_details())
    for sample in samples:
        expected, actual = evaluate(reference, sample), evaluate(candidate, sample)
        for index, (left, right) in enumerate(zip(expected, actual)):
            if left.shape != right.shape or left.dtype != right.dtype:
                raise ValueError("Optimization changed an output contract")
            np.testing.assert_allclose(right, left, atol=atol, rtol=rtol,
                                       err_msg=f"Output {index} equivalence failed")
            if left.size:
                errors[index] = max(errors[index], float(np.max(np.abs(
                    left.astype(np.float64) - right.astype(np.float64)))))
    report = OptimizationReport(
        hashlib.sha256(data).hexdigest(), hashlib.sha256(optimized).hexdigest(),
        folded, lowered, before, len(model.subgraphs[0].operators), len(samples), tuple(errors),
    )
    return optimized, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--sample", required=True, action="append", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve():
        parser.error("Use a separate output file to preserve the source model")
    if args.report and args.report.resolve() in (args.source.resolve(), args.output.resolve()):
        parser.error("Report must be separate from both model files")
    samples = []
    for path in args.sample:
        with np.load(path, allow_pickle=False) as archive:
            samples.append({key: archive[key].copy() for key in archive.files})
    data, report = optimize(args.source.read_bytes(), samples)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Publish only after all verification succeeds, without partially replacing
    # an existing artifact if writing fails.
    with tempfile.NamedTemporaryFile(dir=args.output.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(data)
            output.close()
            temporary.replace(args.output)
        finally:
            temporary.unlink(missing_ok=True)
    text = json.dumps(asdict(report), indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text)
    print(text, end="")


if __name__ == "__main__":
    main()
