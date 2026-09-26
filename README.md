# WinLauncher MVP

Android launcher infrastructure for running user-owned Windows PC games via
Wine + Box64 + DXVK/VKD3D. **A real `RealRuntimeEngine` now exists behind the
`RuntimeEngine` interface, but no actual Wine+Box64 launch has been verified
against real binaries in this environment -- see "Known limitations" below
before assuming Windows games work.** `DummyRuntimeEngine` remains available
(toggle in Settings) for UI/process-lifecycle testing without any runtime
installed. See `domain/runtime/DummyRuntimeEngine.kt` for exactly what's
real vs. placeholder, and `ARCHITECTURE.md` (delivered separately) for the
full system design this project follows.

## What's real in this MVP

- Full Compose UI: Home, Library, Add Game, Game Details (with Runtime
  assignment dropdown + live Performance strip), Runtime Manager (with
  Installed Components + import), Controller Manager (touch overlay +
  per-profile deadzone/sensitivity/opacity), Settings (engine toggle)
- Room-backed persistence for game/runtime/controller profiles, including
  the assigned runtime surviving app restarts
- SAF-based game file picking with persisted URI permissions (no
  `MANAGE_EXTERNAL_STORAGE`, no root)
- **Real** native GPU detection: actual `vkCreateInstance`/
  `vkEnumeratePhysicalDevices` calls and a real EGL/GLES probe --
  see `app/src/main/cpp/gpu/`
- Mali-first capability tiering (`MaliProfile.kt`) and independent
  DXVK/VKD3D resolution logic (`GraphicsManager.kt`) -- unchanged by the
  real runtime work, and reused by `RealRuntimeEngine` to decide what to
  deploy into the prefix
- Real OS process lifecycle: start/stop/crash-detect/log-capture. Two
  engines use it: `DummyRuntimeEngine` (a harmless `/system/bin/sh` script)
  and `RealRuntimeEngine` (the actual configured Wine+Box64 command --
  see "Known limitations" for what "actual" does and doesn't guarantee)
- `RuntimeInstallationManager`: real SAF import (raw file or `.zip`) into
  app-private storage, real `--version` probing, real DXVK/VKD3D DLL
  deployment into a prefix's `system32`
- Real physical gamepad detection (`InputManager`) and a functional virtual
  touch controller, both feeding one `InputMapper` pipeline with live
  deadzone/sensitivity adjustment and reduced redundant state emissions

## What's a placeholder / still needs work

- `InputBridge` only logs state transitions; wiring it into a real Wine
  XInput implementation (named pipe / shared memory / JNI) is future work
- `stageExecutableLocally()` copies only the selected `.exe`, not sibling
  files under a chosen working directory
- Wine prefix bootstrap (`wineboot`, which creates the standard
  `drive_c` tree) is not automated -- `RealRuntimeEngine.initialize()`
  only creates the prefix *directory*, not an initialized Wine prefix
- The exact Wine+Box64 command line (`RealRuntimeEngine.buildLaunchCommand`)
  is a documented assumption based on a common community pattern, not a
  verified invocation for any specific Wine-Android fork

---

## Known limitations (read before assuming Windows games work)

**1. No real Wine/Box64 binaries have been built, installed, or tested
against this code.** `RealRuntimeEngine` implements real binary discovery,
SAF staging, environment construction, and process supervision -- but
"a process was launched with exit code 0" is not the same as "a Windows
game ran." Building actual Wine-on-ARM64 and Box64 binaries is a large,
separate upstream engineering effort (see the architecture doc's
build-vs-integrate breakdown); this project integrates them, it doesn't
compile them.

**2. Android 10+ (API 29+) blocks executing binaries written to app
storage at runtime.** This is the single biggest real risk to the whole
"import your own runtime" feature (requirement 15). Android enforces W^X
(write XOR execute) on a non-rooted device: a binary an app downloads or
copies into its own private storage at runtime generally **cannot** be
`exec()`'d, even after `chmod +x`. `RuntimeInstallationManager`'s import
mechanism is real and useful for staging binaries and for DXVK/VKD3D
(which are DLLs Wine loads, not binaries Android execs -- no restriction
there), but actually running an imported Wine/Box64 **executable** will
likely fail on modern devices with a SecurityException/"Permission
denied," which `RealRuntimeEngine.launch()` catches and surfaces as a
clear error rather than crashing.

The standard real-world fix (used by Winlator and similar projects) is to
ship Wine/Box64 as `.so` files under `app/src/main/jniLibs/arm64-v8a/`
**at APK build time** (e.g. `libwine.so`, `libbox64.so`) -- Android's
PackageManager extracts these into `ApplicationInfo.nativeLibraryDir`,
which is exec-permitted. That's a build-time packaging change (new
binaries checked into the repo or fetched during CI), not something a
runtime SAF import can achieve. This project does not yet do that.

