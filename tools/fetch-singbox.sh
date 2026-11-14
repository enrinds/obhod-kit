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

mkdir -p "$DEST"

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  url="https://github.com/${REPO}/releases/download/v${VERSION}/sing-box-${VERSION}-android-${ARCH[$abi]}.tar.gz"
  tmp="$(mktemp -d)"
  echo "==> ${abi}: ${url}"

  curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 \
    "$url" -o "${tmp}/sb.tar.gz"

  tar -xzf "${tmp}/sb.tar.gz" -C "${tmp}" --strip-components=1

  if [ ! -f "${tmp}/sing-box" ]; then
    echo "ОШИБКА: в архиве нет sing-box (${abi})" >&2
    ls -la "${tmp}" >&2
    rm -rf "${tmp}"
    exit 1
  fi

  mkdir -p "${DEST}/${abi}"
  install -m 0755 "${tmp}/sing-box" "${DEST}/${abi}/libsingbox.so"
  rm -rf "${tmp}"

  echo "    -> ${DEST}/${abi}/libsingbox.so ($(du -h "${DEST}/${abi}/libsingbox.so" | cut -f1))"
done

echo "==> готово: $(find "$DEST" -name 'libsingbox.so' | wc -l) файлов в $DEST"
