package xyz.mederi.core.remote

import xyz.mederi.AppInfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.launch
import mederi.app.shared.generated.resources.Res
import mederi.app.shared.generated.resources.remotegate_connecting
import mederi.app.shared.generated.resources.remotegate_enter
import mederi.app.shared.generated.resources.remotegate_hint
import mederi.app.shared.generated.resources.remotegate_password_label
import mederi.app.shared.generated.resources.remotegate_password_required
import mederi.app.shared.generated.resources.remotegate_password_wrong
import mederi.app.shared.generated.resources.remotegate_title
import org.jetbrains.compose.resources.stringResource

private const val STORAGE_KEY = "mederi.remote.password"

/** 从 localStorage 读取已保存的遥控密码；未保存返回 null。 */
fun readStoredPassword(): String? = localStorage.getItem(STORAGE_KEY)

/** 保存遥控密码到 localStorage（浏览器本机）。 */
fun writeStoredPassword(password: String) {
    localStorage.setItem(STORAGE_KEY, password)
}

/** 清除已保存的遥控密码。 */
fun clearStoredPassword() {
    localStorage.removeItem(STORAGE_KEY)
}

/**
 * 遥控密码门（浏览器端）：检测到 server 需要密码时先弹密码输入，验证通过才渲染内容。
 * 在 MederiApp 外层包裹，密码未通过时不给创建 AiCore。
 */
@Composable
fun RemoteGate(content: @Composable () -> Unit) {
    var phase by remember { mutableStateOf(GatePhase.Checking) }
    var password by remember { mutableStateOf(readStoredPassword() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    // 文案在组合上下文取值（Button onClick 不是 @Composable）
    val passwordRequiredMsg = stringResource(Res.string.remotegate_password_required)
    val passwordWrongMsg = stringResource(Res.string.remotegate_password_wrong)
    val client = remember { HttpClient { defaultRequest { header(HttpHeaders.UserAgent, AppInfo.userAgent) } } }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val probe = probeServer(client, readStoredPassword())
        phase = if (probe == ProbeResult.Authorized) GatePhase.Ready else GatePhase.NeedPassword
    }

    when (phase) {
        GatePhase.Checking -> Box(Modifier.fillMaxSize().background(Color(0xFF0D1117)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color(0xFF58A6FF))
                Text(stringResource(Res.string.remotegate_connecting), color = Color(0xFF8B949E), fontSize = 13.sp)
            }
        }

        GatePhase.NeedPassword -> Box(
            Modifier.fillMaxSize().background(Color(0xFF0D1117)).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(Res.string.remotegate_title), color = Color(0xFFE6EDF3), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.remotegate_hint), color = Color(0xFF8B949E), fontSize = 12.sp)

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.remotegate_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE6EDF3),
                        unfocusedTextColor = Color(0xFFE6EDF3),
                        focusedBorderColor = Color(0xFF58A6FF),
                        unfocusedBorderColor = Color(0xFF30363D),
                        cursorColor = Color(0xFF58A6FF),
                    )
                )

                if (error != null) {
                    Text(error!!, color = Color(0xFFE5484D), fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        if (password.isBlank()) { error = passwordRequiredMsg; return@Button }
                        error = null
                        phase = GatePhase.Checking
                        scope.launch {
                            val ok = probeServer(client, password) == ProbeResult.Authorized
                            if (ok) {
                                writeStoredPassword(password)
                                phase = GatePhase.Ready
                            } else {
                                error = passwordWrongMsg
                                phase = GatePhase.NeedPassword
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636), contentColor = Color.White)
                ) { Text(stringResource(Res.string.remotegate_enter), fontSize = 14.sp) }
            }
        }

        GatePhase.Ready -> content()
    }
}

private enum class GatePhase { Checking, NeedPassword, Ready }
private enum class ProbeResult { Authorized, Unauthorized }

private suspend fun probeServer(client: HttpClient, password: String?): ProbeResult = runCatching {
    val response = client.get(window.location.origin + "/v1/providers") {
        password?.let { header("Authorization", "Bearer $it") }
    }
    when (response.status) {
        HttpStatusCode.OK -> ProbeResult.Authorized
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> ProbeResult.Unauthorized
        else -> ProbeResult.Authorized
    }
}.getOrElse { ProbeResult.Authorized }