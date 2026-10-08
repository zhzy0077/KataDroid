#!/usr/bin/env python3
"""Validate the rendered sample without network or device access."""
import array
import json
import math
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
final = "--final" in sys.argv
tutorial = "--tutorial" in sys.argv
kind = "tutorial" if tutorial else "promo" if final else "teaser"
plan = json.loads((ROOT / f"content/{kind}.zh-CN.json").read_text())
video = ROOT / ".local/output" / ((plan["slug"] if final or tutorial else "katadroid-teaser-zh-1080p") + ".mp4")
manifest = json.loads((ROOT / ".local/public" / plan.get("manifest", "audio/manifest.json")).read_text())
data = json.loads(subprocess.check_output([
    "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(video)
]))
picture = next(s for s in data["streams"] if s["codec_type"] == "video")
sound = next(s for s in data["streams"] if s["codec_type"] == "audio")
assert (picture["width"], picture["height"]) == (plan["width"], plan["height"])
assert picture["codec_name"] == "h264"
assert picture["pix_fmt"] == "yuv420p"
assert picture["color_space"] == "bt709"
assert picture["avg_frame_rate"] == f"{plan['fps']}/1"
assert int(picture["nb_frames"]) == plan["durationInFrames"]
assert abs(float(data["format"]["duration"]) - plan["durationInFrames"] / plan["fps"]) < 0.05
if final or tutorial:
    assert float(data["format"]["duration"]) < 60, "成片必须小于 1 分钟"
assert sound["codec_name"] == "aac" and int(sound["sample_rate"]) == 48000
subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-i", str(video), "-f", "null", "-"], check=True)
pcm = array.array("f")
pcm.frombytes(subprocess.check_output([
    "ffmpeg", "-v", "error", "-i", str(video), "-vn", "-ac", "1", "-ar", "48000", "-f", "f32le", "-"
]))
if sys.byteorder != "little":
    pcm.byteswap()
assert max(abs(v) for v in pcm) < 1, "混音发生削波"
assert len(manifest["cues"]) == len(plan["clips"]), "旁白片段不完整"
if tutorial:
    assert all(cue["tempo"] <= 1.15 for cue in manifest["cues"]), "教学语速需要自然清晰"
levels = []
for cue in manifest["cues"]:
    start = round(cue["from"] / plan["fps"] * 48000)
    end = round((cue["from"] + cue["durationInFrames"]) / plan["fps"] * 48000)
    samples = pcm[start:end]
    rms = math.sqrt(sum(v * v for v in samples) / len(samples))
    assert rms > 0.015, f"{cue['id']} 旁白区间过于安静"
    levels.append({"clip": cue["id"], "rmsDbfs": round(20 * math.log10(rms), 2)})
report = {
    "durationSeconds": float(data["format"]["duration"]),
    "frames": int(picture["nb_frames"]),
    "dimensions": [picture["width"], picture["height"]],
    "videoCodec": picture["codec_name"], "colorSpace": picture["color_space"],
    "audioCodec": sound["codec_name"], "sampleRate": int(sound["sample_rate"]),
    "sizeBytes": int(data["format"]["size"]), "narrationLevels": levels,
    "fullDecode": "passed", "clipping": "none",
}
(ROOT / ".local/output" / (kind + "-verification.json" if final or tutorial else "verification.json")).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(report, ensure_ascii=False, indent=2))
