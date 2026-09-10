package xyz.mederi.core.mock

class MockIdGenerator {
    private var counter = 0
    fun next(): String = "id_${++counter}"
}
