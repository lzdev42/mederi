import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(project(":app:shared"))
    implementation(project(":core"))
    implementation(libs.koog.agents)
    implementation(libs.ktor.clientCio)
    implementation(libs.kotlinx.serializationJson)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    // cloudflared 隧道：pty4j 以真实 pty 挂起子进程，本进程（含 kill -9）退出时内核向
    // pty 前台进程组发 SIGHUP 使 cloudflared 随之退出（"终端关了进程就死"的语义）。
    // JVM-only 库，无 KMP 替代品，desktop 专属，放这里不进 shared。
    implementation(libs.pty4j)

    // 内置 JCEF 浏览器（多 tab = KBPage）：desktop 宿主专属，UI 端浏览器宿主用。
    // 与 inkcompose 的 mermaid JCEF worker 共用同一 KBrowser 运行时（useOsr=true）。
    implementation(libs.kbrowser)

    // 图标库：用于浏览器工具栏后退、前进、刷新等图标
    implementation(libs.composeIcons.feather)

    testImplementation(libs.kotlin.test)
}

// ------------------------------------------------------------------
// 自托管 wasmJs Web UI：构建期联动 :app:webApp 的 wasmJsBrowserDistribution
// ------------------------------------------------------------------
evaluationDependsOn(":app:webApp")

val wasmJsProject = project(":app:webApp")
val wasmJsDistTask = wasmJsProject.tasks.named("wasmJsBrowserDistribution")

val checkout = rootProject.layout.projectDirectory.asFile
val checkoutRuntimeRevision = if (checkout.resolve(".git").exists()) {
    val revision = providers.exec {
        workingDir(checkout)
        commandLine("git", "rev-parse", "--verify", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText
    val scriptsMatchRevision = providers.exec {
        workingDir(checkout)
        commandLine("git", "diff", "--quiet", "HEAD", "--", "scripts/runtime/install.sh", "scripts/runtime/install.ps1")
        isIgnoreExitValue = true
    }.result
    revision.zip(scriptsMatchRevision) { hash, diff ->
        if (diff.exitValue == 0) hash.trim() else ""
    }
} else providers.provider { "" }

// Only ship the revision. Both the app and the terminal retrieve the public scripts at that revision.
val runtimeScriptRevision = providers.gradleProperty("mederi.runtimeScriptRevision").map { hash ->
    require(hash.matches(Regex("[0-9a-f]{40}"))) { "mederi.runtimeScriptRevision must be a full Git commit SHA." }
    hash
}.orElse(checkoutRuntimeRevision)
val runtimeRevisionFile = layout.buildDirectory.file("generated/runtime-revision/revision.txt")
val generateRuntimeRevision = tasks.register("generateRuntimeRevision") {
    val revision = runtimeScriptRevision
    val output = runtimeRevisionFile
    inputs.property("revision", revision)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(revision.get())
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(wasmJsDistTask, generateRuntimeRevision)
    from(runtimeRevisionFile) {
        into("runtime")
    }
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

compose.desktop {
    application {
        mainClass = "xyz.mederi.MainKt"
        providers.gradleProperty("mederi.releaseJavaHome").orNull?.let { javaHome = it }
        jvmArgs += listOf(
            "--enable-native-access=jcef",
            "--add-opens=jcef/com.jetbrains.cef.remote.browser=ALL-UNNAMED",
            "--add-opens=jcef/com.jetbrains.cef.remote=ALL-UNNAMED"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "xyz.mederi"
            packageVersion = "1.0.0"

            modules(
                "java.instrument",
                "java.naming",
                "java.security.jgss",
                "java.sql",
                "java.xml.crypto",
                "jdk.httpserver",
                "jcef",
                "jdk.jfr",
                "jdk.management",
                "jdk.security.auth",
                "jdk.unsupported"
            )
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
