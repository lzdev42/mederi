#!/bin/bash
# Install system runtimes on Linux: sudo /bin/bash install.sh [node python git uv]
set -euo pipefail

die() { echo "$*" >&2; exit 1; }

if [[ $# -eq 0 ]]; then set -- node python git uv; fi
for component in "$@"; do
    case "$component" in
        node|python|git|uv) ;;
        *) die "Unknown runtime: $component. Choose node, python, git or uv." ;;
    esac
done
# Resolve privileged package tools from system directories.
export PATH=/usr/sbin:/usr/bin:/sbin:/bin LC_ALL=C
[[ "$(uname -s)" == Linux ]] || die 'This installer is for Linux.'
[[ $EUID -eq 0 ]] || die 'Run this script with sudo: sudo /bin/bash install.sh [node python git uv]'
umask 022
system_path="/usr/local/bin:$PATH"
manager=none
for candidate in apt-get dnf yum zypper pacman; do
    if command -v "$candidate" >/dev/null; then manager=$candidate; break; fi
done
echo "Linux package manager: $manager"

repo_ready=false
prepare_repo() {
    if $repo_ready; then return; fi
    case "$manager" in
        apt-get) apt-get -o APT::Update::Error-Mode=any update || die 'Could not refresh apt package metadata.' ;;
        dnf) dnf --refresh makecache || die 'Could not refresh dnf package metadata.' ;;
        yum) yum makecache || die 'Could not refresh yum package metadata.' ;;
        zypper) zypper --non-interactive refresh || die 'Could not refresh zypper package metadata.' ;;
        # Use the existing sync database. Refreshing it without upgrading the
        # whole system can lead to an unsupported partial Arch upgrade.
        pacman|none) ;;
    esac
    repo_ready=true
}

repo_has() {
    local package=$1 output status
    prepare_repo
    case "$manager" in
        apt-get)
            output=$(apt-cache policy "$package") || die "Could not query apt for $package."
            awk '$1 == "Candidate:" && $2 != "(none)" { found=1 } END { exit !found }' <<< "$output"
            ;;
        dnf)
            if rpm -q "$package" >/dev/null 2>&1; then return 0; fi
            output=$(dnf -q repoquery --available --queryformat '%{name}' "$package") ||
                die "Could not query dnf for $package."
            [[ -n "$output" ]]
            ;;
        yum)
            if rpm -q "$package" >/dev/null 2>&1; then return 0; fi
            if output=$(yum -q list available "$package" 2>&1); then return 0; fi
            [[ "$output" == *"No matching Packages to list"* ]] || die "$output"
            return 1
            ;;
        zypper)
            if zypper --non-interactive search --type package --match-exact "$package" >/dev/null; then return 0
            else status=$?
            fi
            [[ $status -eq 104 ]] || die "Could not query zypper for $package (exit $status)."
            return 1
            ;;
        pacman)
            if output=$(pacman -Si "$package" 2>&1); then return 0; fi
            [[ "$output" == *"package '$package' was not found"* ]] || die "$output"
            return 1
            ;;
        none) return 1 ;;
    esac
}

repo_install() {
    prepare_repo
    case "$manager" in
        apt-get) apt-get install -y "$@" ;;
        dnf|yum) "$manager" install -y "$@" ;;
        zypper) zypper --non-interactive install "$@" ;;
        pacman) pacman -S --needed --noconfirm "$@" ;;
        none) die "No supported Linux package manager to install $*." ;;
    esac
}

system_command() { PATH="$system_path" "$@"; }
available() { system_command "$@" >/dev/null 2>&1; }
has_node() { available node --version && available npm --version && available npx --version; }
has_python() { available python3 -E -S -c 'import sys; sys.exit(sys.version_info[0] != 3)'; }

work=
trap 'if [[ -n "$work" ]]; then rm -rf -- "$work"; fi' EXIT
prepare_download() {
    if ! command -v curl >/dev/null; then
        repo_install curl ca-certificates
    fi
    if [[ -z "$work" ]]; then work=$(mktemp -d /tmp/mederi-runtimes.XXXXXXXX); fi
}

