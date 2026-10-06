package xyz.mederi

import kotlin.test.Test
import kotlin.test.assertNotNull

class ApplicationTest {

    @Test
    fun staticResourcesExist() {
        // wasmJs 入口页嵌入 classpath 的 /static/
        assertNotNull(
            ApplicationTest::class.java.getResource("/static/index.html"),
            "wasmJs 入口页 /static/index.html 未嵌入 classpath"
        )
        // JS bundle 嵌入 classpath 的 /static/
        assertNotNull(
            ApplicationTest::class.java.getResource("/static/webApp.js"),
            "wasmJs JS bundle /static/webApp.js 未嵌入 classpath"
        )
        // 样式资源嵌入 classpath 的 /static/
        assertNotNull(
            ApplicationTest::class.java.getResource("/static/styles.css"),
            "wasmJs 样式 /static/styles.css 未嵌入 classpath"
        )
    }
}