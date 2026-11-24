package org.voltarians.elmlab

import java.io.InputStream
import java.io.OutputStream

/** Shared wire handling for Android TCP/SPP and the desktop compatibility harness. */
class ElmStreamSession(private val engine: Elm327Engine) {
    fun serve(input: InputStream, output: OutputStream, active: () -> Boolean = { true }, observe: (String, String) -> Unit = { _, _ -> }) {
        val command = StringBuilder()
        var lastWasCr = false
        while (active() && !Thread.currentThread().isInterrupted) {
            val byte = input.read()
            if (byte < 0) return
            if (byte == 10 && lastWasCr) { lastWasCr = false; continue }
            lastWasCr = byte == 13
            when (byte) {
                8, 127 -> if (command.isNotEmpty()) command.deleteCharAt(command.lastIndex)
                13, 10 -> {
                    val value = command.toString()
                    command.clear()
                    val reply = engine.exchange(value)
                    if (reply.delayMs > 0) Thread.sleep(reply.delayMs.toLong())
                    if (!active()) return
                    output.write(reply.text.toByteArray(Charsets.US_ASCII)); output.flush()
                    observe(value, reply.text)
                }
                in 32..126 -> { if (command.length >= 512) return; command.append(byte.toChar()) }
                else -> return
            }
        }
    }
}
