package judozoo.library.brokertoken

interface TokenSource {
    fun get(): String

    fun invalidate()
}
