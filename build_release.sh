#!/usr/bin/env bash
set -euo pipefail

# ==========================================
# Mederi Release Build Script (Compose Multiplatform)
# Usage: ./build_release.sh [--skip-sign] [--output <dir>] [--no-clean]
# Windows: run in Git Bash. Each invocation builds packages for the current OS/architecture.
# ==========================================

SKIP_SIGN=false
SKIP_OBSCURA=false
OUTPUT_DIR=""
CLEAN_BUILD=true
APP_NAME="${APP_NAME:-Mederi}"

while [[ $# -gt 0 ]]; do
    case $1 in
        --name|-n)
            [[ $# -ge 2 && -n "$2" ]] || { echo "Missing --name value"; exit 1; }
            APP_NAME="$2"
            shift 2
            ;;
        --skip-sign)
            SKIP_SIGN=true
            shift
            ;;
        --skip-obscura)
            SKIP_OBSCURA=true
            shift
            ;;
        --no-clean)
            CLEAN_BUILD=false
            shift
            ;;
        --output|-o)
            [[ $# -ge 2 && -n "$2" ]] || { echo "Missing --output value"; exit 1; }
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --name, -n NAME     Application display name (default: Mederi)"
            echo "  --skip-sign         Skip code signing (useful for CI without certs)"
            echo "  --skip-obscura      Skip downloading Obscura browser binary"
            echo "  --no-clean          Skip cleaning build artifacts"
            echo "  --output, -o DIR    Output directory (default: ./release)"
            echo "  --help, -h        Show this help"
            echo "Builds desktop, server-bundled (JVM + Web UI), server-api (no JVM/Web UI)."
            echo "Requires a JBR 25 SDK with JCEF. Server is linked without JCEF from the same JDK."
            echo "Windows requires Git Bash."
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

[[ "$APP_NAME" =~ ^[a-zA-Z0-9._-]+$ && "$APP_NAME" != "." && "$APP_NAME" != ".." ]] || {
    echo "Invalid application name: use letters, digits, dots, underscores or hyphens"; exit 1;
}

SCRIPT_DIR=$(cd "$(dirname "$0")"; pwd)
OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
    Darwin*) PLATFORM="macos" ;;
    Linux*)  PLATFORM="linux" ;;
    CYGWIN*|MINGW*|MSYS*) PLATFORM="windows" ;;
    *) echo "❌ Unsupported OS: $OS"; exit 1 ;;
esac

case "$ARCH" in
    arm64|aarch64) ARCH_NAME="arm64" ;;
    x86_64|amd64)  ARCH_NAME="x64" ;;
    *) echo "❌ Unsupported Architecture: $ARCH"; exit 1 ;;
esac

echo "=============================================="
echo "🎯 Target: $PLATFORM-$ARCH_NAME"
echo "=============================================="

# 定位项目目录与 gradlew（脚本与 gradlew 同处仓库根）
PROJECT_DIR="$SCRIPT_DIR"
if [[ ! -f "$PROJECT_DIR/gradlew" ]]; then
    echo "❌ 未找到 gradlew！"
    exit 1
fi

if [[ -z "$OUTPUT_DIR" ]]; then
    OUTPUT_DIR="$SCRIPT_DIR/release"
fi
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd "$OUTPUT_DIR" && pwd)

# ==========================================
# 1. 确保使用 JBR (with JCEF)
# ==========================================
JBR_ROOT=""

if [[ "$PLATFORM" == "macos" ]]; then
    # 优先检测已有的 JAVA_HOME
    if [[ -n "${JAVA_HOME:-}" ]]; then
        if [[ -d "$(dirname "${JAVA_HOME:-}")/Frameworks/cef_server.app" ]]; then
            JBR_ROOT="$(cd "$(dirname "${JAVA_HOME:-}")/.." && pwd)"
        elif [[ -d "$JAVA_HOME/Frameworks/cef_server.app" ]]; then
            JBR_ROOT="${JAVA_HOME:-}"
        elif [[ -d "$JAVA_HOME/../Contents/Frameworks/cef_server.app" ]]; then
            JBR_ROOT="$(cd "$JAVA_HOME/.." && pwd)"
        fi
    fi

    if [[ -z "$JBR_ROOT" ]]; then
        # 扫描本地安装的 jbrsdk_jcef 目录
        for candidate in \
            "$HOME/Library/Java/JavaVirtualMachines"/jbrsdk_jcef* \
            "/Library/Java/JavaVirtualMachines"/jbrsdk_jcef*; do
            if [[ -d "$candidate/Contents/Frameworks/cef_server.app" ]]; then
                JBR_ROOT="$candidate"
                export JAVA_HOME="$candidate/Contents/Home"
                break
            fi
        done
    fi

