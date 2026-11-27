#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SCRIPT_DIR}/../src-tauri/lib"
mkdir -p "${LIB_DIR}"

OS_NAME="$(uname -s)"

if [[ "${OS_NAME}" == "Darwin" ]]; then
  echo "Detected macOS."
  CANDIDATES=(
    "/opt/homebrew/lib/libmpv.2.dylib"
    "/usr/local/lib/libmpv.2.dylib"
  )
  FOUND=""
  for path in "${CANDIDATES[@]}"; do
    if [[ -f "${path}" ]]; then
      FOUND="${path}"
      break
    fi
  done

  if [[ -z "${FOUND}" ]]; then
    echo "libmpv.2.dylib not found. Install it via Homebrew:"
    echo "  brew install mpv"
    exit 1
  fi

  ln -sf "${FOUND}" "${LIB_DIR}/libmpv.2.dylib"
  echo "Linked ${FOUND} -> ${LIB_DIR}/libmpv.2.dylib"
elif [[ "${OS_NAME}" == "Linux" ]]; then
  echo "Detected Linux."
  CANDIDATES=(
    "/usr/lib/x86_64-linux-gnu/libmpv.so.2"
    "/usr/lib/aarch64-linux-gnu/libmpv.so.2"
    "/usr/lib/libmpv.so.2"
    "/usr/lib64/libmpv.so.2"
  )
  FOUND=""
  for path in "${CANDIDATES[@]}"; do
    if [[ -f "${path}" ]]; then
      FOUND="${path}"
      break
    fi
  done

  if [[ -z "${FOUND}" ]]; then
    echo "libmpv.so.2 not found. Install it with your package manager:"
    echo "  Ubuntu/Debian: sudo apt-get install -y libmpv2 libmpv-dev"
    echo "  Arch Linux:    sudo pacman -S mpv"
    echo "  Fedora:        sudo dnf install mpv-libs mpv-libs-devel"
    exit 1
  fi

  ln -sf "${FOUND}" "${LIB_DIR}/libmpv.so.2"
  echo "Linked ${FOUND} -> ${LIB_DIR}/libmpv.so.2"
else
  echo "Unsupported OS: ${OS_NAME}. Use setup-libmpv.ps1 on Windows."
  exit 1
fi
