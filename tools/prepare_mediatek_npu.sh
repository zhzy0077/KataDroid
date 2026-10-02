#!/usr/bin/env bash
# Build the patched LiteRT 2.2.0 MediaTek JIT plugins on Linux x86_64.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$root/.local/mediatek-npu-build"
dest="$root/app/src/main/jniLibs/arm64-v8a"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
test -d "$ANDROID_NDK_HOME/toolchains"
test "$(uname -sm)" = 'Linux x86_64'
mkdir -p "$work" "$dest"

download() {
  local url="$1" path="$2" digest="$3"
  if ! printf '%s  %s\n' "$digest" "$path" | sha256sum --check --status 2>/dev/null; then
    curl --fail --location --retry 3 --output "$path" "$url"
  fi
  printf '%s  %s\n' "$digest" "$path" | sha256sum --check
}

download 'https://codeload.github.com/google-ai-edge/LiteRT/tar.gz/refs/tags/v2.2.0' \
  "$work/litert-source.tar.gz" '6d2ce16738199adc5a3cdde76c3c6a6dac636d3b52a1d7790ea524fb0d59f7fc'
download 'https://github.com/bazelbuild/bazel/releases/download/7.7.0/bazel-7.7.0-linux-x86_64' \
  "$work/bazel" 'fe7e799cbc9140f986b063e06800a3d4c790525075c877d00a7112669824acbf'
chmod +x "$work/bazel"
source_dir="$work/LiteRT-2.2.0"
if [[ ! -d "$source_dir" ]]; then
  tar -xzf "$work/litert-source.tar.gz" -C "$work"
fi
patch_file="$root/tools/patches/litert-2.2.0-mediatek-runtime-selection.patch"
if git apply --check --unsafe-paths --directory="$source_dir" "$patch_file" 2>/dev/null; then
  git apply --unsafe-paths --directory="$source_dir" "$patch_file"
else
  # A rerun may reuse the already patched source; any other mismatch is fatal.
  git apply --check --reverse --unsafe-paths --directory="$source_dir" "$patch_file"
fi

# Bazel 7's SDK discovery expects integer API directory names, while the app's
# SDK package uses android-37.0. Use a separate symlink view without changing SDK.
sdk_view="$work/bazel-sdk"
mkdir -p "$sdk_view/platforms"
ln -sfn "$ANDROID_HOME/platforms/android-37.0" "$sdk_view/platforms/android-37"
for directory in build-tools platform-tools tools; do
  if [[ -d "$ANDROID_HOME/$directory" ]]; then
    ln -sfn "$ANDROID_HOME/$directory" "$sdk_view/$directory"
  fi
done
test -f "$sdk_view/platforms/android-37/android.jar"
export ANDROID_HOME="$sdk_view"

# Bazel's Java downloader also needs JVM proxy settings when a local proxy is set.
proxy_args=()
if [[ -n "${NPU_BAZEL_OUTPUT_ROOT:-}" ]]; then
  proxy_args+=("--output_user_root=$NPU_BAZEL_OUTPUT_ROOT")
fi
if [[ -n "${HTTPS_PROXY:-${https_proxy:-}}" ]]; then
  mapfile -t downloaded_proxy_args < <(python3 - <<'PY'
import os
from urllib.parse import urlparse
proxy = urlparse(os.environ.get('HTTPS_PROXY') or os.environ['https_proxy'])
for scheme in ('http', 'https'):
    print(f'--host_jvm_args=-D{scheme}.proxyHost={proxy.hostname}')
    print(f'--host_jvm_args=-D{scheme}.proxyPort={proxy.port or 80}')
PY
  )
  proxy_args+=("${downloaded_proxy_args[@]}")
fi
cd "$source_dir"
"$work/bazel" "${proxy_args[@]}" build --config=android_arm64 -c opt \
  --repo_env=HERMETIC_PYTHON_VERSION=3.12 --jobs="${NPU_BUILD_JOBS:-2}" \
  //litert/vendors/mediatek/compiler:compiler_plugin_so \
  //litert/vendors/mediatek/dispatch:dispatch_api_so
for pair in \
  'compiler/libLiteRtCompilerPlugin_MediaTek.so' \
  'dispatch/libLiteRtDispatch_MediaTek.so'; do
  install -m 644 "bazel-bin/litert/vendors/mediatek/$pair" "$dest/${pair##*/}"
done
echo 'Prepared patched MediaTek compiler and dispatch plugins.'
