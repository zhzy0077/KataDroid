#!/usr/bin/env python3
"""Generate locally cached narration. Credentials are read only from the environment."""

import argparse
import base64
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import wave

ROOT = Path(__file__).resolve().parents[1]
MODEL = "mimo-v2.5-tts"
ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
PIPELINE_VERSION = 1


def request_payload(plan, clip):
    return {
        "model": MODEL,
        "messages": [
            {"role": "user", "content": plan["style"] +
             f"这句话请在 {clip['slotSeconds']} 秒左右自然说完。"},
            {"role": "assistant", "content": clip["text"]},
        ],
        "audio": {"format": "wav", "voice": plan["voice"]},
    }


def fingerprint(plan):
    data = {"plan": plan, "model": MODEL, "pipeline": PIPELINE_VERSION}
    return hashlib.sha256(json.dumps(data, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:16]


def safe_message(message):
    return re.sub(r"sk-[a-zA-Z0-9_-]+", "[REDACTED]", str(message))


def speed_factor(duration, slot):
    if duration <= 0 or slot <= 0.1:
        raise ValueError("音频时长或镜头预算无效。")
    factor = max(1.0, duration / (slot - 0.05))
    if factor > 1.35:
        raise ValueError("配音超出镜头预算 35%，请缩短文案或调整语速后重试。")
    return factor


def wav_duration(path):
    with wave.open(str(path), "rb") as audio:
        return audio.getnframes() / audio.getframerate()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def synthesize(payload, api_key):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(ENDPOINT, data=body, headers={
        "Authorization": "Bearer " + api_key,
        "Content-Type": "application/json",
    }, method="POST")
    opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(request, timeout=120) as response:
            raw = response.read(32 * 1024 * 1024 + 1)
    except urllib.error.HTTPError as error:
        detail = safe_message(error.read(2048).decode("utf-8", errors="replace"))
        raise RuntimeError(f"MiMo HTTP {error.code}: {detail}") from None
    if len(raw) > 32 * 1024 * 1024:
        raise RuntimeError("MiMo 响应超出预期大小。")
    data = json.loads(raw)
    try:
        result = base64.b64decode(data["choices"][0]["message"]["audio"]["data"], validate=True)
        with wave.open(io.BytesIO(result), "rb") as audio:
            if audio.getnframes() <= 0:
                raise ValueError("空音频")
    except (KeyError, IndexError, ValueError, wave.Error) as error:
        raise RuntimeError("MiMo 未返回有效的 WAV 音频。") from error
    return result


def ffmpeg(*args):
    result = subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *map(str, args)],
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(safe_message(result.stderr))


def generate(force=False, plan_name="content/teaser.zh-CN.json"):
    if not shutil.which("ffmpeg"):
        raise RuntimeError("请先安装 FFmpeg 并加入 PATH。")
    plan_path = (ROOT / plan_name).resolve()
    if not plan_path.is_relative_to(ROOT / "content"):
        raise ValueError("文案必须位于 content/ 目录。")
    plan = json.loads(plan_path.read_text())
    cache_key = fingerprint(plan)
    raw_dir = ROOT / ".local/audio" / cache_key
    output_dir = ROOT / ".local/public/audio" / cache_key
    raw_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    api_key = os.environ.get("MIMO_API_KEY", "").strip()
    cues = []
    for clip in plan["clips"]:
        raw_path = raw_dir / (clip["id"] + ".wav")
        trimmed_path = raw_dir / (clip["id"] + "-trimmed.wav")
        output_path = output_dir / (clip["id"] + ".wav")
        if force or not raw_path.exists():
            if not api_key:
                raise RuntimeError("请通过环境变量 MIMO_API_KEY 提供 MiMo Key。")
            print("MiMo 合成：" + clip["id"], flush=True)
            raw_path.write_bytes(synthesize(request_payload(plan, clip), api_key))
            raw_path.chmod(0o600)
        else:
            print("复用配音缓存：" + clip["id"], flush=True)
        # Trim only the two ends; preserve pauses inside the spoken sentence.
        trim_filter = ("silenceremove=start_periods=1:start_duration=0.01:start_threshold=-48dB,"
                       "areverse,silenceremove=start_periods=1:start_duration=0.01:"
                       "start_threshold=-48dB,areverse")
        ffmpeg("-i", raw_path, "-af", trim_filter, "-ar", 48000, "-ac", 1,
               "-c:a", "pcm_s16le", trimmed_path)
        factor = speed_factor(wav_duration(trimmed_path), clip["slotSeconds"])
        filters = f"atempo={factor:.8f},loudnorm=I=-16:TP=-1.5:LRA=8,aresample=48000"
        ffmpeg("-i", trimmed_path, "-af", filters, "-t", clip["slotSeconds"],
               "-ar", 48000, "-ac", 1, "-c:a", "pcm_s16le", output_path)
        duration = wav_duration(output_path)
        frames = math.ceil(duration * plan["fps"])
        if clip["from"] + frames > plan["durationInFrames"]:
            raise RuntimeError("配音超出视频总时长。")
        cues.append({
            "id": clip["id"], "from": clip["from"], "durationInFrames": frames,
            "src": f"audio/{cache_key}/{clip['id']}.wav", "text": clip["caption"],
            "durationSeconds": round(duration, 6), "tempo": round(factor, 6),
        })
        print(f"  {duration:.2f}s，时间轴第 {clip['from']} 帧，语速系数 {factor:.3f}", flush=True)
    manifest = {"model": MODEL, "voice": plan["voice"], "fingerprint": cache_key, "cues": cues}
    path = (ROOT / ".local/public" / plan.get("manifest", "audio/manifest.json")).resolve()
    if not path.is_relative_to(ROOT / ".local/public/audio"):
        raise ValueError("配音清单必须位于 audio/ 目录。")
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)
    print("中文旁白已就绪；Key 未写入工程或配音清单。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="重新请求已缓存的配音")
    parser.add_argument("--plan", default="content/teaser.zh-CN.json", help="content/ 中的配音文案")
    args = parser.parse_args()
    try:
        generate(args.force, args.plan)
    except Exception as error:
        print(safe_message(error), file=sys.stderr)
        sys.exit(1)
