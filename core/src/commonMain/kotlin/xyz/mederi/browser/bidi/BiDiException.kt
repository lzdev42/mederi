package xyz.mederi.browser.bidi

open class BiDiException(val code: String, override val message: String) : Exception(message)
class BiDiErrorException(code: String, message: String) : BiDiException(code, message)
class ProfileCorruptedException(message: String) : Exception(message)
class BrowserStartTimeoutException : Exception("Browser failed to start within timeout")
class ElementNotFoundException(val ref: String) : Exception("Element not found: $ref")
class NavigationTimeoutException(url: String) : Exception("Navigation timeout: $url")
