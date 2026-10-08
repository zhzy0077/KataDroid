import io
import json
from pathlib import Path
from types import SimpleNamespace
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import tutorial_source
from verify_tutorial import verify_export


class TutorialSourceTests(unittest.TestCase):
    original = "(;SZ[19]KM[7.5]RU[Chinese];B[aa];W[bb];B[cc])"
    exported = "(;SZ[19]KM[7.5]RU[chinese];B[aa];W[bb](;B[cc])(;B[dd]))"
    lesson = {"beforeMove": 2, "afterMove": 3, "candidateCoordinate": "D16"}

    def test_export_retains_complete_mainline_and_new_branch(self):
        result = verify_export(self.original, self.exported, self.lesson)
        self.assertEqual(result["mainLineMoves"], 3)
        self.assertEqual(result["savedLines"], 2)

    def test_export_rejects_missing_or_different_variations(self):
        for exported in (self.original, self.exported.replace("B[dd]", "B[ee]"),
                         self.exported.replace("B[cc]", "B[cd]")):
            with self.subTest(exported=exported), self.assertRaises(AssertionError):
                verify_export(self.original, exported, self.lesson)

    def test_analysis_rejects_a_different_model_or_backend(self):
        for engine in ("wrong-model-CPU", tutorial_source.CACHE_KEY.replace("-CPU", "-NPU")):
            with self.subTest(engine=engine), self.assertRaises(ValueError):
                tutorial_source.summarize({"version": 3, "engine": engine, "analyses": []})

    def test_snapshot_selects_named_record_and_cpu_model_cache(self):
        buffer = io.BytesIO()
        files = {
            "files/analysis-cache-" + tutorial_source.CACHE_KEY + ".json":
                {"version": 3, "engine": tutorial_source.CACHE_KEY, "analyses": []},
            "files/current-record.json": {"nodes": ["current"]},
            "files/analysis-cache-other-model-NPU.json": {"analyses": ["wrong"]},
            "files/old-record.json": {"nodes": ["old"]},
        }
        with tarfile.open(fileobj=buffer, mode="w") as archive:
            for name, value in files.items():
                data = json.dumps(value).encode()
                member = tarfile.TarInfo(name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        device = SimpleNamespace(run=lambda *args: SimpleNamespace(stdout=buffer.getvalue()))
        with tempfile.TemporaryDirectory() as directory, patch.object(tutorial_source, "ROOT", Path(directory)):
            (Path(directory) / ".local/capture/tutorial").mkdir(parents=True)
            cache, record = tutorial_source.snapshot(device)
        self.assertEqual(cache["engine"], tutorial_source.CACHE_KEY)
        self.assertEqual(record["nodes"], ["current"])


if __name__ == "__main__":
    unittest.main()
