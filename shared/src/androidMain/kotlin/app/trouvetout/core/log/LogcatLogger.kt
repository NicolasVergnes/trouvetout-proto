package app.trouvetout.core.log

import android.util.Log

/** Debug-only sink. Callers must keep business data out of messages (identifiers only). */
public object LogcatLogger : Logger {
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val text = if (throwable == null) message else "$message\n${Log.getStackTraceString(throwable)}"
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, text)
            LogLevel.INFO -> Log.i(tag, text)
            LogLevel.WARN -> Log.w(tag, text)
            LogLevel.ERROR -> Log.e(tag, text)
        }
    }
}
