#!/usr/bin/env python3
"""Capture only KataDroid on an explicitly selected Android Studio AVD."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.zhzy0077.katadroid"
CROP = "1080:2272:0:64"


def normalize_take(source, target, seconds, trim_start, crop=CROP):
    # screenrecord is VFR: an unchanged UI may have no new frame for seconds.
    # Expand held frames BEFORE trimming; input -ss would discard the held image.
    filters = f"fps=30:start_time=0,tpad=stop_mode=clone:stop_duration={seconds + trim_start},trim=start={trim_start}:duration={seconds},setpts=PTS-STARTPTS,crop={crop},format=yuv420p"
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(source), "-map", "0:v:0",
                    "-vf", filters, "-frames:v", str(round(seconds * 30)), "-an", "-c:v", "libx264",
                    "-preset", "fast", "-crf", "17", "-g", "30", "-keyint_min", "30", "-sc_threshold", "0",
                    "-movflags", "+faststart", str(target)], check=True)
    data = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(target)]))
    video = data["streams"][0]
    if int(video["nb_frames"]) != round(seconds * 30) or abs(float(video["start_time"])) > 0.001:
        raise RuntimeError("标准化镜头的帧数或起始时间不正确。")


class Avd:
    def __init__(self, serial, namespace=None):
        if namespace is not None and not re.fullmatch(r"[a-z][a-z0-9-]*", namespace):
            raise ValueError("素材命名空间只能包含小写字母、数字和连字符。")
        self.serial = serial
        devices = subprocess.check_output(["adb", "devices", "-l"], text=True)
        entries = {line.split()[0]: line.split()[1] for line in devices.splitlines()[1:] if len(line.split()) > 1}
        if entries.get(serial) != "device":
            raise RuntimeError("目标不在 adb devices -l 的在线设备列表中。")
        self.base = ["adb", "-s", serial]
        if self.shell("getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("宣传片例行录制只使用 AVD。")
        if "1080x2400" not in self.shell("wm", "size"):
            raise RuntimeError("此拍摄布局需要 1080×2400 的专用 AVD。")
        capture = ROOT / ".local/capture" / (namespace or "")
        self.raw = capture / "raw"
        self.ui = capture / "ui"
        self.public = ROOT / ".local/public" / (namespace or "") / "captures"
        for folder in (self.raw, self.ui, self.public):
            folder.mkdir(parents=True, exist_ok=True)

    def run(self, *args, **kwargs):
        return subprocess.run(self.base + list(map(str, args)), check=True, capture_output=True, **kwargs)

    def shell(self, *args):
        return self.run("shell", *args, text=True).stdout

    def assert_app(self):
        state = self.shell("dumpsys", "activity", "activities")
        resumed = [line for line in state.splitlines() if "topResumedActivity=" in line or "mResumedActivity:" in line]
        if not any(PACKAGE in line for line in resumed):
            raise RuntimeError("前台不是 KataDroid，停止生成可发布素材。")

    def dump(self, name="current"):
        self.shell("uiautomator", "dump", "/sdcard/katadroid-promo-ui.xml")
        path = self.ui / (name + ".xml")
        self.run("pull", "/sdcard/katadroid-promo-ui.xml", path)
        return ET.parse(path)

    @staticmethod
    def bounds(node):
        return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))

    def find(self, text=None, desc=None, prefix=None, tree=None):
        tree = tree if tree is not None else self.dump()
        matches = []
        for node in tree.iter("node"):
            if node.get("package") != PACKAGE:
                continue
            if text is not None and node.get("text") != text:
                continue
            if desc is not None and node.get("content-desc") != desc:
                continue
            if prefix is not None and not node.get("content-desc", "").startswith(prefix):
                continue
            matches.append(node)
        if len(matches) != 1:
            raise RuntimeError(f"需要唯一控件，匹配到 {len(matches)} 个：{text or desc or prefix}")
        return matches[0]

    @staticmethod
    def is_selected(node):
        # Compose's Selected semantics on a Button may be exposed as checked
        # by Android accessibility; other roles use selected directly.
        return node.get("selected") == "true" or node.get("checked") == "true"

    def center(self, node):
        x1, y1, x2, y2 = self.bounds(node)
        return ((x1 + x2) // 2, (y1 + y2) // 2)

    def board_point(self, coordinate, tree=None):
        board = self.find(desc="19 路围棋棋盘", tree=tree)
        x1, y1, x2, _ = self.bounds(board)
        column = "ABCDEFGHJKLMNOPQRST".index(coordinate[0])
        row = 19 - int(coordinate[1:])
        scale = (x2 - x1) / 640
        return (round(x1 + (41 + column * 31) * scale), round(y1 + (41 + row * 31) * scale))

    def chart_point(self, move, last_move=50, tree=None):
        node = self.find(prefix="黑棋胜率走势", tree=tree)
        x1, y1, x2, y2 = self.bounds(node)
        scale = min((x2 - x1) / 300, (y2 - y1) / 98)
        x = x1 + ((x2 - x1) - 300 * scale) / 2 + (30 + 260 * move / last_move) * scale
        return (round(x), (y1 + y2) // 2)

    def candidate_point(self, label="A", tree=None):
        tree = tree if tree is not None else self.dump()
        node = self.find(prefix=f"候选 {label}，", tree=tree)
        coordinate = node.get("content-desc").split("，")[1]
        return self.board_point(coordinate, tree)

    def tap(self, x, y):
        self.shell("input", "tap", x, y)

    def tap_node(self, **kwargs):
        point = self.center(self.find(**kwargs))
        self.tap(*point)
        return point

    def long_press(self, x, y, milliseconds=850):
        self.shell("input", "swipe", x, y, x, y, milliseconds)

    def screenshot(self, name):
        self.assert_app()
        raw = self.raw / (name + ".png")
        raw.write_bytes(self.run("exec-out", "screencap", "-p").stdout)
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(raw), "-vf", "crop=" + CROP,
                        "-frames:v", "1", str(self.public / (name + ".png"))], check=True)
        return self.public / (name + ".png")

    def checkpoint(self, name):
        self.screenshot(name)
        self.dump(name)
        record = self.run("exec-out", "run-as", PACKAGE, "cat", "files/current-record.json").stdout
        (self.raw / (name + "-record.json")).write_bytes(record)

    def record(self, name, seconds, events):
        self.assert_app()
        if seconds <= 0 or seconds > 170:
            raise ValueError("镜头时长无效。")
        device_path = "/sdcard/katadroid-promo-" + name + ".mp4"
        path = self.raw / (name + ".mp4")
        log_path = self.raw / (name + "-screenrecord.log")
        started = time.monotonic()
        warmup = 4
        performed = []
        with log_path.open("w") as log:
            process = subprocess.Popen(self.base + ["shell", "screenrecord", "--bit-rate", "16M",
                "--time-limit", str(int(seconds + warmup + 4)), device_path], stdout=log, stderr=subprocess.STDOUT)
            try:
                for event in events:
                    time.sleep(max(0, started + warmup + event["at"] - time.monotonic()))
                    event = dict(event)
                    if "selector" in event:
                        event["x"], event["y"] = self.center(self.find(**event["selector"]))
                    if "coordinate" in event:
                        event["x"], event["y"] = self.board_point(event["coordinate"])
                    actual = time.monotonic() - started - warmup
                    if event["type"] == "tap":
                        self.tap(event["x"], event["y"])
                    elif event["type"] == "long":
                        self.long_press(event["x"], event["y"], event.get("milliseconds", 850))
                    elif event["type"] == "swipe":
                        self.shell("input", "swipe", event["x"], event["y"], event["toX"], event["toY"], event.get("milliseconds", 700))
                    elif event["type"] == "checkpoint":
                        self.checkpoint(event["name"])
                    elif event["type"] == "back":
                        self.shell("input", "keyevent", "4")
                    else:
                        raise ValueError("未支持的拍摄动作。")
                    performed.append({**event, "actualSeconds": round(actual, 3)})
                process.wait(timeout=seconds + 20)
                finished = time.monotonic()
                if process.returncode:
                    raise RuntimeError("screenrecord 失败，见原始录屏日志。")
            finally:
                if process.poll() is None:
                    self.shell("pkill", "-INT", "screenrecord")
                    process.wait(timeout=10)
        self.run("pull", device_path, path)
        self.assert_app()
        probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_format", "-of", "json", str(path)]))
        raw_duration = float(probe["format"]["duration"])
        # A static tail may have no packets. File duration is therefore NOT the
        # wall-clock recording duration and must not be used to infer startup.
        trim_start = warmup
        if finished - started < seconds + warmup:
            raise RuntimeError("录制进程提前结束，请检查编码器日志。")
        normalize_take(path, self.public / (name + ".mp4"), seconds, trim_start)
        (self.raw / (name + "-actions.json")).write_text(json.dumps({"trimStartSeconds": trim_start, "recordWallSeconds": round(finished - started, 3), "rawDurationSeconds": raw_duration, "events": performed}, ensure_ascii=False, indent=2) + "\n")
        print(f"已录制 {name}：{seconds} 秒，原速。", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("action", choices=["dump", "screenshot"])
    parser.add_argument("--name", default="current")
    parser.add_argument("--namespace", default=None)
    args = parser.parse_args()
    device = Avd(args.serial, args.namespace)
    if args.action == "screenshot":
        print(device.screenshot(args.name))
    else:
        for node in device.dump(args.name).iter("node"):
            if node.get("package") == PACKAGE and (node.get("text") or node.get("content-desc")):
                print(node.get("bounds"), node.get("text"), node.get("content-desc"))
