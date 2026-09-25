# Windows-on-Android Game Launcher — System Architecture

## 1. Build vs Integrate vs External

**A. Built by us (Kotlin/Compose + thin native glue)**
- Launcher UI (all screens)
- Game library / metadata store (Room or file-based JSON)
- Game Manager (add/edit/delete game profiles)
- Runtime Manager UI (create/clone/edit/delete runtime profiles)
- Controller Manager (physical controller detection UI, virtual touch overlay, per-game controller profile editor)
- Storage Manager (SAF wrapper, persisted URI permissions)
- Settings screens (global + per-game)
- Process lifecycle wrapper (start/stop/monitor the Wine/Box64 process tree, log capture)
- GPU/Vulkan/GLES capability detector (native probe + Kotlin-facing API)
- Performance overlay (FPS/frame time/RAM, CPU via `/proc`, GPU util only where exposed)
- Compatibility database (local + user-editable JSON: game → GPU → API → result)
- License/attribution screen + `OPEN_SOURCE_NOTICES`

**B. Integrated from existing open-source projects (forked/adapted, not rewritten)**
- Wine (Windows API compatibility) — Android-patched fork (e.g. Wine-related work from the Winlator/Box64 community)
- Box64 / Box86 (x86_64/x86 → ARM64 CPU translation)
- DXVK (DX9/10/11 → Vulkan)
- VKD3D-Proton (DX12 → Vulkan)
- Mesa/Turnip only as the *Adreno* Vulkan path — **not** used for Mali
- A Vulkan loader / `libvulkan.so` shim if targeting devices where the vendor driver needs indirection

**C. Remains external / out of scope**
- Vendor GPU drivers themselves (never bundled or redistributed)
- The actual Windows game binaries (user-supplied)
- Anti-cheat, DRM, license servers — explicitly unsupported
- Per-game manual tuning knowledge (crowdsourced compatibility DB, not something we can guarantee)

---

## 2. Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                        UI (Compose)                          │
│  Home │ Library │ Add Game │ Details │ Runtime Mgr │ Ctrl Mgr │
│  Per-Game Settings │ Global Settings │ Perf Overlay │ About   │
└───────────────┬───────────────────────────────────────────────┘
                │
      ┌─────────┼───────────┬───────────────┬───────────────┐
      ▼         ▼           ▼               ▼               ▼
 GameManager RuntimeManager ControllerManager StorageManager PerformanceManager
      │         │                │               │               │
      │         ▼                ▼               │               │
      │   ┌────────────┐   InputMapper           │               │
      │   │  Runtime    │   (physical→XInput,     │               │
      │   │  Process    │    virtual overlay)     │               │
      │   │  Lifecycle  │                          │               │
      │   └─────┬──────┘                          │               │
      │         ▼                                  │               │
      │   GraphicsManager ── GpuDetector (JNI) ─────┘               │
      │         │                                                  │
      │         ▼                                                  │
      │   ┌──────────────────────────────┐                         │
      │   │ Wine ⇄ Box64/Box86 ⇄ DXVK/VKD3D │                       │
      │   └──────────────┬───────────────┘                         │
      │                  ▼                                          │
      │           Android Vulkan/GLES driver ◄──────────────────────┘
      ▼
 Game Profile Store (Room/JSON)
```

---

## 3. Data Flow (Add Game → Launch)

1. User picks `.exe`/folder via SAF → app persists URI permission.
2. `GameManager` creates a `GameProfile` (name, exe path, working dir, icon, runtime ID, resolution, graphics backend, Box64 flags, env vars, controller profile, FPS cap, fullscreen).
3. Profile persisted (Room DB), linked to a `RuntimeProfile` (Wine prefix path, CPU backend, graphics backend).
4. On first add, `GpuDetector` runs once, caches a `GpuCapabilityProfile` device-wide (not per-game).
5. `GraphicsManager` combines `GpuCapabilityProfile` + game's requested backend → resolves actual backend (Vulkan/DXVK, Vulkan/VKD3D, GLES fallback, or blocked-with-reason).

## 4. Process Flow: PLAY → Launch

```
User taps PLAY
   → RuntimeManager.resolveProfile(gameId)
   → GraphicsManager.validate(runtimeProfile, gpuCapabilities)
        ├─ fail → show compatibility error + suggested fallback
        └─ pass → continue
   → StorageManager.grantsExecutablePaths()
   → RuntimeProcessLauncher.start(
         exePath, workingDir, envVars, launchArgs,
         wineforkBinary, box64Binary, dxvk/vkd3d config)
   → ProcessMonitor attaches (stdout/stderr → log ring buffer)
   → ControllerManager binds active pads → InputMapper → XInput socket/pipe into runtime
   → PerformanceOverlay attaches (opt-in)
   → on exit/crash → ProcessMonitor reports code + last N log lines → Game Details screen
```

## 5. Controller Input Flow

```
Physical pad (BT/USB) → Android InputDevice/MotionEvent
        │
        ▼
InputMapper.normalize() → XInput-style struct (sticks, triggers, buttons)
        │
        ▼
Per-game ControllerProfile (remap, deadzone, sensitivity)
        │
        ▼
Bridge into Wine's XInput implementation (named pipe / shared memory / JNI call, depending on fork)

Virtual touch controller → same InputMapper.normalize() entry point
(so physical and touch never diverge downstream)
```

## 6. Graphics/Rendering Flow (Mali-first)

```
GpuDetector (native, Vulkan + GLES queries — NEVER inferred from device model)
   │
   ▼
