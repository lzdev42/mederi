package xyz.mederi.api.exception

/**
 * Mederi 统一异常基类。
 *
 * 所有通过 [xyz.mederi.Mederi] 公共 API 抛出的异常都应是 [MederiException] 的子类，
 * 确保调用方可以通过单一基类捕获，并根据具体子类区分错误类型。
 *
 * 设计原则：
 * - 不兜底：错误必须暴露给调用方，不能静默吞掉。
 * - 不崩溃：内部未预期异常也要包装为 [MederiInternalException] 抛出，而不是让原始运行时异常直接上抛。
 */
abstract class MederiException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 请求的资源不存在。
 */
class MederiNotFoundException(
    message: String,
    cause: Throwable? = null
) : MederiException(message, cause)

/**
 * 请求参数非法或违反业务规则。
 */
class MederiValidationException(
    message: String,
    cause: Throwable? = null
) : MederiException(message, cause)

/**
 * 当前状态不允许执行该操作。
 */
class MederiStateException(
    message: String,
    cause: Throwable? = null
) : MederiException(message, cause)

/**
 * 内部未预期错误。
 */
class MederiInternalException(
    message: String,
    cause: Throwable? = null
) : MederiException(message, cause)

/**
 * 把底层异常转换为 Mederi 统一异常。
 *
 * 映射关系：
 * - [NoSuchElementException] → [MederiNotFoundException]
 * - [IllegalArgumentException] → [MederiValidationException]
 * - [IllegalStateException] → [MederiStateException]
 * - 其他 → [MederiInternalException]
 *
 * 已经在 [MederiException] 体系内的异常直接透传，避免重复包装。
 */
internal inline fun <T> mederiCall(block: () -> T): T {
    return try {
        block()
    } catch (e: MederiException) {
        throw e
    } catch (e: NoSuchElementException) {
        throw MederiNotFoundException(e.message ?: "Resource not found", e)
    } catch (e: IllegalArgumentException) {
        throw MederiValidationException(e.message ?: "Invalid argument", e)
    } catch (e: IllegalStateException) {
        throw MederiStateException(e.message ?: "Invalid state", e)
    } catch (e: Throwable) {
        throw MederiInternalException(e.message ?: "Internal error", e)
    }
}
