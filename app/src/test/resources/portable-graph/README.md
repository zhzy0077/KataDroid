# Synthetic portable-envelope fixtures

These small TFLite v3 graphs use the COS/ADD/CONCATENATION graph constructed by
`OptimizeTfliteTest.graph()` in `tools/tests/test_optimize_tflite.py`, serialized
with `tools/optimize_tflite.py`. They contain synthetic arrays, not model weights.

- `portable`: original graph.
- `legacy`: builtinCode omitted; deprecatedBuiltinCode retains each operator.
- `custom`: first opcode changed to CUSTOM with customCode `DISPATCH`.
- `stateful`: first opcode changed to VAR_HANDLE, deprecated code 127.
- `variable`: first tensor's isVariable set true.
- `external`: second buffer's offset set to 128.
- `subgraphs`: duplicate subgraph added.

The Android/JVM envelope checks use these without a native LiteRT dependency.
