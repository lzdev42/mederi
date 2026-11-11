import org.gradle.jvm.toolchain.JavaLanguageVersion
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
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
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
        exclude("**/*.map")
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

val serverJavaHome = providers.gradleProperty("mederi.serverJavaHome")
    .orElse(providers.gradleProperty("mederi.releaseJavaHome"))
    .orElse(providers.environmentVariable("MEDERI_SERVER_JAVA_HOME"))
    .orElse(providers.systemProperty("java.home"))
    .map { file(it) }
val serverJavaModules = serverJavaHome.map { home ->
    val release = home.resolve("release")
    require(release.isFile) { "Missing Java release metadata: $home" }
    val metadata = release.readLines()
    val version = metadata.firstOrNull { it.startsWith("JAVA_VERSION=") }?.substringAfter('=')?.trim('"')
    val modules = metadata.firstOrNull { it.startsWith("MODULES=") }?.substringAfter('=')?.trim('"')?.split(' ').orEmpty()
    require(version?.substringBefore('.') == "25" && "java.base" in modules) {
        "Bundled server requires a Java 25 runtime: $home"
    }
    modules
}
val serverJavaHasJcef = serverJavaModules.zip(serverJavaHome) { modules, home ->
    "jcef" in modules || home.resolve("jmods/jcef.jmod").exists()
}
val linkedServerRuntime = layout.buildDirectory.dir("server-runtime")
val linkServerRuntime by tasks.registering(Exec::class) {
    onlyIf("source Java contains JCEF") { serverJavaHasJcef.get() }
    inputs.file(serverJavaHome.map { it.resolve("release") })
    inputs.dir(serverJavaHome.map { it.resolve("jmods") }).optional()
    outputs.dir(linkedServerRuntime)
    doFirst {
        val home = serverJavaHome.get()
        val jlink = home.resolve("bin/jlink").takeIf { it.isFile } ?: home.resolve("bin/jlink.exe")
        val jmods = home.resolve("jmods")
        require(jlink.isFile && jmods.resolve("java.base.jmod").isFile) {
            "Building a Server runtime from JBR with JCEF requires the full JBR SDK: $home"
        }
        val runtime = linkedServerRuntime.get().asFile
        check(runtime.deleteRecursively()) { "Cannot replace Server runtime: $runtime" }
        val requiredModules = setOf(
            "java.base",
            "java.desktop",
            "java.instrument",
            "java.logging",
            "java.management",
            "java.naming",
            "java.net.http",
            "java.prefs",
            "java.security.jgss",
            "java.security.sasl",
            "java.sql",
            "java.xml",
            "java.xml.crypto",
            "jdk.crypto.ec",
            "jdk.httpserver",
            "jdk.jfr",
            "jdk.management",
            "jdk.security.auth",
            "jdk.unsupported",
            "jdk.zipfs"
        )
        val modules = serverJavaModules.get().filter { it in requiredModules }.joinToString(",")
        commandLine(
            jlink.absolutePath,
            "--module-path", jmods.absolutePath,
            "--add-modules", modules,
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--output", runtime.absolutePath
        )
    }
    doLast {
        val release = linkedServerRuntime.get().file("release").asFile
        val modules = release.readLines().first { it.startsWith("MODULES=") }
            .substringAfter('=').trim('"').split(' ')
        check("jcef" !in modules) { "Linked Server runtime contains JCEF" }
    }
}
val installServerBundled by tasks.registering(Sync::class) {
    dependsOn(tasks.named("installDist"), linkServerRuntime)
    into(layout.buildDirectory.dir("install/server-bundled"))
    from(tasks.named("installDist"))
    from(serverJavaHasJcef.flatMap { hasJcef ->
        if (hasJcef) linkedServerRuntime.map { it.asFile } else serverJavaHome
    }) { into("runtime") }
    // JDK license files may be read-only. Remove the previous generated image before replacing it.
    doFirst { delete(destinationDir.resolve("runtime")) }
}

dependencies {
    // JVM 变体：复用 MederiAiCore 桥接 + contract DTO（wasm 浏览器经本模块 REST/SSE 访问与 desktop 一致的 AiCore 语义）
    // 无头 server 排除无需渲染的 Compose UI、Skiko 和富文本渲染器
    implementation(project(":app:shared")) {
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.material3")
        exclude(group = "org.jetbrains.compose.material")
        exclude(group = "org.jetbrains.compose.animation")
        exclude(group = "org.jetbrains.compose.components")
        exclude(group = "org.jetbrains.skiko")
        exclude(module = "inkcompose")
        exclude(group = "io.coil-kt.coil3")
        exclude(group = "br.com.devsrsouza.compose.icons")
        exclude(group = "org.jetbrains.jediterm")
    }
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty) {
        exclude(group = "io.netty", module = "netty-codec-http3")
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverSse)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serializationJson)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
