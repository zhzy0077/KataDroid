# Models and performance

[User guide](android-ui.md) · [Architecture](katago-integration.md) · [Conversion](model-conversion.md)

## Model choice

| Network | Size | Intended tradeoff |
| --- | --- | --- |
| b6c96 | 6 blocks, 96 channels, about 1.03M parameters | Lower inference cost and faster interactive analysis |
| b10c128 | 10 blocks, 128 channels, about 2.99M parameters | Larger network; stronger at equal visits in upstream g170 testing |

Both are older official g170 networks, paired with verified 19×19 version-8
TFLite exports. They are useful lightweight mobile baselines, not the latest or
strongest KataGo networks. Equal visits do not mean equal thinking time or equal
strength across networks. See the upstream [g170 network notes](https://katagoarchive.org/g170/neuralnets/README.txt).

The app allows switching these bundled pairs. It does not yet accept arbitrary
model files; newer architectures may need a different output contract and export
path. No retraining was needed for these two conversions.

## What visits/s measures

The engine page runs one 32-visit warm-up followed by fresh searches of an empty
board, an eight-move opening and a position after a capture. Each measured
position starts with the search tree and NN cache cleared. Choose 100, 500 or
1,000 visits **per position**. The benchmark's budget is separate from the game's
search setting.

Throughput is **total actual visits / total search time**, not an average of
individual rates, and not `1000 / neural inference latency`. Progressive visit
counts are cumulative and counted once. Initialization and warm-up are reported
separately and excluded from throughput. This is the single-threaded search used
by the app; serialization, JNI and synchronous inference contribute to its cost.

Models and actual CPU/NPU backends have separate saved results. Leaving the page,
cancelling or switching models invalidates an incomplete run. Auto results record
the backend that actually opened, including CPU fallback.

<img src="images/benchmark-en.png" width="360" alt="Completed b6c96 CPU benchmark with total and per-position visits per second" />

This screenshot shows a separate AVD run using Auto, which selected CPU. Its
125.4 visits/s result illustrates the report; the recorded baseline below comes
from a different run.

## Measured development baseline

Measured on 2026-10-02 with a debug build, Android Studio API 37 x86_64 AVD,
16 KiB pages. Each model completed 3 × 100 measured visits, after warm-up.
These are **host-dependent simulator measurements**, not phone CPU figures.

| Model / actual backend | visits/s | Search total | Initialization | Warm-up |
| --- | ---: | ---: | ---: | ---: |
| b6c96 / CPU | 121.8 | 2.463 s | 59.1 ms | 271.9 ms |
| b10c128 / CPU | 44.8 | 6.693 s | 134.6 ms | 708.0 ms |

Sanitized per-position counters and timings: [avd-cpu.json](benchmarks/avd-cpu.json).
The two models' raw outputs and official postprocessed values were checked
against reference positions, separately from throughput measurements.

## Prior physical NPU work

The only physical SoC tested so far is **Snapdragon 8 Elite** with Android 17,
LiteRT 2.2.0 and QNN 2.47.0. This is validation coverage, not a device allowlist.
No new physical-device testing was performed during repository preparation.

A previous exploratory b6c96 NPU benchmark completed 3 × 500 visits at **665.0
visits/s** (2.256 s search, 71.8 ms initialization and 69.1 ms warm-up). Its
sanitized counters are in [prior-npu-b6.json](benchmarks/prior-npu-b6.json).
The same test process was subsequently killed during b10 testing: **the combined
both-model benchmark/lifecycle/delegation audit did not complete**. This single
b6 measurement must not be presented as a passing full-suite or sustained-load
result. Separate earlier b6 numerical/search/cancellation/reopen and native
QNN delegation checks did pass. b10 raw/postprocessed NPU precision checks passed,
but a complete b10 NPU benchmark and lifecycle audit remains unverified.

Do not compare the AVD table with that phone as a CPU-vs-NPU speedup experiment:
they are different environments and visit budgets. Temperature, background load,
compiler caches and search position affect throughput. Sustained thermal behavior,
battery use and playing strength have not been measured.

## 中文说明

引擎页面的 visits/s 来自三个固定局面的真实搜索访问数除以搜索时间，初始化和预热另计。
它不是单次网络延迟的倒数，也不能直接当作棋力。两个模型、CPU 和 NPU 的结果分开保存。

本次模拟器中 b6c96 / CPU 约 121.8 visits/s，b10c128 / CPU 约 44.8 visits/s，均为每局面 100 visits。
这些数字依赖宿主机，不代表手机 CPU 性能。此前 8 Elite 上完成的一次 b6 NPU 探索测速为
665.0 visits/s、每局面 500 visits；随后 b10 测试进程被系统结束，因此不能声称完整双模型 NPU
专项已通过。本轮没有重新使用真机，也尚未进行持续温升、功耗和棋力评测。
