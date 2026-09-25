package com.winlauncher.app

import android.app.Application
import com.winlauncher.app.data.db.AppDatabase
import com.winlauncher.app.data.repository.ControllerRepository
import com.winlauncher.app.data.repository.GameRepository
import com.winlauncher.app.data.repository.RuntimeRepository
import com.winlauncher.app.domain.runtime.DummyRuntimeEngine
import com.winlauncher.app.domain.runtime.RuntimeEngine

class LauncherApplication : Application() {

    lateinit var gameRepository: GameRepository
        private set
    lateinit var runtimeRepository: RuntimeRepository
        private set
    lateinit var controllerRepository: ControllerRepository
        private set
    lateinit var runtimeEngine: RuntimeEngine
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        gameRepository = GameRepository(db.gameProfileDao())
        runtimeRepository = RuntimeRepository(db.runtimeProfileDao())
        controllerRepository = ControllerRepository(db.controllerProfileDao())
        // Swap this for the real Wine/Box64/DXVK/VKD3D engine when it's integrated --
        // every other layer talks to the RuntimeEngine interface, not this class.
        runtimeEngine = DummyRuntimeEngine(filesDir)
    }
}
