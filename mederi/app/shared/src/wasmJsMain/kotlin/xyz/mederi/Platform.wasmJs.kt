package xyz.mederi

class WasmPlatform : Platform {
    override val name: String = "Web (Wasm)"
}

actual fun getPlatform(): Platform = WasmPlatform()

actual val isDesktopPlatform: Boolean = false
