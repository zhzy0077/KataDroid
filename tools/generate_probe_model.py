"""Generate an untrained 19x19 convolution probe, not a KataGo model.

Requires tflite==2.18.0 and flatbuffers==25.2.10. Output is checked in;
normal Android builds do not require Python packages.
"""
from pathlib import Path
import struct
import flatbuffers
import tflite as t

b = flatbuffers.Builder(4096)


def ints(values):
    b.StartVector(4, len(values), 4)
    for value in reversed(values):
        b.PrependInt32(value)
    return b.EndVector()


def offsets(values):
    b.StartVector(4, len(values), 4)
    for value in reversed(values):
        b.PrependUOffsetTRelative(value)
    return b.EndVector()


def buffer(data=b""):
    raw = b.CreateByteVector(data)
    t.BufferStart(b)
    t.BufferAddData(b, raw)
    return t.BufferEnd(b)


def tensor(name, shape, index):
    name = b.CreateString(name)
    shape = ints(shape)
    t.TensorStart(b)
    t.TensorAddName(b, name)
    t.TensorAddShape(b, shape)
    t.TensorAddType(b, t.TensorType.FLOAT32)
    t.TensorAddBuffer(b, index)
    return t.TensorEnd(b)


# OHWI weights: multiples of 1/16 make the reference arithmetic transparent.
weights = [(i % 9 - 4) / 16 for i in range(36)]
bias = [i / 8 for i in range(4)]
buffers = offsets([buffer(), buffer(struct.pack('<36f', *weights)), buffer(struct.pack('<4f', *bias))])
tensors = offsets([
    tensor("board", [1, 19, 19, 1], 0),
    tensor("kernel", [4, 3, 3, 1], 1),
    tensor("bias", [4], 2),
    tensor("output", [1, 19, 19, 4], 0),
])
t.Conv2DOptionsStart(b)
t.Conv2DOptionsAddPadding(b, t.Padding.SAME)
t.Conv2DOptionsAddStrideW(b, 1)
t.Conv2DOptionsAddStrideH(b, 1)
t.Conv2DOptionsAddDilationWFactor(b, 1)
t.Conv2DOptionsAddDilationHFactor(b, 1)
options = t.Conv2DOptionsEnd(b)
op_inputs, op_outputs = ints([0, 1, 2]), ints([3])
t.OperatorStart(b)
t.OperatorAddOpcodeIndex(b, 0)
t.OperatorAddInputs(b, op_inputs)
t.OperatorAddOutputs(b, op_outputs)
t.OperatorAddBuiltinOptionsType(b, t.BuiltinOptions.Conv2DOptions)
t.OperatorAddBuiltinOptions(b, options)
operators = offsets([t.OperatorEnd(b)])
inputs, outputs = ints([0]), ints([3])
t.SubGraphStart(b)
t.SubGraphAddTensors(b, tensors)
t.SubGraphAddInputs(b, inputs)
t.SubGraphAddOutputs(b, outputs)
t.SubGraphAddOperators(b, operators)
graphs = offsets([t.SubGraphEnd(b)])
t.OperatorCodeStart(b)
t.OperatorCodeAddBuiltinCode(b, t.BuiltinOperator.CONV_2D)
t.OperatorCodeAddDeprecatedBuiltinCode(b, t.BuiltinOperator.CONV_2D)
t.OperatorCodeAddVersion(b, 1)
codes = offsets([t.OperatorCodeEnd(b)])
description = b.CreateString("KataDroid deterministic convolution probe; not a trained Go model")
t.ModelStart(b)
t.ModelAddVersion(b, 3)
t.ModelAddOperatorCodes(b, codes)
t.ModelAddSubgraphs(b, graphs)
t.ModelAddBuffers(b, buffers)
t.ModelAddDescription(b, description)
b.Finish(t.ModelEnd(b), file_identifier=b"TFL3")
dest = Path(__file__).resolve().parents[1] / "app/src/main/assets/conv_probe.tflite"
dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_bytes(b.Output())
print(dest)
