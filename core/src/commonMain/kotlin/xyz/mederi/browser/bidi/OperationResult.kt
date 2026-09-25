package xyz.mederi.browser.bidi

sealed class OperationResult {
    abstract val success: Boolean

    data class Success(
        val action: String,
        val verified: Boolean = true,
        val detail: String = ""
    ) : OperationResult() {
        override val success: Boolean get() = true
        override fun toString(): String =
            if (detail.isNotEmpty()) "Success($action, verified=$verified, $detail)"
            else "Success($action, verified=$verified)"
    }

    data class Failure(
        val action: String,
        val reason: String,
        val recoverable: Boolean = true
    ) : OperationResult() {
        override val success: Boolean get() = false
        override fun toString(): String = "Failure($action, $reason)"
    }

    data object Acknowledged : OperationResult() {
        override val success: Boolean get() = true
        override fun toString(): String = "Acknowledged"
    }
}
