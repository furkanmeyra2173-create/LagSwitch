#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT_DIR="$ROOT/app/libs"
OUT_AAR="$OUT_DIR/netstack.aar"
export GOTOOLCHAIN=auto
if [[ -z "$ANDROID_NDK_HOME" ]]; then echo "Set ANDROID_NDK_HOME to an Android NDK"; exit 1; fi
cd "$HERE"
go version
MOBILE_VERSION="v0.0.0-20260709172247-6129f5bee9d5"
go mod tidy
GOFLAGS=-mod=mod go build ./...
go get "golang.org/x/mobile@$MOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
export PATH="$(go env GOPATH)/bin:$PATH"
mkdir -p "$OUT_DIR"
GOFLAGS=-mod=mod gomobile bind -target=android/arm64 -androidapi 26 -javapkg=com.anonh4x -o "$OUT_AAR" .
test -s "$OUT_AAR"
echo "Built $OUT_AAR"
