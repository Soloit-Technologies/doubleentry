package com.soloit.doubleentry.platform

actual object Log {
    /** Debug messages are only printed with `-Ddoubleentry.debug=true`. */
    private val debug = System.getProperty("doubleentry.debug").toBoolean()

    actual fun d(tag: String, message: String, error: Throwable?) {
        if (debug) print("D", tag, message, error)
    }

    actual fun i(tag: String, message: String, error: Throwable?) = print("I", tag, message, error)

    actual fun w(tag: String, message: String, error: Throwable?) = print("W", tag, message, error)

    private fun print(level: String, tag: String, message: String, error: Throwable?) {
        System.err.println("$level/$tag: $message" + (error?.let { ": $it" } ?: ""))
    }
}