download() {
    local minimum_speed=${3:-1024}
    curl -q --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
        --connect-timeout 5 --max-time 300 --speed-limit "$minimum_speed" --speed-time 10 "$1" -o "$2"
}

download_verified() {
    local digest=$1 destination=$2 official=$3 mirror=$4 source minimum_speed
    local -a sources=("$official" "$mirror" "$official")
    local index
    for index in "${!sources[@]}"; do
        source=${sources[index]}
        minimum_speed=524288
        # Give a slow official connection a chance if the mirror could not deliver.
        if [[ "$index" == 2 ]]; then minimum_speed=1024; fi
        echo "Downloading from $source"
        if download "$source" "$destination" "$minimum_speed"; then
            if printf '%s  %s\n' "$digest" "$destination" | sha256sum -c --status; then
                return
            fi
            echo "SHA-256 mismatch: $source" >&2
        fi
        rm -f -- "$destination"
    done
    die "Could not download a verified copy of ${destination##*/}."
}

install_node_release() {
    # https://nodejs.org/dist/v24.22.3/SHASUMS256.txt
    local version=v24.22.3 arch digest archive release prefix binary target
    case "$(uname -m)" in
        x86_64) arch=x64; digest=061ec3e3062115705b56b51fa9cc330ab50d34b6969387d4e4b1a50c6809acc2 ;;
        aarch64|arm64) arch=arm64; digest=261af2af250dfd7e9f25cf151d1b2713a0adf8f10a9ad116c4947f68627affe8 ;;
        ppc64le) arch=ppc64le; digest=ba4b26ade1f7b3f5ba6756856815de58e353746004e7e46cc838243a6cbf3299 ;;
        s390x) arch=s390x; digest=d8f84397c1478c0ed0c1b29343626ed0f5f1a0985df0c5975c30f0c03836631f ;;
        *) die "No supported official Node.js Linux binary for $(uname -m)." ;;
    esac
    release="node-$version-linux-$arch"
    archive="$release.tar.gz"
    prefix="/usr/local/lib/nodejs/$release"
    for binary in node npm npx; do
        target="/usr/local/bin/$binary"
        if [[ -e "$target" || -L "$target" ]]; then
            [[ -L "$target" && "$(readlink "$target")" == "$prefix/bin/$binary" ]] ||
                die "$target already exists. Resolve the conflict before installing Node.js."
        fi
    done

    prepare_download
    download_verified "$digest" "$work/$archive" \
        "https://nodejs.org/dist/$version/$archive" \
        "https://mirrors.huaweicloud.com/nodejs/$version/$archive"
    tar --no-same-owner --no-same-permissions -xzf "$work/$archive" -C "$work"
    "$work/$release/bin/node" --version >/dev/null ||
        die 'The official Node.js binary cannot run on this Linux system.'
    install -d /usr/local/bin /usr/local/lib/nodejs
    if [[ ! -e "$prefix" ]]; then mv -T "$work/$release" "$prefix"; fi
    for binary in node npm npx; do
        target="/usr/local/bin/$binary"
        if [[ ! -L "$target" ]]; then ln -s "$prefix/bin/$binary" "$target"; fi
    done
}

install_node() {
    if has_node; then return; fi
    local node_package=nodejs npm_package=''
    if [[ "$manager" == zypper ]]; then node_package=nodejs-default; fi
    if repo_has "$node_package"; then
        case "$manager" in
            dnf|yum)
                # Fedora and RHEL package npm under different names.
                if repo_has nodejs-npm; then npm_package=nodejs-npm
                elif repo_has npm; then npm_package=npm
                fi
                ;;
            zypper) if repo_has npm-default; then npm_package=npm-default; fi ;;
            *) if repo_has npm; then npm_package=npm; fi ;;
        esac
        if [[ -n "$npm_package" ]]; then
            repo_install "$node_package" "$npm_package"
        else
            repo_install "$node_package"
        fi
        if has_node; then return; fi
    fi
    install_node_release
    has_node || die 'Node.js, npm and npx must be available after installation.'
}

