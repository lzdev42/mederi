import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "xyz.mederi"
version = "1.0.0"
application {
    mainClass = "xyz.mederi.ApplicationKt"
    // JCEF JVM args：server 用 JBR（JvmVendorSpec.JETBRAINS），JCEF 经 :app:shared 传递依赖在 classpath 上，
    // 与 app/desktopApp/build.gradle.kts 的 compose.desktop.application.jvmArgs 对齐，使 server 进程能初始化 JCEF。
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=jcef",
        "--add-opens=jcef/com.jetbrains.cef.remote.browser=ALL-UNNAMED",
        "--add-opens=jcef/com.jetbrains.cef.remote=ALL-UNNAMED"
    )
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}

// ------------------------------------------------------------------
// 自托管 wasmJs Web UI：构建期联动 :app:webApp 的 wasmJsBrowserDistribution
// ------------------------------------------------------------------
evaluationDependsOn(":app:webApp")

val wasmJsProject = project(":app:webApp")
val wasmJsDistTask = wasmJsProject.tasks.named("wasmJsBrowserDistribution")

tasks.named<ProcessResources>("processResources") {
    dependsOn(wasmJsDistTask)
    from(wasmJsProject.layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) {
        into("static")
    }
}

tasks.withType<Tar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    // JVM 变体：复用 MederiAiCore 桥接 + contract DTO（wasm 浏览器经本模块 REST/SSE 访问与 desktop 一致的 AiCore 语义）
    implementation(project(":app:shared"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverSse)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serializationJson)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}