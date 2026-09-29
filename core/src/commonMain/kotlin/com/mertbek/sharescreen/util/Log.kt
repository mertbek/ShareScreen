package com.mertbek.sharescreen.util

object Log {
    var sink: (level: String, tag: String, message: String, error: Throwable?) -> Unit = { level, tag, message, error ->
        println("$level/$tag: $message" + (error?.let { " ($it)" } ?: ""))
    }

    fun d(tag: String, message: String) = sink("D", tag, message, null)

    fun i(tag: String, message: String) = sink("I", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = sink("W", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = sink("E", tag, message, error)
}
