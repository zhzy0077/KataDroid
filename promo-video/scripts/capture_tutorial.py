#!/usr/bin/env python3
"""Capture the 58-second portrait review lesson on an explicitly selected AVD."""
import argparse
import hashlib
import json
import time
from capture_avd import Avd, ROOT, PACKAGE
from tutorial_source import game_moves, snapshot, summarize


def tap(at, label, x, y):
    return {"at": at, "type": "tap", "x": x, "y": y, "label": label}


def publish_touches(v):
    manifest = {}
    for path in sorted(v.raw.glob("*-actions.json")):
        data = json.loads(path.read_text())
        manifest[path.name.removesuffix("-actions.json")] = [
            {"at": event["actualSeconds"], "type": event["type"], "x": event["x"], "y": event["y"] - 64,
             "label": event.get("label", ""),
             "duration": event.get("milliseconds", 850) / 1000 if event["type"] == "long" else .9}
            for event in data["events"] if "x" in event and "y" in event
        ]
    (v.public / "touches.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")


def coordinate(point):
    return "ABCDEFGHJKLMNOPQRST"[point % 19] + str(19 - point // 19)


def prepare(v):
    report = summarize(snapshot(v)[0], require_complete=True)
    texts = [n.get("text") for n in v.dump().iter("node")]
    if "已暂停" not in texts:
        v.tap_node(desc="KataGo 引擎")
    v.find(text="已暂停")
    before = report["turningPoint"]["beforeMove"]
    after = report["turningPoint"]["afterMove"]
    v.tap(*v.chart_point(before, last_move=180))
    time.sleep(.4)
    v.find(text=str(before))
    v.screenshot("hero")
    tree = v.dump()
    original = coordinate(game_moves()[after - 1][1])
    label = next(label for label in "ABC" if v.find(prefix="候选 " + label + "，", tree=tree).get("content-desc").split("，")[1] != original)
    point = v.candidate_point(label, tree)
    candidate = v.find(prefix="候选 " + label + "，", tree=tree).get("content-desc").split("，")[1]
    v.long_press(*point)
    exit_point = v.center(v.find(text="退出预览"))
    preview_record = json.loads(v.run("exec-out", "run-as", PACKAGE, "cat", "files/current-record.json").stdout)
    v.tap(*exit_point)
    if preview_record["previewStep"] != 7 or len(preview_record["previewMoves"]) != 7:
        raise RuntimeError("The updated App and chosen candidate must provide a seven-move preview.")
    lesson = {"beforeMove": before, "afterMove": after, "candidateLabel": label,
              "candidateCoordinate": candidate, "candidatePoint": point,
              "exitPoint": exit_point, "originalCoordinate": original,
              "previewMoves": preview_record["previewMoves"]}
    (ROOT / ".local/capture/tutorial/lesson.json").write_text(json.dumps(lesson, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(lesson, ensure_ascii=False))


def capture(v, stage):
    lesson_path = ROOT / ".local/capture/tutorial/lesson.json"
    lesson = json.loads(lesson_path.read_text()) if lesson_path.exists() else {}
    if stage == "prepare":
        prepare(v)
        return
    if stage == "analysis":
        tree = v.dump()
        v.find(text="已暂停", tree=tree)
        v.screenshot("imported")
        v.record("analysis", 8, [
            tap(1.3, "开启 KataGo 分析", *v.center(v.find(desc="KataGo 引擎", tree=tree))),
        ])
    elif stage == "review":
        tree = v.dump()
        v.record("review", 14, [
            tap(3.2, "点击曲线转折", *v.chart_point(lesson["afterMove"], last_move=180, tree=tree)),
            tap(8.2, "上一手，对照转折前", *v.center(v.find(desc="上一手", tree=tree))),
            tap(10.2, "下一手，再看转折后", *v.center(v.find(desc="下一手", tree=tree))),
            tap(12.2, "回到转折前", *v.center(v.find(desc="上一手", tree=tree))),
        ])
        v.find(text=str(lesson["beforeMove"]))
    elif stage == "preview":
        if len(lesson.get("previewMoves", [])) != 7:
            raise RuntimeError("Run prepare with the seven-move App before recording.")
        v.find(text=str(lesson["beforeMove"]))
        before = snapshot(v)[1]
        point = lesson["candidatePoint"]
        v.record("preview", 10, [
            {"at": 1.5, "type": "long", "x": point[0], "y": point[1], "milliseconds": 1000, "label": "长按候选点"},
            {"at": 3.3, "type": "checkpoint", "name": "preview-seven"},
            tap(7.5, "退出预览", *lesson["exitPoint"]),
        ])
        v.find(text=str(lesson["beforeMove"]))
        after = snapshot(v)[1]
        checkpoint = json.loads((v.raw / "preview-seven-record.json").read_text())
        if checkpoint["previewStep"] != 7 or checkpoint["previewMoves"] != lesson["previewMoves"]:
            raise RuntimeError("The recorded preview must show all seven real PV moves.")
        if "previewLabel" in after or any(before[key] != after[key] for key in ("nodes", "selected", "leaf")):
            raise RuntimeError("Exiting preview must preserve the original record and selection.")
        proof = {"steps": 7, "moves": checkpoint["previewMoves"], "recordUnchanged": True,
                 "captureSha256": hashlib.sha256((v.public / "preview.mp4").read_bytes()).hexdigest()}
        (ROOT / ".local/capture/tutorial/preview-proof.json").write_text(json.dumps(proof, indent=2) + "\n")
    elif stage == "branches":
        v.find(text=str(lesson["beforeMove"]))
        original_state = snapshot(v)[1]
        selected = next((n for n in original_state["nodes"] if n["id"] == original_state["selected"]), None)
        if len(original_state["nodes"]) != len(game_moves()) + 1 or selected is None or selected["move"] != lesson["beforeMove"]:
            raise RuntimeError("The saved game must be the complete main line at the lesson position. Navigate away and back, then retry.")
        v.tap(*lesson["candidatePoint"])
        v.tap_node(text="变化树")
        tree = v.dump()
        main_point = v.center(v.find(prefix=f"第 {lesson['afterMove']} 手，{lesson['originalCoordinate']}", tree=tree))
        alternate_point = v.center(v.find(prefix=f"第 {lesson['afterMove']} 手，{lesson['candidateCoordinate']}", tree=tree))
        # Rehearsal is rolled back before recording a genuinely new branch.
        v.shell("am", "force-stop", PACKAGE)
        v.run("shell", "run-as", PACKAGE, "sh", "-c", "'cat > files/current-record.json'",
              input=json.dumps(original_state, ensure_ascii=False).encode())
        v.shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        time.sleep(1)
        v.find(text=str(lesson["beforeMove"]))
        tab = v.center(v.find(text="变化树"))
        v.record("branches", 10, [
            tap(.9, "试下新的走法", *lesson["candidatePoint"]),
            tap(4.7, "打开变化树", *tab),
            tap(6.9, "点选原来的主线", *main_point),
            tap(8.55, "切换到新分支", *alternate_point),
        ])
        node = v.find(prefix=f"第 {lesson['afterMove']} 手，{lesson['candidateCoordinate']}")
        if not v.is_selected(node):
            raise RuntimeError("The recorded final branch was not selected.")
    elif stage in ("import-menu", "export-menu"):
        menu = v.center(v.find(desc="更多选项"))
        v.record(stage, 4.5 if stage == "import-menu" else 4, [tap(.5, "打开三点菜单", *menu)])
    else:
        raise ValueError("Unknown tutorial stage")
    v.screenshot(stage + "-end")
    publish_touches(v)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("stage", choices=["prepare", "analysis", "review", "preview", "branches", "import-menu", "export-menu"])
    args = p.parse_args()
    capture(Avd(args.serial, "tutorial"), args.stage)
