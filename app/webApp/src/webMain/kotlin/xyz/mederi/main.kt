package xyz.mederi

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import xyz.mederi.server.RemoteGate

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        // 遥控密码门：server 有密码保护时先弹密码输入，验证通过后才初始化 Mederi
        RemoteGate {
            MederiApp()
        }
    }
}