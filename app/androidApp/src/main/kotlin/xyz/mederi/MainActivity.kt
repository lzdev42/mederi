package xyz.mederi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import xyz.emuci.inkcompose.MermaidCacheConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 宿主专属注入：遥控端 Mermaid 磁盘缓存固定用本应用缓存目录。
        // 不跟随 server 的 project.directories（设备上不存在/无权限），库内默认值同样不可写
        MermaidCacheConfig.setBaseDirectory(cacheDir.absolutePath)

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}