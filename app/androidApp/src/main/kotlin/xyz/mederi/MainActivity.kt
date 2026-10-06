package xyz.mederi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import xyz.emuci.inkcompose.MermaidCacheConfig
import xyz.mederi.core.contract.preferences.AndroidAppContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 注入 Application Context，供 defaultPreferencesStore() 读取偏好（remote url/密码/主题）。
        AndroidAppContext.applicationContext = applicationContext

        // 宿主专属注入：遥控端 Mermaid 磁盘缓存固定用本应用缓存目录。
        // 不跟随 server 的 project.directories（设备上不存在/无权限），库内默认值同样不可写
        MermaidCacheConfig.setBaseDirectory(cacheDir.absolutePath)

        setContent {
            MederiApp()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    MederiApp()
}