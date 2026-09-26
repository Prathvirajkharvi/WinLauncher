package com.winlauncher.app.domain.runtime

/** Implemented by any RuntimeEngine that captures process stdout/stderr for the Logs screen. */
interface LogSource {
    fun currentLogs(): List<String>
}