fi

# Linux/Windows 使用显式 JAVA_HOME；macOS 可自动发现本机 JBR JCEF。
[[ -n "${JAVA_HOME:-}" ]] || { echo "❌ 请设置 JAVA_HOME 为 JBR 25 SDK with JCEF"; exit 1; }
if [[ "$PLATFORM" == "windows" ]]; then
    JAVA_HOME=$(cygpath -u "$JAVA_HOME")
fi
export JAVA_HOME
[[ -f "$JAVA_HOME/jmods/jcef.jmod" ]] || {
    echo "❌ JAVA_HOME 必须指向带 JCEF 的 JBR SDK: $JAVA_HOME"; exit 1;
}
RELEASE_JAVA_HOME="$JAVA_HOME"
if [[ "$PLATFORM" == "windows" ]]; then
    RELEASE_JAVA_HOME=$(cygpath -m "$JAVA_HOME")
fi
echo "☕ Release JBR: $JAVA_HOME"

# ==========================================
# 2. 执行 Gradle 打包
# ==========================================
cd "$PROJECT_DIR"

GRADLE_ARGS=(--no-configuration-cache "-Pmederi.releaseJavaHome=$RELEASE_JAVA_HOME")
if [[ "$CLEAN_BUILD" == "true" ]]; then
    echo "🧹 清理旧构建..."
    ./gradlew :app:desktopApp:clean :server:clean "${GRADLE_ARGS[@]}"
fi

echo "🚀 构建 Desktop 和两种 Server 发布目录..."
./gradlew :app:desktopApp:createReleaseDistributable \
    :server:installServerBundled :server:installServerApi "${GRADLE_ARGS[@]}"

# 所有整理操作在仓库 temp 下进行，避免污染 Gradle 输出与临时目录外溢。
mkdir -p "$PROJECT_DIR/temp"
STAGING=$(mktemp -d "$PROJECT_DIR/temp/release.XXXXXX")
trap 'rm -rf "$STAGING"' EXIT

archive_folder() {
    local folder="$1"
    local name="$(basename "$folder")"
    if [[ "$PLATFORM" == "windows" ]]; then
        # JDK jar 生成标准 ZIP，Git Bash 无需额外安装 zip/7-Zip。
        "$JAVA_HOME/bin/jar" --create --no-manifest \
            --file "$(cygpath -m "$OUTPUT_DIR/$name.zip")" \
            -C "$(cygpath -m "$(dirname "$folder")")" "$name"
    else
        tar -czf "$OUTPUT_DIR/$name.tar.gz" -C "$(dirname "$folder")" "$name"
    fi
}

# ==========================================
# 3. 产物整理与平台后处理
# ==========================================
echo "📦 整理发布产物..."

