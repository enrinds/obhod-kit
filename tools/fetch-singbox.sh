#!/usr/bin/env bash
set -euo pipefail

VERSION="${SINGBOX_VERSION:-1.14.4}"
REPO="${SINGBOX_REPO:-SagerNet/sing-box}"
DEST="${1:-app/src/main/singboxlibs}"

declare -A ARCH=(
  [arm64-v8a]=arm64
  [armeabi-v7a]=arm
  [x86]=386
  [x86_64]=amd64
)

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  url="https://github.com/${REPO}/releases/download/v${VERSION}/sing-box-${VERSION}-android-${ARCH[$abi]}.tar.gz"
  mkdir -p "${DEST}/${abi}"
  echo "==> ${abi}: ${url}"
  curl -fL --retry 3 --connect-timeout 20 "$url" -o /tmp/sing-box-${abi}.tar.gz
  tar -xzf /tmp/sing-box-${abi}.tar.gz -C /tmp sing-box
  install -m 0755 /tmp/sing-box "${DEST}/${abi}/libsingbox.so"
  rm -f /tmp/sing-box /tmp/sing-box-${abi}.tar.gz
done

echo "==> готово: $(find "$DEST" -name 'libsingbox.so' | wc -l) файлов"
