#!/usr/bin/env python3
"""Inspect real, locally cached App analysis and select a documented reversal."""
import argparse
import io
import json
from pathlib import Path
import tarfile
from capture_avd import Avd, ROOT, PACKAGE
from sgf_mainline import mainline, parse

MODEL = "b6c96"
CACHE_KEY = "6611aa4aa2b6d51273337830ccd80884d8a1d3b46bb0a201e26cc3d798b35d72-CPU"


def snapshot(v):
    raw = v.run("exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "files").stdout
    cache = None
    record = None
    with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
        for member in tar.getmembers():
            if member.name == f"files/analysis-cache-{CACHE_KEY}.json":
                cache = json.loads(tar.extractfile(member).read())
            elif member.name == "files/current-record.json":
                record = json.loads(tar.extractfile(member).read())
    if cache is None or record is None:
        raise RuntimeError("App has not persisted the record and analysis yet.")
    base = ROOT / ".local/capture/tutorial"
    (base / "analysis-snapshot.json").write_text(json.dumps(cache))
    (base / "current-record-snapshot.json").write_text(json.dumps(record))
    return cache, record


def game_moves():
    result = []
    for node in mainline(parse((ROOT / "content/tutorial-game.sgf").read_text())[0]):
        for color in ("B", "W"):
            if color in node:
                value = node[color][0]
                point = -1 if not value else (ord(value[1]) - 97) * 19 + ord(value[0]) - 97
                result.append((1 if color == "B" else 2, point))
    return result


def summarize(cache, require_complete=False):
    if cache.get("version") != 3 or cache.get("engine") != CACHE_KEY:
        raise ValueError("Tutorial analysis must use the b6c96 / CPU cache.")
    moves = game_moves()
    positions = {}
    for entry in cache["analyses"]:
        count = len(entry["moves"])
        if count > len(moves) or entry["backend"] != "CPU":
            continue
        if entry["moves"] != [m[1] for m in moves[:count]] or entry["moveColors"] != [m[0] for m in moves[:count]]:
            continue
        if entry["initialBlack"] or entry["initialWhite"] or entry["komi"] != 7.5 or entry["rules"] != "chinese":
            continue
        if entry["visits"] >= positions.get(count, {}).get("visits", 0):
            positions[count] = entry
    sufficient = [i for i in range(len(moves) + 1) if positions.get(i, {}).get("visits", 0) >= 100]
    print(f"Full-game positions at 100+ visits: {len(sufficient)}/{len(moves) + 1}")
    if require_complete and len(sufficient) != len(moves) + 1:
        raise RuntimeError("Wait for the App to complete real history analysis.")
    if len(sufficient) != len(moves) + 1:
        return None
    values = [positions[i]["blackWinRate"] for i in range(len(moves) + 1)]
    after = max(range(1, len(moves) + 1), key=lambda i: values[i - 1] - values[i])
    drop = values[after - 1] - values[after]
    span = max(values) - min(values)
    if span < 50 or drop < 15:
        raise RuntimeError("The computed game does not yet have a sufficiently clear reversal.")
    report = {
        "game": "AlphaGo vs Lee Sedol, game 4, 2016-03-13",
        "moves": len(moves), "backend": "CPU", "model": MODEL,
        "minVisits": min(positions[i]["visits"] for i in sufficient),
        "blackWinRateRange": [min(values), max(values)],
        "rangePercentagePoints": span,
        "turningPoint": {"beforeMove": after - 1, "afterMove": after,
                         "beforeWinRate": values[after - 1], "afterWinRate": values[after],
                         "dropPercentagePoints": drop},
        "curve": [{"move": i, "blackWinRate": values[i], "visits": positions[i]["visits"]} for i in sufficient],
    }
    (ROOT / ".local/capture/tutorial/curve-proof.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: value for k, value in report.items() if k != "curve"}, ensure_ascii=False, indent=2))
    return report


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--require-complete", action="store_true")
    args = p.parse_args()
    v = Avd(args.serial, "tutorial")
    summarize(snapshot(v)[0], args.require_complete)
