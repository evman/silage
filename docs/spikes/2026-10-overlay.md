# Spike: Compose overlay through mpv overlay-add

Date: 2026-10-11 · Machine: RTX 5090 + Arc A380, KDE Plasma 6.7.5 Wayland, mpv 0.41 · File: synthetic 3840×2160 HEVC Main10, HDR10 (BT.2020/PQ), 24 fps

| Overlay size | fps | p50 | p95 | max | dropped video frames | Smooth to the eye? |
|---|---|---|---|---|---|---|
| 1920×1080 | 60.0 | 5.7 ms | 9.9 ms | 5206 ms (first frame only: Skia/JIT warm-up) | 0 | pending user check |

mpv reported video `bt.2020 / pq`, hwdec `vaapi`. HDR signaled on the display: pending user check.

**Decision:** provisional GO at 1920×1080 on the numbers (60 fps, p95 well under the 33 ms budget, no dropped video frames). Final GO needs the user to confirm by eye, with a real 4K HDR file, that the bar animates smoothly and the TV shows HDR. If they see stutter, re-run at 1280×720; if that also fails, Plan 3 uses the mpv Lua control bar.

Plan 3 note: render the first overlay frame before showing the controls, so the ~5 s warm-up never appears as a frozen bar.
