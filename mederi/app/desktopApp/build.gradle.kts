import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":app:shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    // cloudflared 隧道：pty4j 以真实 pty 挂起子进程，本进程（含 kill -9）退出时内核向
    // pty 前台进程组发 SIGHUP 使 cloudflared 随之退出（"终端关了进程就死"的语义）。
    // JVM-only 库，无 KMP 替代品，desktop 专属，放这里不进 shared。
    implementation(libs.pty4j)
}

compose.desktop {
    application {
        mainClass = "xyz.mederi.MainKt"
        jvmArgs += listOf(
            "--enable-native-access=jcef",
            "--add-opens=jcef/com.jetbrains.cef.remote.browser=ALL-UNNAMED",
            "--add-opens=jcef/com.jetbrains.cef.remote=ALL-UNNAMED"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "xyz.mederi"
            packageVersion = "1.0.0"
        }
    }
}