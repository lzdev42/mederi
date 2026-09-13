plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
}

group = "xyz.mederi"
version = "1.0.0"

// core 保持 KMP 模块身份，但当前实现是 JVM 侧逻辑（koog / SQLite JDBC / java.io）。
// ios/android/wasm 平台经 app:shared 的 AiCore 契约桥（ServerAiCore REST）访问，不直连 core，
// 故只声明 jvm 目标；后续 core 代码真正 KMP 化时再补其他目标。
kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.koog.agents)
            implementation(libs.koog.mcp)
            implementation(libs.koog.google.client)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientCio)
            implementation(libs.ktor.sse)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

sqldelight {
    databases {
        // 配置库：providers / api_keys / projects / mcp_servers —— 极小、低频写、事务一致，
        // 一个文件 = 全部软件配置，可单独备份迁移
        create("MederiConfigDatabase") {
            packageName.set("xyz.mederi.db.config")
            srcDirs("src/commonMain/sqldelight/config")
            dialect(libs.sqldelight.dialect.sqlite338)
        }
        // 数据库：sessions / message_history / diffs —— 高频写、开发期可随时删库重建
        create("MederiDataDatabase") {
            packageName.set("xyz.mederi.db.data")
            srcDirs("src/commonMain/sqldelight/data")
            dialect(libs.sqldelight.dialect.sqlite338)
        }
    }
}