vendor == "ARM" (Mali)?
   ├─ yes → MaliCapabilityTier (BASIC / MODERN / HIGH_END / UNKNOWN)
   │         based on: supportsVulkan, requiredExtensions, requiredFeatures,
   │         requiredTextureFormats, descriptor limits
   │         → Vulkan preferred → DXVK/VKD3D gated per-check
   │         → OpenGL ES fallback if Vulkan checks fail
   │         → software renderer as last resort (warn: unplayable perf)
   └─ no (Adreno/other) → existing Turnip/Vulkan path unaffected

DX9/10/11 → DXVK.isSupported(gpuCaps)  → independent check
DX12      → VKD3D.isSupported(gpuCaps) → independent check (never inferred from DXVK result)
```

Compatibility record shape:
```json
{ "game": "...", "gpu": "Mali-G715", "api": "Vulkan",
  "dxvk": "supported", "vkd3d": "unknown", "result": "experimental" }
```

---

## 7. Repository Structure

```
app/
 ui/{home,library,addgame,details,runtime,controller,settings,overlay,about}/
 game_manager/
 runtime_manager/
 controller/
 storage/
 performance/
 graphics/
   GpuDetector.kt
   GraphicsManager.kt
   GraphicsCapabilities.kt
   MaliProfile.kt
 settings/
native/
 jni/  (thin JNI boundary classes only)
 gpu/
   gpu_detector.cpp
   vulkan_detector.cpp
   gles_detector.cpp
 runtime/
   process_launcher.cpp
 CMakeLists.txt
runtime/            (integrated open-source binaries/scripts)
 wine/
 box64/
 dxvk/
 vkd3d/
docs/
 OPEN_SOURCE_NOTICES.md
 ARCHITECTURE.md
```

## 8. Android Permissions

- `INTERNET` — only if a compatibility-DB sync feature is added (optional, off by default)
- No `MANAGE_EXTERNAL_STORAGE` — use SAF + persisted URI permissions instead
- `FOREGROUND_SERVICE` (+ `FOREGROUND_SERVICE_SPECIAL_USE` on Android 14+) — to keep the runtime process alive while overlay/controls are active
- Controller input needs no special permission (standard `InputDevice` APIs)
- No root, no `WRITE_SECURE_SETTINGS`, no shell-exec-from-network permission of any kind

## 9. NDK/JNI Interface Surface

| Kotlin-facing class | Native responsibility |
|---|---|
| `GpuInfo` | vendor/renderer/model strings, Vulkan/GLES version |
| `VulkanCapabilities` | instance/device creation, extensions, features, formats |
| `OpenGLCapabilities` | GLES version, extensions, framebuffer/texture limits |
| `RuntimeGraphicsConfig` | resolved backend + reason, passed into launch args |
| `ProcessLauncher` | fork/exec Wine+Box64 chain, pipe stdout/stderr, PID tracking |
| `InputBridge` | delivers normalized XInput struct into runtime's input channel |

Each gets its own `.cpp`/`.h` pair — no monolithic JNI god-class.

## 10. Dependency / License Table

| Component | Repo (type) | License | Redistribute? | Modify? | Source-disclosure obligation |
|---|---|---|---|---|---|
| Wine (Android fork) | community Android-Wine fork | LGPL-2.1 | Yes, with attribution | Yes | Must publish modified LGPL source or link to it |
| Box64 | community project | MIT | Yes | Yes | Attribution only |
| Box86 | community project | MIT | Yes | Yes | Attribution only |
| DXVK | community project | zlib/MIT-style | Yes | Yes | Attribution only |
| VKD3D-Proton | community project | LGPL-2.1 | Yes, with attribution | Yes | Must publish modified LGPL source or link to it |
| Mesa (Turnip, Adreno only) | community project | MIT-style | Yes | Yes | Attribution only |
| Vulkan Loader | Khronos | Apache-2.0 | Yes | Yes | Attribution + NOTICE |

Action items before shipping: pin exact repo commit/tag per dependency, verify current LICENSE files at integration time (licenses/forks change), and generate `OPEN_SOURCE_NOTICES.md` programmatically from the pinned set — don't hand-maintain it.

## 11. MVP Roadmap

1. Compose shell: Home, Library, Add Game, Details, Settings (no runtime yet)
2. Game profile CRUD + Room persistence + SAF file picking
3. Runtime profile CRUD (data model only, no real Wine/Box64 yet)
4. `GpuDetector` native module (real Vulkan/GLES queries, cached device-wide)
5. Controller detection + `InputMapper` → XInput struct (log-only sink first)
6. Virtual touch overlay (basic layout, no save/customize yet)
7. `ProcessLauncher` abstraction wired to a no-op/dummy binary to prove lifecycle + logging works
8. Logs/error screen wired to `ProcessMonitor`
9. **Milestone**: swap dummy binary for real Wine+Box64 chain on one test title
10. Add DXVK, then VKD3D, gated behind capability checks from step 4
11. Per-game graphics/controller settings UI on top of working backend
12. Compatibility DB (local JSON first, sync later)

## 12. Risks & Limitations

- Mali driver quality varies wildly by OEM/Android version — capability detection reduces but doesn't eliminate crashes
- VKD3D/DX12 support on mobile Vulkan drivers is the weakest link; treat as experimental for the whole MVP phase
- Wine-on-Android forks are community-maintained with inconsistent update cadence — pin versions, don't track `main`
- Thermal throttling on sustained loads will dominate real-world performance more than raw GPU capability
- Scoped storage changes across Android 13/14/15 affect executable/file access — test each target SDK explicitly, don't assume SAF behavior is stable across versions
- Legal: this architecture is sound only as long as no copyrighted binaries/drivers are bundled and no DRM/anti-cheat bypass is implemented — keep that boundary hard in code review, not just docs
