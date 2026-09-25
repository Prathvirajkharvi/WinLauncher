package com.winlauncher.app.domain.controller

/**
 * Integration point for delivering XInputState into the real runtime's XInput
 * implementation (named pipe / shared memory / JNI call into the Wine fork --
 * exact mechanism depends on which Wine-on-Android fork gets integrated).
 *
 * The MVP only logs state transitions so the rest of the input pipeline
 * (GamepadManager -> InputMapper -> here) is provably wired end to end.
 */
interface InputBridge {
    fun onStateChanged(state: XInputState)
}

class LoggingInputBridge : InputBridge {
    private var last: XInputState = XInputState()

    override fun onStateChanged(state: XInputState) {
        // Placeholder: real implementation writes this into the runtime's
        // input channel instead of doing nothing with `last`.
        last = state
    }
}
