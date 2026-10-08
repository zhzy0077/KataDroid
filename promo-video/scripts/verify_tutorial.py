#!/usr/bin/env python3
"""Audit tutorial sources, touch timing, narration and the real SGF export offline."""
import hashlib
import json
import math
from pathlib import Path
import subprocess
import wave

from capture_avd import ROOT
from mimo_tts import fingerprint
from sgf_mainline import mainline, parse
from tutorial_source import game_moves, summarize


def node_moves(nodes):
    return [(color, node[color][0]) for node in nodes for color in ("B", "W") if color in node]


def variation_paths(tree, prefix=()):
    sequence, variations = tree
    path = prefix + tuple(node_moves(sequence))
    if not variations:
        return [path]
    return [leaf for child in variations for leaf in variation_paths(child, path)]


def verify_export(original, exported, lesson):
    source_roots, export_roots = parse(original), parse(exported)
    assert len(source_roots) == len(export_roots) == 1, "Expected one complete game"
    source_nodes = mainline(source_roots[0])
    exported_nodes = mainline(export_roots[0])
    expected = tuple(node_moves(source_nodes))
    assert tuple(node_moves(exported_nodes)) == expected, "The exported main line changed"
    for key in ("SZ", "PB", "PW", "DT"):
        assert source_nodes[0].get(key) == exported_nodes[0].get(key), "Game facts changed: " + key
    assert float(source_nodes[0]["KM"][0]) == float(exported_nodes[0]["KM"][0])
    assert source_nodes[0]["RU"][0].lower() == exported_nodes[0]["RU"][0].lower()
    before, after = lesson["beforeMove"], lesson["afterMove"]
    assert after == before + 1 and 0 < after <= len(expected)
    coordinate = lesson["candidateCoordinate"]
    x = "ABCDEFGHJKLMNOPQRST".index(coordinate[0])
    y = 19 - int(coordinate[1:])
    assert 0 <= y < 19
    move = (expected[after - 1][0], chr(97 + x) + chr(97 + y))
    assert move != expected[after - 1], "The lesson must introduce a new variation"
    alternate = expected[:before] + (move,)
    paths = variation_paths(export_roots[0])
    assert len(paths) == 2 and expected in paths and alternate in paths, "The new variation was not exported"
    return {"mainLineMoves": len(expected), "savedLines": len(paths),
            "branchAtMove": after, "branchCoordinate": coordinate}


def verify():
    def load(relative):
        return json.loads((ROOT / relative).read_text())

    plan = load("content/tutorial.zh-CN.json")
    manifest = load(".local/public/audio/tutorial-manifest.json")
    assert manifest["fingerprint"] == fingerprint(plan), "Narration plan and cache differ"
    assert manifest["voice"] == plan["voice"]
    assert len(manifest["cues"]) == len(plan["clips"])
    for clip, cue in zip(plan["clips"], manifest["cues"]):
        assert (cue["id"], cue["from"], cue["text"]) == (clip["id"], clip["from"], clip["caption"])
        assert cue["durationInFrames"] <= math.ceil(clip["slotSeconds"] * plan["fps"])
        assert 1 <= cue["tempo"] <= 1.15
        path = (ROOT / ".local/public" / cue["src"]).resolve()
        assert path.is_relative_to((ROOT / ".local/public/audio").resolve())
        with wave.open(str(path), "rb") as audio:
            duration = audio.getnframes() / audio.getframerate()
        assert math.ceil(duration * plan["fps"]) == cue["durationInFrames"]

    touches = load(".local/public/tutorial/captures/touches.json")
    durations = {"import-menu": 4.5, "analysis": 8, "review": 14,
                 "preview": 10, "branches": 10, "export-menu": 4}
    sources = []
    for name, duration in durations.items():
        path = ROOT / ".local/public/tutorial/captures" / (name + ".mp4")
        data = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_streams", "-of", "json", str(path)]))
        video = next(s for s in data["streams"] if s["codec_type"] == "video")
        assert (video["width"], video["height"]) == (1080, 2272)
        assert video["avg_frame_rate"] == "30/1" and int(video["nb_frames"]) == round(duration * 30)
        assert abs(float(video["start_time"])) < .001
        events = touches[name]
        assert events, "Missing touch annotations: " + name
        assert [e["at"] for e in events] == sorted(e["at"] for e in events)
        for event in events:
            assert 0 <= event["at"] < duration
            assert 0 <= event["x"] <= 1080 and 0 <= event["y"] <= 2272
        sources.append({"clip": name, "seconds": duration, "touches": len(events)})

    cache = load(".local/capture/tutorial/analysis-snapshot.json")
    curve = summarize(cache, require_complete=True)
    lesson = load(".local/capture/tutorial/lesson.json")
    turning = curve["turningPoint"]
    assert (lesson["beforeMove"], lesson["afterMove"]) == (turning["beforeMove"], turning["afterMove"])
    annotation = (f"{turning['beforeMove']}→{turning['afterMove']} 手："
                  f"{turning['beforeWinRate']:.1f}% → {turning['afterWinRate']:.1f}%（黑棋）")
    motions = load("content/tutorial-motions.json")
    assert annotation in [s["text"] for s in motions["review"]["steps"]], "On-screen values differ from real analysis"

    source = ROOT / "content/tutorial-game.sgf"
    provenance = load(".local/capture/tutorial/source/source.json")
    assert hashlib.sha256(source.read_bytes()).hexdigest() == provenance["mainlineSha256"]
    exported = ROOT / ".local/capture/tutorial/exported-with-variation.sgf"
    export = verify_export(source.read_text(), exported.read_text(), lesson)
    preview = load(".local/capture/tutorial/preview-proof.json")
    checkpoint = load(".local/capture/tutorial/raw/preview-seven-record.json")
    assert preview["steps"] == checkpoint["previewStep"] == len(checkpoint["previewMoves"]) == 7
    assert checkpoint["previewMoves"] == preview["moves"] == lesson["previewMoves"]
    assert checkpoint["previewLabel"] == lesson["candidateLabel"] and preview["recordUnchanged"]
    base = next(n for n in checkpoint["nodes"] if n["id"] == checkpoint["selected"])
    assert base["move"] == lesson["beforeMove"]
    main_moves = [move[1] for move in game_moves()[:lesson["beforeMove"]]]
    entry = next(e for e in cache["analyses"] if e["moves"] == main_moves)
    candidate = entry["candidates"][ord(lesson["candidateLabel"]) - ord("A")]
    assert candidate["pv"][:7] == preview["moves"], "Preview must come from the real native PV"
    clip = ROOT / ".local/public/tutorial/captures/preview.mp4"
    assert hashlib.sha256(clip.read_bytes()).hexdigest() == preview["captureSha256"]
    report = {
        "narrationFingerprint": manifest["fingerprint"],
        "sourceClips": sources,
        "game": curve["game"],
        "model": curve["model"], "backend": curve["backend"],
        "analyzedPositions": len(curve["curve"]), "minimumVisits": curve["minVisits"],
        "turningPoint": turning,
        "preview": preview,
        "export": export,
        "sourceSgfSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "exportedSgfSha256": hashlib.sha256(exported.read_bytes()).hexdigest(),
    }
    output = ROOT / ".local/output/tutorial-source-verification.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    verify()
