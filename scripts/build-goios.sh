#!/usr/bin/env bash
#
# Cross-compiles go-ios for Android (arm64) and drops the binary where Gradle
# picks it up as a native library, so that the APK ships
#   jniLibs/arm64-v8a/libgoios.so
# The file is really a statically linked Go executable (not a shared library);
# the app extracts it at install time and runs it from nativeLibraryDir.
set -euo pipefail

GO_IOS_VERSION="${GO_IOS_VERSION:-v1.3.2}"
GO_IOS_REPO="${GO_IOS_REPO:-https://github.com/danielpaulus/go-ios.git}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${GO_IOS_SRC_DIR:-$ROOT/third_party/go-ios}"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"

if [ ! -f "$SRC_DIR/go.mod" ]; then
  mkdir -p "$(dirname "$SRC_DIR")"
  rm -rf "$SRC_DIR"
  git clone --depth 1 --branch "$GO_IOS_VERSION" "$GO_IOS_REPO" "$SRC_DIR"
fi

mkdir -p "$OUT_DIR"

cd "$SRC_DIR"
export CGO_ENABLED=0
export GOOS=linux
export GOARCH=arm64
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"

go build -buildvcs=false -trimpath -ldflags="-s -w" -o "$OUT_DIR/libgoios.so" .

echo "built: $OUT_DIR/libgoios.so"
ls -lh "$OUT_DIR/libgoios.so"
if command -v file >/dev/null 2>&1; then
  file "$OUT_DIR/libgoios.so"
fi
