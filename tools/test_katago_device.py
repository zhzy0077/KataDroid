"""Run the installed device test and audit delegation without uninstalling the app."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, help="Explicit device serial from adb devices -l")
    parser.add_argument("--suite", choices=("probe", "engine", "models"), default="probe",
                        help="Raw probe, JNI engine lifecycle, or both model benchmarks/accuracy (no UI tests)")
    parser.add_argument("--vendor", choices=("qualcomm", "mediatek"), default="qualcomm",
                        help="Native delegation evidence required for the selected NPU runtime")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "models")
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    work = args.output_dir
    work.mkdir(parents=True, exist_ok=True)
    test_class = {"engine": "NpuEngineIntegrationTest", "models": "NpuModelBenchmarkTest",
                  "probe": "NpuNetworkProbeTest#kataGoB6"}[args.suite]
    prefix = {"engine": "engine-device", "models": "models-device", "probe": "device"}[args.suite]
    filters = ["KataDroidModel:I", "KataDroidEngine:I", "litert:I", "tflite:I", "qnn:W",
               "NeuronAdapter:I", "Neuron:I", "NeuroPilot:I", "neuron:I", "apusys:I", "apuware_hidl:I",
               "TestRunner:I", "AndroidRuntime:E", "libc:F", "*:S"]
    # Capture while the test runs: native compilation can fill the log buffer quickly.
    with tempfile.TemporaryFile(mode="w+") as live:
        logger = subprocess.Popen(adb + ["logcat", "-T", "1", "-v", "brief"] + filters,
                                  stdout=live, stderr=subprocess.PIPE, text=True)
        timed_out = False
        result = None
        try:
            result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                                     f"com.example.katadroid.{test_class}",
                                     "com.example.katadroid.test/androidx.test.runner.AndroidJUnitRunner"],
                                    capture_output=True, text=True, timeout=180)
            report = result.stdout + result.stderr
        except subprocess.TimeoutExpired as error:
            # Keep partial instrumentation and delegation evidence on hangs too.
            def decoded(value):
                return value.decode(errors="replace") if isinstance(value, bytes) else value or ""
            report = decoded(error.stdout) + decoded(error.stderr) + "\nTIMEOUT after 180 seconds\n"
            timed_out = True
            # Killing the local adb command does not stop remote instrumentation.
            # End only this test app so a timed-out audit cannot keep running NPU work.
            try:
                stop = subprocess.run(adb + ["shell", "am", "force-stop", "com.example.katadroid"],
                                      capture_output=True, text=True, timeout=15)
                report += f"Test app timeout cleanup returned {stop.returncode}\n"
            except subprocess.SubprocessError as cleanup_error:
                report += f"Test app timeout cleanup failed: {cleanup_error}\n"
        finally:
            logger.terminate()
            logger.communicate(timeout=10)
        (work / f"{prefix}-test.log").write_text(report)
        print(report)
        pid_match = re.search(r"KATADROID_PID=(\d+)", report)
        assert pid_match, "Test PID missing"
        pid = pid_match.group(1)
        live.seek(0)
        # Retain only our test process, not other applications' logs.
        log = "".join(line for line in live if re.search(r"\(\s*" + pid + r"\)", line))
    # A terminated streaming logcat can lose its final buffered output. A
    # finite, PID-filtered dump exits normally and flushes those final records.
    tail = subprocess.run(adb + ["logcat", "-d", "--pid=" + pid, "-v", "brief"] + filters,
                          capture_output=True, text=True, timeout=15).stdout
    lines = log.splitlines(keepends=True)
    if not lines:
        log = tail
    else:
        for size in range(min(20, len(lines)), 0, -1):
            suffix = "".join(lines[-size:])
            overlap = tail.rfind(suffix)
            if overlap >= 0:
                log += tail[overlap + len(suffix):]
                break
    (work / f"{prefix}-native.log").write_text(log)
    assert not timed_out, "NPU test timed out; partial test and native logs were preserved"
    result.check_returncode()
    assert "OK (1 test)" in report and "FAILURES!!!" not in report, "Instrumentation did not pass"
    assert "BEGIN backend=NPU" in log, "NPU log marker missing; audit is inconclusive"
    npu_log = log.split("BEGIN backend=NPU", 1)[1]
    selected = re.findall(r"selected (\d+) ops, from a total of (\d+) ops", npu_log)
    # Cached contexts omit the compilation trace; when present, every op must be selected.
    assert all(count == total for count, total in selected), selected
    replacements = re.findall(r"Replacing (\d+) out of (\d+) node\(s\) with delegate \(([^)]+)\)", npu_log)
    assert replacements, "No delegate assignment evidence"
    assert all(count == total and name == "DispatchDelegate" for count, total, name in replacements), replacements
    if args.vendor == "qualcomm":
        assert "Found qnn graph:" in npu_log, "QNN graph evidence missing"
    else:
        assert "NPU vendor=MEDIATEK" in log, "MediaTek runtime selection missing"
        assert "Loaded NeuronAdapter shared library" in npu_log, "Neuron runtime evidence missing"
        assert "Neuron SDK version check passed" in npu_log, "Native Neuron graph loading evidence missing"
        assert "Ahwb" in npu_log or "DmaBuf" in npu_log, "MediaTek hardware buffer evidence missing"
    assert "PASS NPU" in npu_log or "PASS NPU" in report, "NPU completion marker missing"
    assert "TfLiteXNNPackDelegate" not in npu_log, "CPU delegate detected during NPU run"
    if args.suite == "engine":
        assert npu_log.count("READY backend=NPU") == 3, "Expected reference, search and restarted NPU runtimes"
        assert (npu_log.count("CLOSED backend=NPU") == 3 or report.count("NPU_SESSION_CLOSED ") == 3), "Not all NPU runtimes were released"
        assert len(replacements) == 3, "Missing delegate evidence for an NPU runtime"
    elif args.suite == "models":
        # b10 raw probe + b10 official output reference + two benchmarks + b6 reopen.
        assert npu_log.count("READY backend=NPU") == 4, "Expected four JNI model runtimes"
        assert (npu_log.count("CLOSED backend=NPU") == 4 or report.count("NPU_SESSION_CLOSED ") == 4), "Not all model runtimes were released"
        assert len(replacements) == 5, "Missing complete delegation evidence for a model/probe runtime"
        assert report.count("NPU_BENCHMARK ") == 2, "Both models must finish a real search benchmark"
    print(f"PASS: numerical test and native {args.vendor} DispatchDelegate audit", replacements)


if __name__ == "__main__":
    main()
