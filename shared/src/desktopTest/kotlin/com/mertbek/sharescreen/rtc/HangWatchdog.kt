package com.mertbek.sharescreen.rtc

import java.util.Timer
import java.util.TimerTask

class HangWatchdog(private val seconds: Long) : AutoCloseable {
    private val timer = Timer("hang-watchdog", true)

    init {
        timer.schedule(object : TimerTask() {
            override fun run() {
                val dump = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, frames) ->
                    "\"${thread.name}\" ${thread.state}\n" + frames.joinToString("\n") { "    at $it" }
                }
                println("HANG DUMP after ${seconds}s\n$dump")
            }
        }, seconds * 1000)
    }

    override fun close() = timer.cancel()
}
