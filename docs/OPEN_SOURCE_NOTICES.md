# Open Source Notices

This MVP's own code contains no bundled third-party runtime binaries. It does
now contain a real *import mechanism* (`RuntimeInstallationManager`) that lets
a user supply their own Wine/Box64/Box86/DXVK/VKD3D packages at runtime via
SAF -- those files live in the app's private storage after import, are never
shipped in the APK, and remain the user's own responsibility to have obtained
legally. This file is the placeholder that MUST be kept accurate if any of
these components are ever bundled directly in the APK (e.g. via `jniLibs` at
build time) -- regenerate it from the pinned dependency set at that point,
don't hand-maintain it.

| Component | License | Redistribute? | Modify? | Source-disclosure obligation |
|---|---|---|---|---|
| Wine (Android fork) | LGPL-2.1 | Yes, with attribution | Yes | Publish modified LGPL source or link to it |
| Box64 | MIT | Yes | Yes | Attribution only |
| Box86 | MIT | Yes | Yes | Attribution only |
| DXVK | zlib/MIT-style | Yes | Yes | Attribution only |
| VKD3D-Proton | LGPL-2.1 | Yes, with attribution | Yes | Publish modified LGPL source or link to it |
| Mesa (Turnip, Adreno path only) | MIT-style | Yes | Yes | Attribution only |
| Vulkan Loader (Khronos) | Apache-2.0 | Yes | Yes | Attribution + NOTICE |
| AndroidX / Jetpack Compose / Room | Apache-2.0 | Yes | N/A (unmodified) | Attribution |
| org.json | Public-domain-style JSON license | Yes | N/A | None |

Before shipping: pin exact commit/tag per native dependency and re-verify each
LICENSE file at integration time -- forks and licenses can change.
