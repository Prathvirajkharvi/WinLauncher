# Open Source Notices

This MVP itself has no bundled third-party runtime binaries yet (no Wine, Box64,
DXVK or VKD3D are integrated in this codebase). This file is the placeholder
that MUST be kept accurate once those are added -- regenerate it from the
pinned dependency set at integration time, don't hand-maintain it.

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
