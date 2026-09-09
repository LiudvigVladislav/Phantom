package phantom.android.diagnostic

/** No recorder in release builds. The debug provider installs an opt-in sink. */
object BackgroundTrace {
    @Volatile var sink: ((String, String) -> Unit)? = null
    fun observe(source: String, line: String) {
        try { sink?.invoke(source, line) } catch (_: Exception) { /* Observations never change decisions. */ }
    }
}
