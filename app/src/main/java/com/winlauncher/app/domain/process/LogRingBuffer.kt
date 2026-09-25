package com.winlauncher.app.domain.process

import java.util.ArrayDeque

/** Thread-safe fixed-size log tail, used by the Logs/Error screen. */
class LogRingBuffer(private val capacity: Int = 500) {
    private val buffer = ArrayDeque<String>(capacity)

    @Synchronized
    fun add(line: String) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()
}