install_python_release() {
    # Source release and SHA-256 from https://www.python.org/downloads/release/python-31410/
    local version=3.14.10 digest=9e4996c19ae5a3df31f10c37723661f71d3455d73bd005f79df7098e5cc13192
    local archive="Python-$version.tgz" prefix="/usr/local/lib/python/Python-$version"
    local interpreter="$prefix/bin/python3.14" target=/usr/local/bin/python3
    local jobs
    if [[ -e "$target" || -L "$target" ]]; then
        [[ -L "$target" && "$(readlink "$target")" == "$interpreter" ]] ||
            die "$target already exists. Resolve the conflict before installing Python."
    fi
    echo 'Installing the official CPython source release. Building it may take several minutes.'
    case "$manager" in
        apt-get) repo_install build-essential pkg-config libssl-dev zlib1g-dev libbz2-dev libffi-dev liblzma-dev libreadline-dev libsqlite3-dev ;;
        dnf|yum) repo_install gcc make pkgconfig openssl-devel zlib-devel bzip2-devel libffi-devel xz-devel readline-devel sqlite-devel ;;
        zypper) repo_install gcc make pkg-config libopenssl-devel zlib-devel libbz2-devel libffi-devel xz-devel readline-devel sqlite3-devel ;;
        pacman) repo_install gcc make pkgconf openssl zlib bzip2 libffi xz readline sqlite ;;
    esac
    command -v cc >/dev/null && command -v make >/dev/null ||
        die 'Building Python requires a C compiler and make.'
    prepare_download
    download_verified "$digest" "$work/$archive" \
        "https://www.python.org/ftp/python/$version/$archive" \
        "https://mirrors.huaweicloud.com/python/$version/$archive"
    tar --no-same-owner --no-same-permissions -xzf "$work/$archive" -C "$work"
    jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
    [[ "$jobs" =~ ^[1-9][0-9]*$ ]] || jobs=1
    if (( jobs > 4 )); then jobs=4; fi
    (
        cd "$work/Python-$version"
        ./configure --prefix="$prefix" --with-ensurepip=install
        make -j "$jobs"
        ./python -I -c 'import bz2, ctypes, lzma, sqlite3, ssl, zlib'
        make altinstall
    )
    "$interpreter" -I -c 'import bz2, ctypes, lzma, sqlite3, ssl, zlib, pip'
    install -d /usr/local/bin
    if [[ ! -L "$target" ]]; then ln -s "$interpreter" "$target"; fi
}

install_python() {
    local python_package=python3
    if has_python; then return; fi
    if [[ "$manager" == pacman ]]; then python_package=python; fi
    if repo_has "$python_package"; then
        repo_install "$python_package"
        if has_python; then return; fi
    fi
    install_python_release
    has_python || die 'Python 3 must be available after installation.'
}

install_git() {
    if available git --version; then return; fi
    repo_has git || die 'Git is required. Install it with your distribution package manager and rerun this script.'
    repo_install git
    available git --version || die 'Git must be available after installation.'
}

install_uv() {
    if available uv --version && available uvx --version; then return; fi
    if repo_has uv; then
        repo_install uv
        if available uv --version && available uvx --version; then return; fi
    fi
    echo 'Installing uv and uvx from Astral.'
    prepare_download
    download 'https://astral.sh/uv/install.sh' "$work/uv-install.sh"
    UV_UNMANAGED_INSTALL=/usr/local/bin /bin/sh "$work/uv-install.sh"
    available uv --version && available uvx --version || die 'uv and uvx must be available after installation.'
}

for component in "$@"; do
    case "$component" in
        node) install_node ;;
        python) install_python ;;
        git) install_git ;;
        uv) install_uv ;;
    esac
done

echo 'System runtime setup complete.'
