"""Download the pinned LiteRT Qualcomm JIT plugins for optional NPU inference."""
import hashlib
import io
from pathlib import Path
import urllib.request
import zipfile

URL = "https://github.com/google-ai-edge/LiteRT/releases/download/v2.2.0/litert_npu_runtime_libraries_jit.zip"
SHA256 = "d6d160104f110e690f1c9b0c54ab3855af1a12e405dcc6e644dd7e3e1955aac4"
DEST = Path(__file__).resolve().parents[1] / "app/src/main/jniLibs/arm64-v8a"
SDK_URL = "https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.47.0.260601/v2.47.0.260601.zip"
SDK_LIBS = {
    "libQnnIr.so": "79043536d4ac324782c953c9092066d3418ec88626ac2384b2e81883a3f8f644",
    "libQnnSaver.so": "d26d8002da0fbe829f58370465dc40b4e5a0fc2c2830c0608972817ac5835026",
}


class RemoteZipFile(io.RawIOBase):
    """Read only ZIP directory + selected files, instead of the entire 2.3 GB SDK."""
    def __init__(self):
        self.position = 0
        request = urllib.request.Request(SDK_URL, headers={"Range": "bytes=0-0"})
        with urllib.request.urlopen(request, timeout=60) as response:
            if response.status != 206:
                raise RuntimeError("SDK server does not support HTTP Range requests")
            self.size = int(response.headers["Content-Range"].split("/")[-1])

    def seekable(self):
        return True

    def seek(self, offset, whence=0):
        base = (0, self.position, self.size)[whence]
        self.position = base + offset
        if self.position < 0:
            raise ValueError("Negative seek")
        return self.position

    def tell(self):
        return self.position

    def read(self, size=-1):
        end = self.size if size < 0 else min(self.size, self.position + size)
        if end <= self.position:
            return b""
        request = urllib.request.Request(SDK_URL, headers={"Range": f"bytes={self.position}-{end - 1}"})
        with urllib.request.urlopen(request, timeout=60) as response:
            expected = f"bytes {self.position}-{end - 1}/{self.size}"
            if response.status != 206 or response.headers.get("Content-Range") != expected:
                raise RuntimeError("SDK server returned an unexpected byte range")
            data = response.read()
        if len(data) != end - self.position:
            raise RuntimeError("Truncated SDK download")
        self.position = end
        return data


def main():
    data = urllib.request.urlopen(URL, timeout=60).read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise RuntimeError("LiteRT archive checksum mismatch")
    archive = zipfile.ZipFile(io.BytesIO(data))
    DEST.mkdir(parents=True, exist_ok=True)
    for name in ("libLiteRtCompilerPlugin_Qualcomm.so", "libLiteRtDispatch_Qualcomm.so"):
        member = f"qualcomm_runtime_v79/src/main/jni/arm64-v8a/{name}"
        (DEST / name).write_bytes(archive.read(member))
        print(f"Prepared {name}")
    # qnn-runtime's AAR omits these JIT compiler dependencies. Match the SDK
    # version used by LiteRT's official fetch_qualcomm_library.sh exactly.
    missing = [name for name, digest in SDK_LIBS.items()
               if not (DEST / name).exists() or hashlib.sha256((DEST / name).read_bytes()).hexdigest() != digest]
    if missing:
        with zipfile.ZipFile(RemoteZipFile()) as sdk:
            for name in missing:
                data = sdk.read(f"qairt/2.47.0.260601/lib/aarch64-android/{name}")
                if hashlib.sha256(data).hexdigest() != SDK_LIBS[name]:
                    raise RuntimeError(f"SDK checksum mismatch: {name}")
                (DEST / name).write_bytes(data)
                print(f"Prepared {name}")


if __name__ == "__main__":
    main()