OBSCURA_DIR="$STAGING/obscura"
prepare_obscura() {
    if [[ "$SKIP_OBSCURA" == "true" ]]; then
        echo "⏭️ 跳过下载 Obscura (--skip-obscura)"
        return 0
    fi

    echo "🌐 下载最新版 Obscura ($PLATFORM-$ARCH_NAME)..."
    local obs_os="$PLATFORM"
    local obs_arch="$ARCH_NAME"
    if [[ "$obs_arch" == "arm64" ]]; then obs_arch="aarch64"; fi
    if [[ "$obs_arch" == "x64" ]]; then obs_arch="x86_64"; fi

    local ext="tar.gz"
    if [[ "$PLATFORM" == "windows" ]]; then ext="zip"; fi

    local asset_name="obscura-${obs_arch}-${obs_os}-stealth.${ext}"
    local cache_dir="$PROJECT_DIR/temp/obscura-cache"
    mkdir -p "$cache_dir" "$OBSCURA_DIR"

    # 若环境中有 GITHUB_TOKEN（如 CI 运行），加入认证 Header 提高优先级和速率限制
    local auth_header=""
    local token="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
    if [[ -n "$token" ]]; then
        auth_header="Authorization: Bearer $token"
    fi

    # 优先通过 GitHub API 获取最新 tag，直连静态资产，减少 302 重定向层级避免网关 504
    local latest_tag=""
    if [[ -n "$auth_header" ]]; then
        latest_tag=$(curl -fsSL --connect-timeout 10 -H "$auth_header" "https://api.github.com/repos/h4ckf0r0day/obscura/releases/latest" 2>/dev/null | grep '"tag_name":' | head -n 1 | sed -E 's/.*"tag_name":[[:space:]]*"([^"]+)".*/\1/')
    else
        latest_tag=$(curl -fsSL --connect-timeout 10 "https://api.github.com/repos/h4ckf0r0day/obscura/releases/latest" 2>/dev/null | grep '"tag_name":' | head -n 1 | sed -E 's/.*"tag_name":[[:space:]]*"([^"]+)".*/\1/')
    fi

    local download_url=""
    if [[ -n "$latest_tag" ]]; then
        download_url="https://github.com/h4ckf0r0day/obscura/releases/download/${latest_tag}/${asset_name}"
        echo "  检测到最新版本: $latest_tag"
    else
        download_url="https://github.com/h4ckf0r0day/obscura/releases/latest/download/${asset_name}"
    fi

    local archive_file="$cache_dir/$asset_name"
    local tmp_archive="$cache_dir/${asset_name}.tmp.$$"

    local has_valid_cache=false
    if [[ -f "$archive_file" && -s "$archive_file" ]]; then
        if [[ "$ext" == "zip" ]]; then
            if command -v unzip >/dev/null 2>&1; then
                unzip -tq "$archive_file" >/dev/null 2>&1 && has_valid_cache=true
            elif command -v python3 >/dev/null 2>&1; then
                python3 -c "import zipfile, sys; sys.exit(0 if zipfile.ZipFile('$archive_file').testzip() is None else 1)" 2>/dev/null && has_valid_cache=true
            else
                has_valid_cache=true
            fi
        else
            tar -tzf "$archive_file" >/dev/null 2>&1 && has_valid_cache=true
        fi
    fi

    echo "  正在拉取最新版: $download_url"
    local download_success=false

    # 尝试下载到临时文件，增加重试与全部错误自动重试
    local curl_exit=0
    if [[ -n "$auth_header" ]]; then
        curl -fSL --connect-timeout 30 --retry 5 --retry-delay 2 --retry-all-errors -H "$auth_header" "$download_url" -o "$tmp_archive" || curl_exit=$?
    else
        curl -fSL --connect-timeout 30 --retry 5 --retry-delay 2 --retry-all-errors "$download_url" -o "$tmp_archive" || curl_exit=$?
    fi

    if [[ $curl_exit -eq 0 && -f "$tmp_archive" ]]; then
        local valid_download=false
        if [[ "$ext" == "zip" ]]; then
            if command -v unzip >/dev/null 2>&1; then
                unzip -tq "$tmp_archive" >/dev/null 2>&1 && valid_download=true
            elif command -v python3 >/dev/null 2>&1; then
                python3 -c "import zipfile, sys; sys.exit(0 if zipfile.ZipFile('$tmp_archive').testzip() is None else 1)" 2>/dev/null && valid_download=true
            else
                valid_download=true
            fi
        else
            tar -tzf "$tmp_archive" >/dev/null 2>&1 && valid_download=true
        fi

        if [[ "$valid_download" == "true" ]]; then
            mv -f "$tmp_archive" "$archive_file"
            download_success=true
            echo "  ✓ 最新版下载完成并校验成功"
        else
            echo "  ⚠️ 下载文件校验未通过（可能返回了网关错误页或数据残缺）"
            rm -f "$tmp_archive"
        fi
    else
        rm -f "$tmp_archive"
    fi

    if [[ "$download_success" == "false" ]]; then
        if [[ "$has_valid_cache" == "true" ]]; then
            echo "  ⚠️ 远程下载最新版失败（网络超时/504），自动降级使用本地已有完整缓存: $asset_name"
        else
            echo "❌ 下载 Obscura 失败且本地无有效缓存: $download_url"
            echo "💡 提示: 请检查网络连接/代理，或手动将 $asset_name 放入 $cache_dir"
            exit 1
        fi
    fi

    echo "📦 解压 Obscura 到 $OBSCURA_DIR..."
    if [[ "$PLATFORM" == "windows" ]]; then
        if command -v unzip >/dev/null 2>&1; then
            unzip -q -o "$archive_file" -d "$OBSCURA_DIR"
        elif command -v python3 >/dev/null 2>&1; then
            python3 -c "import zipfile; zipfile.ZipFile('$archive_file').extractall('$OBSCURA_DIR')"
        else
            "$JAVA_HOME/bin/jar" -xf "$archive_file"
        fi
    else
        tar -xzf "$archive_file" -C "$OBSCURA_DIR"
        chmod +x "$OBSCURA_DIR/obscura"* 2>/dev/null || true
    fi
}

