import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// 按旧模块划分的领域目录（common/ 与 common-test/ 下的子目录即边界）
val inkModules = listOf(
    "entry",
    "latex-parser", "latex-renderer",
    "syntax-parser", "syntax-render",
    "diagram",
    "markdown-parser", "markdown-runtime", "markdown-renderer",
    "vtext",
)

kotlin {
    jvmToolchain(21)

    iosArm64()
    iosSimulatorArm64()

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        // Compose UI test 在 wasmJs 上需要可执行二进制加载 Skiko（CMP-4906）
        binaries.executable()
    }

    android {
        namespace = "xyz.emuci.inkcompose"
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
    }

    sourceSets {
        commonMain {
            kotlin.srcDirs(inkModules.map { "common/$it/kotlin" })
            dependencies {
                api(libs.compose.runtime)
                api(libs.compose.foundation)
                api(libs.compose.ui)
                api(libs.compose.material3)
                api(libs.compose.components.resources)
                api(libs.kotlinx.coroutinesCore)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)
                implementation(libs.kbrowser)
            }
        }
        commonTest {
            kotlin.srcDirs(inkModules.map { "common-test/$it/kotlin" })
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutinesTest)
                implementation(libs.compose.uiTest)
            }
        }
        androidMain {
            kotlin.srcDir("android/kotlin")
            dependencies {
                implementation(libs.androidx.graphics.path)
            }
        }
        iosMain {
            kotlin.srcDir("ios/kotlin")
        }
        jvmMain {
            kotlin.srcDir("jvm/kotlin")
            resources.srcDir("jvm/resources")
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        jvmTest {
            kotlin.srcDir("jvm-test/kotlin")
            resources.srcDir("jvm-test/resources")
        }
        wasmJsMain {
            kotlin.srcDir("wasmJs/kotlin")
        }
    }
}

compose.resources {
    packageOfResClass = "xyz.emuci.inkcompose.resources"
    customDirectory("commonMain", providers.provider { layout.projectDirectory.dir("common/composeResources") })
}

tasks.withType<Test>().configureEach {
    jvmArgs(
        "--enable-native-access=jcef",
        "--add-opens=jcef/com.jetbrains.cef.remote.browser=ALL-UNNAMED",
        "--add-opens=jcef/com.jetbrains.cef.remote=ALL-UNNAMED"
    )
}
