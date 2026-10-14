"""Reference ABI and symmetry tests without Torch or large model weights."""
import sys
import unittest
from pathlib import Path
import importlib.util

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
AVAILABLE = importlib.util.find_spec('ai_edge_litert') is not None and importlib.util.find_spec('numpy') is not None
if AVAILABLE:
    import numpy as np
    import package_model as package


@unittest.skipUnless(AVAILABLE, 'Install tools/portable-model-requirements.txt')
class ModelPackageTest(unittest.TestCase):
    def outputs(self, channels=6):
        return [np.arange(channels * 362, dtype=np.float32).reshape(1, channels, 362),
                np.arange(3, dtype=np.float32).reshape(1, 3),
                np.arange(10, dtype=np.float32).reshape(1, 10),
                np.arange(8, dtype=np.float32).reshape(1, 8) + 100,
                np.arange(361, dtype=np.float32).reshape(1, 1, 19, 19)]

    def test_official_channel_selection_for_six_and_eight_policy_channels(self):
        for channels in (6, 8):
            raw = package.canonical(self.outputs(channels))
            self.assertEqual(raw.size, 1094)
            np.testing.assert_array_equal(raw[:362], np.arange(362))
            np.testing.assert_array_equal(raw[362:724], np.arange(5 * 362, 6 * 362))
            np.testing.assert_array_equal(raw[727:733], [0, 1, 2, 3, 100, 101])

    def test_all_symmetries_preserve_transport_and_inverse_raw_heads(self):
        spatial = np.arange(361 * 22).reshape(1, 19, 19, 22)
        raw = package.canonical(self.outputs())
        for index in range(8):
            transformed = package.symmetry(spatial, index)
            np.testing.assert_array_equal(package.symmetry(transformed, index, inverse=True), spatial)
            transformed_raw = raw.copy()
            for start in (0, 362, 733):
                transformed_raw[start:start + 361] = package.symmetry(
                    raw[start:start + 361].reshape(1, 19, 19, 1), index).reshape(-1)
            np.testing.assert_array_equal(package.inverse_raw(transformed_raw, index), raw)
            self.assertEqual(transformed_raw[361], raw[361])
            self.assertEqual(transformed_raw[723], raw[723])

    def test_invalid_shapes_and_nonfinite_references_are_rejected(self):
        outputs = self.outputs()
        outputs[0] = np.zeros((1, 4, 362), np.float32)
        with self.assertRaises(ValueError): package.canonical(outputs)
        outputs = self.outputs()
        outputs[3][0, 0] = np.inf
        with self.assertRaises(ValueError): package.canonical(outputs)


if __name__ == '__main__':
    unittest.main()
