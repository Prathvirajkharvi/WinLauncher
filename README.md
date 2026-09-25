# WinLauncher MVP

Android launcher infrastructure for running user-owned Windows PC games via a
future Wine + Box64 + DXVK/VKD3D integration. **This MVP does not run Windows
games yet** -- see `domain/runtime/DummyRuntimeEngine.kt` for exactly what's
real vs. placeholder, and `ARCHITECTURE.md` (delivered separately) for the
full system design this project follows.

## What's real in this MVP

- Full Compose UI: Home, Library, Add Game, Game Details, Runtime Manager,
  Controller Manager (with touch overlay), Settings
- Room-backed persistence for game/runtime/controller profiles
- SAF-based game file picking with persisted URI permissions (no
  `MANAGE_EXTERNAL_STORAGE`, no root)
- **Real** native GPU detection: actual `vkCreateInstance`/
  `vkEnumeratePhysicalDevices` calls and a real EGL/GLES probe --
  see `app/src/main/cpp/gpu/`
- Mali-first capability tiering (`MaliProfile.kt`) and independent
  DXVK/VKD3D resolution logic (`GraphicsManager.kt`)
- Real OS process lifecycle: start/stop/crash-detect/log-capture, currently
  pointed at a harmless `/system/bin/sh` script, NOT Wine
  (`DummyRuntimeEngine.kt`)
- Real physical gamepad detection (`InputManager`) and a functional virtual
  touch controller, both feeding one `InputMapper` pipeline

## What's a placeholder / future integration point

- `RuntimeEngine` has exactly one implementation today: `DummyRuntimeEngine`.
  Swapping in real Wine/Box64/DXVK/VKD3D means writing a new class that
  implements the same interface -- no other layer changes.
- `InputBridge` only logs state transitions; wiring it into a real Wine
  XInput implementation (named pipe / shared memory / JNI) is future work.

---

## 1. Setup instructions

1. Install **Android Studio Ladybug (2024.2)** or newer.
2. Install via SDK Manager:
   - Android SDK Platform 35
   - NDK (side by side) -- version 26.x or newer
   - CMake 3.22.1
3. Open the `WinLauncher/` folder as an existing project. Android Studio will
   prompt to generate the Gradle wrapper jar/scripts if they're missing
   (this project ships `gradle/wrapper/gradle-wrapper.properties` pinned to
   Gradle 8.7, but not the binary wrapper jar) -- accept the prompt, or run
   `gradle wrapper` yourself if you have a local Gradle install.

## 2. Build instructions

```bash
# from the WinLauncher/ directory, after the wrapper is generated
./gradlew assembleDebug
```

The native module builds automatically as part of the Gradle build via the
`externalNativeBuild { cmake { ... } }` block in `app/build.gradle.kts` --
no separate `ndk-build`/`cmake` invocation needed.

Install to a connected device/emulator:

```bash
./gradlew installDebug
```

A real device is required to meaningfully test GPU detection and controller
input; the emulator's software Vulkan/GLES stack will report generic
"Unknown"/software-renderer values, which is expected and not a bug.

## 3. NDK/CMake setup notes

- `app/src/main/cpp/CMakeLists.txt` builds a single shared library,
  `libgpudetect.so`, linking `vulkan`, `EGL`, `GLESv2`, `android`, and `log`.
- `abiFilters` is set to `arm64-v8a` only in `app/build.gradle.kts` --
  intentional, since Mali/Adreno devices this project targets are all 64-bit
  ARM. Add `armeabi-v7a` back if 32-bit support becomes a requirement.
- If CMake configuration fails with "vulkan library not found", confirm the
  connected NDK version bundles `libvulkan.so` stubs (NDK r21+ all do).

## 4. Testing GPU detection on a real Mali phone

1. Install and launch the app on a Mali-equipped device.
2. Open any Game Details screen for a saved game (add a dummy game first via
   **Library > +** if none exist) -- the graphics badge at the top calls
   `GpuDetector.detect()` on first read.
3. Expected: badge shows "Vulkan + DXVK" or "OpenGL ES fallback" with a
   human-readable reason. If it shows "Unsupported", the reason string names
   the missing Vulkan extension/feature -- that's the detector doing its job,
   not a crash.
4. For raw values, add a temporary `Log.d("GpuDetector", caps.toString())`
   in `GpuDetector.detect()` and check Logcat for `vkDeviceName`,
   `vkApiVersion`, and the full extension list.

## 5. Testing physical controller detection

1. Pair a Bluetooth Xbox-compatible pad (or plug in a USB pad) to the test
   device.
2. Open **Library > Controllers** (Controller Manager screen).
3. The pad should appear under "Physical controllers" within a second of
   connecting (driven by `InputManager.InputDeviceListener`, no polling).
4. Press any button / move a stick -- the "Live input state" line updates in
   real time. This confirms `MainActivity.dispatchKeyEvent` /
   `dispatchGenericMotionEvent` are correctly forwarding into the shared
   `InputMapper`.
5. Drag the on-screen virtual sticks / tap the virtual buttons at the bottom
   of the same screen -- the same live-state line should update, proving
   physical and touch share one pipeline.

## 6. Testing the dummy ProcessLauncher

1. Add a game (any real or placeholder `.exe` URI -- the dummy engine never
   actually executes it).
2. Create a runtime profile in **Library > Runtimes > +** and assign it to
   the game (assignment UI is minimal in this MVP -- set
   `game.runtimeProfileId` directly if the picker isn't wired yet in your
   build).
3. Open the game's Details screen and tap **Play**.
4. Expected: status moves Idle -> Initializing -> Running -> Stopped (exit
   code 0) over about 3 seconds, and the Logs panel fills with
   `[dummy-runtime] tick 1/2/3` and `[stdout]`-tagged lines. This proves
   start, live log capture, PID tracking, and clean exit detection all work
   against a real OS process.
5. To test crash detection, temporarily change the script in
   `DummyRuntimeEngine.launch()` to end with `exit 1` -- the exit status
   should report a non-zero code and the UI should reflect `Stopped(exitCode=1)`.

## 7. Where the future Wine/Box64 integration plugs in

- Implement `RuntimeEngine` (see `domain/runtime/RuntimeEngine.kt`) with a
  class that builds the real `command`/`environment` for
  `ProcessLauncher.launch()` -- e.g.
  `[wineloaderPath, exePath, ...args]` with `WINEPREFIX`, `BOX64_*`, `DXVK_*`
  environment variables, instead of the dummy `sh -c` script.
- Wire that implementation into `LauncherApplication.onCreate()` in place of
  `DummyRuntimeEngine`.
- Implement `InputBridge` (see `domain/controller/InputBridge.kt`) to push
  `XInputState` into the real Wine XInput channel instead of just logging it.
- Extend `GraphicsManager`'s resolved backend into real DXVK/VKD3D
  environment variables when launching (`DXVK_HUD`, `VKD3D_CONFIG`, etc.) --
  the resolution logic (`GraphicsResolution`) already tells you which path
  was chosen and why.

No UI, database, or controller code needs to change for this integration --
that boundary is the point of the `RuntimeEngine`/`InputBridge` interfaces.