deploy_obscura() {
    local target_res_dir="$1"
    if [[ "$SKIP_OBSCURA" == "true" || ! -d "$OBSCURA_DIR" ]]; then
        return 0
    fi
    mkdir -p "$target_res_dir"
    cp -R "$OBSCURA_DIR/"* "$target_res_dir/"
    chmod +x "$target_res_dir/obscura"* 2>/dev/null || true
}

prepare_obscura

case "$PLATFORM" in
    macos)
        APP_DIR=$(find "$PROJECT_DIR/app/desktopApp/build/compose/binaries/main-release/app" -maxdepth 1 -name "*.app" 2>/dev/null | head -n 1)
        if [[ -z "$APP_DIR" || ! -d "$APP_DIR" ]]; then
            echo "❌ 未找到打包产物 .app 目录"
            exit 1
        fi
        
        BUNDLE_NAME="$(basename "$APP_DIR")"
        FINAL_APP="$OUTPUT_DIR/$BUNDLE_NAME"
        rm -rf "$FINAL_APP"
        cp -R "$APP_DIR" "$FINAL_APP"

        # macOS 下 jpackage 默认不会把 JBR 的 Contents/Frameworks（cef_server.app）打进去
        # 如果有 JBR JCEF，补拷进 runtime 并签名
        FRAMEWORKS_SRC=""
        if [[ -n "$JBR_ROOT" && -d "$JBR_ROOT/Contents/Frameworks" ]]; then
            FRAMEWORKS_SRC="$JBR_ROOT/Contents/Frameworks"
        elif [[ -n "$JBR_ROOT" && -d "$JBR_ROOT/Frameworks" ]]; then
            FRAMEWORKS_SRC="$JBR_ROOT/Frameworks"
        fi

        if [[ -n "$FRAMEWORKS_SRC" ]]; then
            echo "💉 补全 CEF Frameworks 到 App Runtime..."
            CEF_DST="$FINAL_APP/Contents/runtime/Contents/Frameworks"
            mkdir -p "$CEF_DST"
            cp -R "$FRAMEWORKS_SRC/"* "$CEF_DST/"
        fi

        echo "💉 部署 Obscura 到 App 资源目录..."
        deploy_obscura "$FINAL_APP/Contents/Resources"

        if [[ "$SKIP_SIGN" == "false" ]]; then
            echo "🔏 执行 Ad-hoc 签名..."
            codesign --remove-signature "$FINAL_APP" 2>/dev/null || true
            find "$FINAL_APP" -name "*.dylib" -exec codesign --force -s - {} \; 2>/dev/null || true
            find "$FINAL_APP" -name "*.jnilib" -exec codesign --force -s - {} \; 2>/dev/null || true
            
            if [[ -f "$FINAL_APP/Contents/Resources/obscura" ]]; then
                codesign --force -s - "$FINAL_APP/Contents/Resources/obscura" 2>/dev/null || true
            fi
            if [[ -f "$FINAL_APP/Contents/Resources/obscura-worker" ]]; then
                codesign --force -s - "$FINAL_APP/Contents/Resources/obscura-worker" 2>/dev/null || true
            fi

            CEF_FW_DIR="$FINAL_APP/Contents/runtime/Contents/Frameworks"
            if [[ -d "$CEF_FW_DIR/cef_server.app" ]]; then
                find "$CEF_FW_DIR" -name "*.dylib" -exec codesign --force -s - {} \; 2>/dev/null || true
                find "$CEF_FW_DIR/cef_server.app" -path "*/Contents/MacOS/*" -type f -exec codesign --force -s - {} \; 2>/dev/null || true
                find "$CEF_FW_DIR" -name "*.framework" -exec codesign --force -s - {} \; 2>/dev/null || true
                find "$CEF_FW_DIR/cef_server.app" -name "*.app" -exec codesign --force -s - {} \; 2>/dev/null || true
                codesign --force -s - "$CEF_FW_DIR/cef_server.app" 2>/dev/null || true
            fi
            codesign --force -s - "$FINAL_APP"
        fi

        echo "✓ macOS App: $FINAL_APP"
        ;;

    linux|windows)
        GRADLE_APP_PARENT="$PROJECT_DIR/app/desktopApp/build/compose/binaries/main-release/app"
        APP_DIR=$(find "$GRADLE_APP_PARENT" -maxdepth 1 -mindepth 1 -type d | head -n 1)
        [[ -n "$APP_DIR" ]] || { echo "❌ 未找到 Desktop 产物"; exit 1; }
        DESKTOP_DIR="$STAGING/${APP_NAME}-desktop-$PLATFORM-$ARCH_NAME"
        cp -R "$APP_DIR" "$DESKTOP_DIR"
        # jlink 不保证保留 JCEF 的全部 native 辅助文件；绿色版直接带完整 JBR。
        if [[ "$PLATFORM" == "linux" ]]; then
            DESKTOP_RUNTIME="$DESKTOP_DIR/lib/runtime"
        else
            DESKTOP_RUNTIME="$DESKTOP_DIR/runtime"
        fi
        rm -rf "$DESKTOP_RUNTIME"
        mkdir -p "$(dirname "$DESKTOP_RUNTIME")"
        cp -R "$JAVA_HOME" "$DESKTOP_RUNTIME"
        # 裁剪仅编译期使用的 SDK 文件（jmods/、C 头文件 include/ 与开发工具），精简绿色运行时
        rm -rf "$DESKTOP_RUNTIME/jmods" "$DESKTOP_RUNTIME/include" "$DESKTOP_RUNTIME/demo" "$DESKTOP_RUNTIME/man"
        rm -f "$DESKTOP_RUNTIME/bin/javac"* "$DESKTOP_RUNTIME/bin/javadoc"* "$DESKTOP_RUNTIME/bin/jlink"* "$DESKTOP_RUNTIME/bin/jpackage"* "$DESKTOP_RUNTIME/bin/jshell"* 2>/dev/null || true

        echo "💉 部署 Obscura 到 Desktop 资源目录..."
        deploy_obscura "$DESKTOP_DIR/resources"

        archive_folder "$DESKTOP_DIR"
        ;;
