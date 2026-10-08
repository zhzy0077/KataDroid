import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from types import SimpleNamespace
from unittest.mock import Mock
from capture_avd import Avd, PACKAGE, ROOT, normalize_take


class CaptureTests(unittest.TestCase):
    def test_checkpoint_keeps_the_app_screen_ui_and_persisted_record(self):
        with tempfile.TemporaryDirectory() as directory:
            device = Avd.__new__(Avd)
            device.raw = Path(directory)
            device.screenshot = Mock()
            device.dump = Mock()
            payload = b'{"previewStep":7,"previewMoves":[1,2,3,4,5,6,7]}'
            device.run = Mock(return_value=SimpleNamespace(stdout=payload))
            device.checkpoint("preview-seven")
            device.screenshot.assert_called_once_with("preview-seven")
            device.dump.assert_called_once_with("preview-seven")
            device.run.assert_called_once_with("exec-out", "run-as", PACKAGE, "cat", "files/current-record.json")
            self.assertEqual((device.raw / "preview-seven-record.json").read_bytes(), payload)

    def test_compose_button_selection_uses_checked_accessibility_state(self):
        for attributes, expected in [
            ({"selected": "false", "checkable": "true", "checked": "true"}, True),
            ({"selected": "true", "checked": "false"}, True),
            ({"selected": "false", "checked": "false"}, False),
            ({}, False),
        ]:
            with self.subTest(attributes=attributes):
                self.assertEqual(Avd.is_selected(ET.Element("node", attributes)), expected)

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg required")
    def test_static_tail_can_cover_recorder_preroll(self):
        base = ROOT / ".local/tests"
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as directory:
            raw = Path(directory) / "still.mp4"
            output = Path(directory) / "held.mp4"
            subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i",
                "color=c=black:s=64x64:r=1:d=1", "-c:v", "libx264", str(raw)], check=True)
            normalize_take(raw, output, 3, 4, crop="64:64:0:0")
            info = json.loads(subprocess.check_output(["ffprobe", "-v", "error",
                "-show_streams", "-of", "json", str(output)]))["streams"][0]
            self.assertEqual(int(info["nb_frames"]), 90)
            self.assertEqual(float(info["start_time"]), 0)

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg required")
    def test_vfr_hold_survives_trim(self):
        base = ROOT / ".local/tests"
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as directory:
            raw = Path(directory) / "vfr.mp4"
            output = Path(directory) / "trimmed.mp4"
            subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i",
                "color=c=black:s=64x64:r=1:d=7", "-vf",
                "drawbox=c=red:t=fill:enable='gte(t,4)',select='eq(n,0)+gte(n,4)'",
                "-fps_mode", "vfr", "-c:v", "libx264", str(raw)], check=True)
            normalize_take(raw, output, 3, 2, crop="64:64:0:0")
            info = json.loads(subprocess.check_output(["ffprobe", "-v", "error",
                "-show_streams", "-of", "json", str(output)]))["streams"][0]
            self.assertEqual(int(info["nb_frames"]), 90)
            self.assertEqual(float(info["start_time"]), 0)
            pixels = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(output),
                "-vf", "scale=1:1", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"])
            self.assertLess(pixels[0], 10)  # the original black frame is held at the start
            self.assertGreater(pixels[-3], 200)  # then the real red change appears


if __name__ == "__main__":
    unittest.main()