**3. The Wine+Box64 invocation itself is unverified and fork-specific.**
There's no single universal "run this PE binary" command across
Wine-Android forks -- see the comment on `buildLaunchCommand`.

**Bottom line:** this delivers the integration layer end to end
(discovery -> verification -> staging -> launch -> monitor -> logs -> DXVK/
VKD3D deployment), wired through the exact same `RuntimeEngine` interface
DummyRuntimeEngine already used. Getting an actual Windows game running
requires (a) real Wine-Android + Box64 binaries, (b) packaging them via
jniLibs at build time rather than runtime import, and (c) confirming the
exact invocation against whichever fork is used -- none of which can be
verified without real binaries and a real device, neither of which are
available in this sandbox.

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

1. In **Settings**, switch "Runtime engine" to the dummy test engine.
2. Add a game (any real or placeholder `.exe` URI -- the dummy engine never
   actually executes it).
3. Create a runtime profile in **Runtime Manager > +**, then assign it to
   the game from the Runtime dropdown on the game's Details screen.
4. Tap **Play**.
5. Expected: status moves Idle -> Initializing -> Running -> Stopped (exit
   code 0) over about 3 seconds, and the Logs panel fills with
   `[dummy-runtime] tick 1/2/3` and `[stdout]`-tagged lines. This proves
   start, live log capture, and clean exit detection all work against a
   real OS process.
6. To test crash detection, temporarily change the script in
   `DummyRuntimeEngine.launch()` to end with `exit 1` -- the exit status
   should report a non-zero code and the UI should reflect `Stopped(exitCode=1)`.

## 7. Testing RealRuntimeEngine (without real Wine/Box64 binaries)

Even without real binaries, you can verify the integration layer itself:

1. In **Settings**, make sure "Runtime engine" is set to the real engine
   (the default).
2. Open **Runtime Manager** -- the "Installed components" section should
   show Wine/Box64/Box86/DXVK/VKD3D all as "Not installed", and the
   runtime directory path underneath (`.../files/runtime`).
3. Assign a runtime to a game and tap **Play**. Expected: the exact error
   from requirement 13 -- *"Wine runtime is not installed. Install/download
   a compatible runtime first."* -- confirming `initialize()` genuinely
   checks for the binaries rather than silently falling back to the dummy
   engine.
4. To test the import path with something harmless: tap **Import** next to
   DXVK and pick any small `.zip` you have on-device. It should extract and
   flip to "Installed." This exercises the real SAF-copy + zip-extraction
   code without needing an actual DXVK release.
5. Importing an actual Wine or Box64 **binary** and tapping Play will likely
   surface a SecurityException-based error instead of launching -- see
   "Known limitations" #2 above. That failure is expected on a modern,
   non-rooted device with binaries imported this way, not a bug in the
   verification/staging code.

## 8. Where the future full integration still needs work

- Ship real Wine-Android + Box64 binaries via `app/src/main/jniLibs/` at
  build time (see "Known limitations" #2) instead of relying solely on
  runtime SAF import for the executables themselves.
- Automate Wine prefix bootstrap (`wineboot`) inside
  `RealRuntimeEngine.initialize()`.
- Confirm/adjust `buildLaunchCommand()` against the specific Wine-Android
  fork actually integrated.
- Implement `InputBridge` (see `domain/controller/InputBridge.kt`) to push
  `XInputState` into the real Wine XInput channel instead of just logging it.
- Extend `stageExecutableLocally()` to copy a full working-directory tree,
  not just the single `.exe`.

No UI, database, or controller code needs to change for any of the above --
that's the point of the `RuntimeEngine`/`LogSource`/`InputBridge` interfaces.


Dev by Zero神