esac

# Gradle Application 插件的 bin/server(.bat) 保留标准参数转发与 JAVA_OPTS 支持。
for variant in bundled api; do
    SERVER_DIR="$STAGING/${APP_NAME}-server-$variant-$PLATFORM-$ARCH_NAME"
    cp -R "$PROJECT_DIR/server/build/install/server-$variant" "$SERVER_DIR"

    echo "💉 部署 Obscura 到 Server ($variant) 资源目录..."
    deploy_obscura "$SERVER_DIR/resources"
    if [[ "$variant" == "bundled" ]]; then
        cat > "$SERVER_DIR/run.sh" <<'EOF'
#!/usr/bin/env sh
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export JAVA_HOME="$HERE/runtime"
exec "$HERE/bin/server" "$@"
EOF
        # Windows 分发使用 CRLF，且固定指向包内运行时，不受系统 JAVA_HOME 影响。
        printf '@echo off\r\nsetlocal\r\nset "JAVA_HOME=%%~dp0runtime"\r\ncall "%%~dp0bin\server.bat" %%*\r\nexit /b %%errorlevel%%\r\n' > "$SERVER_DIR/run.bat"
    else
        cat > "$SERVER_DIR/run.sh" <<'EOF'
#!/usr/bin/env sh
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$HERE/bin/mederi-server" "$@"
EOF
        printf '@echo off\r\ncall "%%~dp0bin\mederi-server.bat" %%*\r\nexit /b %%errorlevel%%\r\n' > "$SERVER_DIR/run.bat"
    fi
    chmod +x "$SERVER_DIR/run.sh"
    archive_folder "$SERVER_DIR"
done

echo ""
echo "=============================================="
echo "✅ 构建完成！产物输出于: $OUTPUT_DIR"
echo "=============================================="
ls -lh "$OUTPUT_DIR"
