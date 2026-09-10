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
    api(project(":core"))
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