package com.winlauncher.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.winlauncher.app.data.db.AppDatabase
import com.winlauncher.app.data.repository.ControllerRepository
import com.winlauncher.app.data.repository.GameRepository
import com.winlauncher.app.data.repository.RuntimeRepository
import com.winlauncher.app.domain.runtime.DummyRuntimeEngine
import com.winlauncher.app.domain.runtime.RealRuntimeEngine
import com.winlauncher.app.domain.runtime.RuntimeEngine
import com.winlauncher.app.domain.runtime.RuntimeInstallationManager

class LauncherApplication : Application() {

    lateinit var gameRepository: GameRepository
        private set
    lateinit var runtimeRepository: RuntimeRepository
        private set
    lateinit var controllerRepository: ControllerRepository
        private set
    lateinit var runtimeInstallationManager: RuntimeInstallationManager
        private set

    /**
     * Selectable per requirement: real Wine+Box64 is the default engine, but
     * DummyRuntimeEngine stays available for development/testing (e.g. on a
     * device with no Wine/Box64 installed yet) via the Settings toggle.
     */
    var runtimeEngine: RuntimeEngine = FailFastPlaceholderEngine
        private set

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        gameRepository = GameRepository(db.gameProfileDao())
        runtimeRepository = RuntimeRepository(db.runtimeProfileDao())
        controllerRepository = ControllerRepository(db.controllerProfileDao())
        runtimeInstallationManager = RuntimeInstallationManager(this)

        prefs = getSharedPreferences("winlauncher_prefs", Context.MODE_PRIVATE)
        applyEngineSelection(useDummy = prefs.getBoolean(KEY_USE_DUMMY_ENGINE, false))
    }

    fun useDummyEngine(): Boolean = prefs.getBoolean(KEY_USE_DUMMY_ENGINE, false)

    /** Called by the Settings toggle -- takes effect on the next screen that requests a ViewModel. */
    fun setUseDummyEngine(useDummy: Boolean) {
        prefs.edit().putBoolean(KEY_USE_DUMMY_ENGINE, useDummy).apply()
        applyEngineSelection(useDummy)
    }

    private fun applyEngineSelection(useDummy: Boolean) {
        runtimeEngine = if (useDummy) {
            DummyRuntimeEngine(filesDir)
        } else {
            RealRuntimeEngine(this, runtimeInstallationManager)
        }
    }

    companion object {
        private const val KEY_USE_DUMMY_ENGINE = "use_dummy_engine"
    }
}

/** Never actually used: replaced in onCreate() before any screen can read it. */
private object FailFastPlaceholderEngine : RuntimeEngine {
    override suspend fun initialize(runtimeProfile: com.winlauncher.app.data.db.entity.RuntimeProfile) =
        throw IllegalStateException("LauncherApplication.onCreate() has not run yet")
    override suspend fun validate(
        game: com.winlauncher.app.data.db.entity.GameProfile,
        runtimeProfile: com.winlauncher.app.data.db.entity.RuntimeProfile,
    ) = throw IllegalStateException("LauncherApplication.onCreate() has not run yet")
    override suspend fun launch(
        game: com.winlauncher.app.data.db.entity.GameProfile,
        runtimeProfile: com.winlauncher.app.data.db.entity.RuntimeProfile,
    ) = throw IllegalStateException("LauncherApplication.onCreate() has not run yet")
    override suspend fun stop() = Unit
    override fun getStatus() = throw IllegalStateException("LauncherApplication.onCreate() has not run yet")
}

