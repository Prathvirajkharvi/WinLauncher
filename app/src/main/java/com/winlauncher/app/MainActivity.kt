package com.winlauncher.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.winlauncher.app.navigation.AppNavHost
import com.winlauncher.app.ui.theme.WinLauncherTheme
import com.winlauncher.app.viewmodel.SharedInput

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WinLauncherTheme {
                AppNavHost(app = application as LauncherApplication)
            }
        }
    }

    // Physical gamepad buttons arrive as KeyEvents at the Activity level.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumed = SharedInput.inputMapper.onKeyEvent(
            event.keyCode,
            pressed = event.action == KeyEvent.ACTION_DOWN,
        )
        return if (consumed) true else super.dispatchKeyEvent(event)
    }

    // Analog sticks/triggers/D-pad hats arrive as MotionEvents (SOURCE_JOYSTICK).
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val consumed = SharedInput.inputMapper.onMotionEvent(event)
        return if (consumed) true else super.dispatchGenericMotionEvent(event)
    }
}
