import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.jvm.application.tasks.CreateStartScripts

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

// Compose 的传递依赖可重复提供同名 desktop JAR，目录分发与压缩分发保持一致。
tasks.withType<Sync> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// API-only 产物独立生成，不切换 processResources 开关，避免连续构建时混入旧 static 资源。
val apiJar by tasks.registering(Jar::class) {
    archiveClassifier.set("api")
    from(sourceSets.main.get().output.classesDirs)
    from(sourceSets.main.get().resources) { exclude("static/**") }
}

val apiStartScripts by tasks.registering(CreateStartScripts::class) {
    applicationName = "mederi-server"
    mainClass.set(application.mainClass)
    classpath = files(apiJar) + configurations.runtimeClasspath.get()
    outputDir = layout.buildDirectory.dir("apiScripts").get().asFile
    // 普通 Java 25 即可运行；JCEF 浏览器能力由部署者的运行时决定。
    defaultJvmOpts = emptyList<String>()
}

val installServerApi by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("install/server-api"))
    from(apiJar) { into("lib") }
    from(configurations.runtimeClasspath) { into("lib") }
    from(apiStartScripts) {
        into("bin")
        filePermissions { unix("rwxr-xr-x") }
    }
}

// 复制完整 JBR（包含 JCEF native 文件），不以普通 jlink 镜像冒充带浏览器的运行时。
val releaseJavaHome = providers.gradleProperty("mederi.releaseJavaHome")
val installServerBundled by tasks.registering(Sync::class) {
    dependsOn(tasks.named("installDist"))
    into(layout.buildDirectory.dir("install/server-bundled"))
    from(tasks.named("installDist"))
    from(releaseJavaHome.map { home ->
        val javaHome = file(home)
        if (javaHome.name == "Home" && javaHome.parentFile.name == "Contents") {
            javaHome.parentFile.parentFile
        } else javaHome
    }) { into("runtime") }
    doFirst {
        require(releaseJavaHome.isPresent) { "Set -Pmederi.releaseJavaHome to a JBR SDK with JCEF" }
        require(file("${releaseJavaHome.get()}/jmods/jcef.jmod").isFile) {
            "Bundled server requires a JBR SDK with JCEF"
        }
    }
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
