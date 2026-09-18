#!/usr/bin/env bash
set -e

# ==========================================
# Mederi Release Build Script (Compose Multiplatform)
# Usage: ./build_release.sh [--skip-sign] [--output <dir>] [--no-clean]
# ==========================================

SKIP_SIGN=false
OUTPUT_DIR=""
CLEAN_BUILD=true
APP_NAME="${APP_NAME:-Mederi}"

while [[ $# -gt 0 ]]; do
    case $1 in
        --name|-n)
            APP_NAME="$2"
            shift 2
            ;;
        --skip-sign)
            SKIP_SIGN=true
            shift
            ;;
        --no-clean)
            CLEAN_BUILD=false
            shift
            ;;
        --output|-o)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --name, -n NAME   Application display name (default: Mederi)"
            echo "  --skip-sign       Skip code signing (useful for CI without certs)"
            echo "  --no-clean        Skip cleaning build artifacts"
            echo "  --output, -o DIR  Output directory (default: ./release)"
            echo "  --help, -h        Show this help"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

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

# 定位项目目录与 gradlew（兼容根目录或子目录）
if [[ -f "$SCRIPT_DIR/gradlew" ]]; then
    PROJECT_DIR="$SCRIPT_DIR"
elif [[ -f "$SCRIPT_DIR/mederi/gradlew" ]]; then
    PROJECT_DIR="$SCRIPT_DIR/mederi"
else
    echo "❌ 未找到 gradlew！"
    exit 1
fi

if [[ -z "$OUTPUT_DIR" ]]; then
    OUTPUT_DIR="$SCRIPT_DIR/release"
fi
mkdir -p "$OUTPUT_DIR"

# ==========================================
# 1. 确保使用 JBR (with JCEF)
# ==========================================
JBR_ROOT=""

if [[ "$PLATFORM" == "macos" ]]; then
    # 优先检测已有的 JAVA_HOME
    if [[ -n "$JAVA_HOME" ]]; then
        if [[ -d "$(dirname "$JAVA_HOME")/Frameworks/cef_server.app" ]]; then
            JBR_ROOT="$(cd "$(dirname "$JAVA_HOME")/.." && pwd)"
        elif [[ -d "$JAVA_HOME/Frameworks/cef_server.app" ]]; then
            JBR_ROOT="$JAVA_HOME"
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

    if [[ -n "$JBR_ROOT" ]]; then
        echo "☕ 命中 JBR JCEF: $JBR_ROOT"
        echo "☕ JAVA_HOME:     $JAVA_HOME"
    else
        echo "⚠️  未找到 JBR JCEF，使用系统默认 Java: $(which java)"
    fi
else
    # Linux / Windows: 直接复用当前配置好的 JAVA_HOME / java
    if [[ -z "$JAVA_HOME" ]]; then
        echo "⚠️  JAVA_HOME 未显式设置，将使用 PATH 中的 Java: $(which java)"
    else
        echo "☕ JAVA_HOME: $JAVA_HOME"
    fi
fi

# ==========================================
# 2. 执行 Gradle 打包
# ==========================================
cd "$PROJECT_DIR"

if [[ "$CLEAN_BUILD" == "true" ]]; then
    echo "🧹 清理旧构建..."
    ./gradlew :app:desktopApp:clean --no-configuration-cache
fi

echo "🚀 执行 Gradle Desktop 打包..."
case "$PLATFORM" in
    macos)
        ./gradlew :app:desktopApp:packageReleaseDistributionForCurrentOS --no-configuration-cache
        ;;
    linux)
        ./gradlew :app:desktopApp:createReleaseDistributable --no-configuration-cache
        ;;
    windows)
        ./gradlew :app:desktopApp:packageReleaseDistributionForCurrentOS --no-configuration-cache
        ;;
esac

# ==========================================
# 3. 产物整理与平台后处理
# ==========================================
echo "📦 整理发布产物..."

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

        if [[ "$SKIP_SIGN" == "false" ]]; then
            echo "🔏 执行 Ad-hoc 签名..."
            codesign --remove-signature "$FINAL_APP" 2>/dev/null || true
            find "$FINAL_APP" -name "*.dylib" -exec codesign --force -s - {} \; 2>/dev/null || true
            find "$FINAL_APP" -name "*.jnilib" -exec codesign --force -s - {} \; 2>/dev/null || true
            
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

        # 创建 DMG
        DMG_NAME="${APP_NAME}-$ARCH_NAME.dmg"
        DMG_PATH="$OUTPUT_DIR/$DMG_NAME"
        DMG_TEMP="$OUTPUT_DIR/dmg_temp"
        rm -rf "$DMG_TEMP" "$DMG_PATH"
        mkdir -p "$DMG_TEMP"
        cp -R "$FINAL_APP" "$DMG_TEMP/"
        ln -s /Applications "$DMG_TEMP/Applications"

        hdiutil create -volname "$APP_NAME" -srcfolder "$DMG_TEMP" -ov -format UDZO "$DMG_PATH"
        rm -rf "$DMG_TEMP"
        echo "✓ DMG 创建完成: $DMG_NAME"
        ;;

    linux)
        echo "🐧 打包 Linux 绿色便携包 (tar.gz，内含完整 JBR JCEF)..."
        GRADLE_APP_PARENT="$PROJECT_DIR/app/desktopApp/build/compose/binaries/main-release/app"
        APP_DIR=$(find "$GRADLE_APP_PARENT" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | head -n 1)
        if [[ -z "$APP_DIR" || ! -d "$APP_DIR" ]]; then
            echo "❌ 未找到构建产物目录"
            exit 1
        fi

        FOLDER_NAME="$(basename "$APP_DIR")"
        TAR_NAME="${APP_NAME}-$ARCH_NAME.tar.gz"

        # 在应用根目录创建一个简易启动脚本，方便解压后直接在根目录运行
        EXEC_BIN=$(find "$APP_DIR/bin" -maxdepth 1 -type f -executable 2>/dev/null | head -n 1)
        if [[ -n "$EXEC_BIN" ]]; then
            BIN_REL="bin/$(basename "$EXEC_BIN")"
            cat << EOF > "$APP_DIR/run.sh"
#!/usr/bin/env bash
HERE="\$(cd "\$(dirname "\$0")"; pwd)"
exec "\$HERE/$BIN_REL" "\$@"
EOF
            chmod +x "$APP_DIR/run.sh"
        fi

        cd "$GRADLE_APP_PARENT"
        tar -czf "$OUTPUT_DIR/$TAR_NAME" "$FOLDER_NAME"
        cd "$PROJECT_DIR"
        echo "✓ 绿色便携包创建完成: $OUTPUT_DIR/$TAR_NAME"
        ;;

    windows)
        RELEASE_BIN="$PROJECT_DIR/app/desktopApp/build/compose/binaries/main-release"
        # 复制 msi 安装包
        find "$RELEASE_BIN/msi" -name "*.msi" -exec cp {} "$OUTPUT_DIR/" \; 2>/dev/null || true
        # 复制免安装目录
        find "$RELEASE_BIN/app" -maxdepth 1 -mindepth 1 -type d -exec cp -r {} "$OUTPUT_DIR/" \; 2>/dev/null || true
        ;;
esac

echo ""
echo "=============================================="
echo "✅ 构建完成！产物输出于: $OUTPUT_DIR"
echo "=============================================="
ls -lh "$OUTPUT_DIR"
