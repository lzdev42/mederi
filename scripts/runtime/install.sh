#!/bin/bash
# Installs portable MCP runtimes for the current user. No sudo or shell profile changes.
set -euo pipefail

root=${MEDERI_RUNTIME_HOME:-"$HOME/.mederi/runtime"}
export PATH="/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
export LC_ALL=C
uv_version=0.12.17
node_url=https://nodejs.org/dist/latest-v24.x

if [[ $# -eq 0 ]]; then set -- node python uv; fi
install_node=false install_python=false install_uv=false
for component in "$@"; do
    case "$component" in
        node) install_node=true ;;
        python) install_python=true ;;
        uv) install_uv=true ;;
        *) echo "Unknown runtime: $component" >&2; exit 2 ;;
    esac
done

case "$(uname -m)" in
    x86_64|amd64) node_arch=x64; uv_arch=x86_64 ;;
    arm64|aarch64) node_arch=arm64; uv_arch=aarch64 ;;
    *) echo "Mederi runtime setup supports x64 and ARM64." >&2; exit 1 ;;
esac
case "$(uname -s)" in
    Darwin) node_platform=darwin; uv_platform=apple-darwin ;;
    Linux) node_platform=linux; uv_platform=unknown-linux-gnu ;;
    *) echo "Use install.ps1 on Windows." >&2; exit 1 ;;
esac

mkdir -p "$root"
work=$(mktemp -d "$root/.setup.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT

download() {
    if command -v curl >/dev/null; then
        curl --proto '=https' --tlsv1.2 --fail --silent --show-error --location \
            --connect-timeout 15 --max-time 300 --retry 2 "$1" -o "$2"
    elif command -v wget >/dev/null; then
        wget --https-only --timeout=30 --tries=3 "$1" -O "$2"
    else
        echo "Install curl or wget, then retry Mederi runtime setup." >&2
        exit 1
    fi
}

verify_sha256() {
    local actual
    if command -v sha256sum >/dev/null; then
        actual=$(sha256sum "$1")
    else
        actual=$(shasum -a 256 "$1")
    fi
    [[ ${actual%% *} = "$2" ]] || { echo "Checksum mismatch: $1" >&2; exit 1; }
}

if $install_node; then
    echo "Installing Node.js, npm and npx..."
    download "$node_url/SHASUMS256.txt" "$work/node-checksums"
    read -r checksum archive < <(awk -v suffix="-$node_platform-$node_arch.tar.gz" \
        '$2 ~ (suffix "$") { print $1, $2; exit }' "$work/node-checksums")
    [[ "$archive" =~ ^node-v24\.[0-9]+\.[0-9]+-$node_platform-$node_arch\.tar\.gz$ ]]
    node_version=${archive#node-}
    node_version=${node_version%%-*}
    download "https://nodejs.org/dist/$node_version/$archive" "$work/node.tar.gz"
    verify_sha256 "$work/node.tar.gz" "$checksum"
    tar -xzf "$work/node.tar.gz" -C "$work"
    node_dir="$work/${archive%.tar.gz}"
    "$node_dir/bin/node" --version
    PATH="$node_dir/bin:$PATH" "$node_dir/bin/npm" --version
    PATH="$node_dir/bin:$PATH" "$node_dir/bin/npx" --version
    rm -rf -- "$root/node"
    mv -- "$node_dir" "$root/node"
fi

uv="$root/uv/uv"
if $install_python && ! $install_uv && ! "$uv" --version >/dev/null 2>&1; then
    uv=$(command -v uv || true)
    if [[ -z "$uv" ]]; then install_uv=true; fi
fi
if $install_uv; then
    echo "Installing uv and uvx..."
    target="uv-$uv_arch-$uv_platform"
    archive="$target.tar.gz"
    url="https://github.com/astral-sh/uv/releases/download/$uv_version/$archive"
    download "$url" "$work/$archive"
    download "$url.sha256" "$work/uv-checksum"
    checksum=$(awk 'NR == 1 {print $1}' "$work/uv-checksum")
    verify_sha256 "$work/$archive" "$checksum"
    tar -xzf "$work/$archive" -C "$work"
    "$work/$target/uv" --version
    "$work/$target/uvx" --version
    rm -rf -- "$root/uv"
    mv -- "$work/$target" "$root/uv"
    uv="$root/uv/uv"
fi

if $install_python; then
    echo "Installing Python..."
    UV_PYTHON_INSTALL_DIR="$root/python" UV_PYTHON_BIN_DIR="$root/python-bin" \
        "$uv" --no-config python install 3.13 --managed-python --default
    "$root/python-bin/python3" --version
fi

echo "Mederi runtime setup complete."
