import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
        namespace = "xyz.mederi.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            // Ktor 客户端引擎：coil-network-ktor3（inkcompose 图片加载）与 ServerAiCore 的
            // 网络请求都需要引擎，Android 上 Ktor 无默认引擎，缺了会报
            // "Failed to find HTTP client engine implementation"（URL 图片静默不渲染）
            implementation(libs.ktor.clientCio)
        }
        commonMain.dependencies {
            api(project(":inkcompose"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // ServerAiCore：REST + SSE 遥控 server（KMP：不带平台后缀，Gradle 按 target 解析变体）
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.clientSerializationJson)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.composeIcons.feather)
            implementation(libs.coil.compose)
            // FilePreferencesStore：commonMain 统一文件偏好存储（okio = KMP 文件系统抽象）
            implementation(libs.okio)
        }
        iosMain.dependencies {
            // iOS 上 Ktor 客户端需要 Darwin 引擎（NSURLSession），无默认引擎
            implementation(libs.ktor.clientDarwin)
        }
        jvmMain.dependencies {
            // core 是 JVM 侧模块，仅 desktop/server 直连；wasm/ios 走 ServerAiCore(REST)
            api(project(":core"))
            // SessionTitleService：直连 OpenCode Zen 免费模型做会话自动命名（JVM-only）
            implementation(libs.ktor.clientCio)
            // 内嵌遥控 server（RemoteServer）：Ktor server 无 KMP 替代品，JVM-only 例外，仅 jvmMain
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.serverContentNegotiation)
            implementation(libs.ktor.serverCors)
            implementation(libs.ktor.serverSse)
            implementation(libs.ktor.serverAuth)
            implementation(libs.ktor.serializationJson)
            // TerminalHub：pty4j 真实 pty 会话（会话拥有者）。
            // JVM-only：pty4j 的 native 是 glibc 二进制，Android(bionic)/iOS(沙箱) 不可用，无 KMP 替代品
            implementation(libs.pty4j)
            // desktop 终端渲染：jediterm（IntelliJ 终端本体，纯 Java Swing，经 SwingPanel 嵌入）。
            // 坐标在 JetBrains intellij-dependencies repo（见 settings.gradle.kts）；ui/core 为 LGPL
            implementation(libs.jediterm.ui)
            implementation(libs.jediterm.core)
        }
        jvmTest.dependencies {
            // KBrowser.newPage 内部用 Dispatchers.Main（kotlinx-coroutines-swing 提供）
            implementation(libs.kotlinx.coroutinesSwing)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// 生成的 AppVersion.kt 挂到 commonMain（四个平台 + 对应 test 都可见）
kotlin.sourceSets.commonMain {
    kotlin.srcDir(layout.buildDirectory.dir("generated/version/commonMain"))
}

// ── 版本号单一真理源注入 ──────────────────────────────────────────────
// `version.json`（Gradle 工程根，CI 基于 git tag 写入）是版本号唯一出处，源码不写版本字面量。
// 每次编译前读它 → 生成 build/generated/version/commonMain/xyz/mederi/AppVersion.kt
// → `AppInfo.VERSION` / User-Agent 都引用该生成常量。
val generateVersionSource = tasks.register("generateVersionSource") {
    val versionJson = rootProject.file("version.json")
    val outputDir = layout.buildDirectory.dir("generated/version/commonMain")
    inputs.file(versionJson)
    outputs.dir(outputDir)
    doLast {
        val parsed = groovy.json.JsonSlurper().parse(versionJson) as Map<*, *>
        val version = parsed["version"] as? String
            ?: error("version.json 必须包含非空 version 字段：$versionJson")
        val source = """
            |package xyz.mederi
            |
            |/** 由 version.json 生成的版本常量（CI 基于 git tag 写入），勿手改。 */
            |const val MEDERI_APP_VERSION: String = "$version"
        """.trimMargin() + "\n"
        val target = outputDir.get().asFile.resolve("xyz/mederi/AppVersion.kt")
        target.parentFile.mkdirs()
        target.writeText(source)
    }
}

// 所有 Kotlin 源编译任务（jvm / android / ios / wasmJs / 各 test，基类统一为 AbstractKotlinCompileTool）
// 先跑版本生成，避免 Gradle 因"使用了未声明依赖的输出"校验报错
tasks.configureEach {
    if (this is AbstractKotlinCompileTool<*>) {
        dependsOn(generateVersionSource)
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}