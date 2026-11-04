# Dolby Vision through a patched KWin (Silage 0.5.2)

## Why

On Linux, Silage's Dolby Vision works only through a root helper that writes Intel
display registers (Arc A380, DisplayPort). On 2026-11-03 a console test proved the
ASUS PG32UCDM also switches to Dolby Vision over **HDMI from an NVIDIA RTX 5090** when
the connector's ordinary `HDR_OUTPUT_METADATA` carries a specific set of values (the
same values its working Windows driver sends). No register writes, no root — but only
the compositor may set that property while the desktop runs, and stock KWin always
derives it from the EDID.

KWin already exists in patched form (`scripts/dv-lab/kwin-6.8.0-experimental-dv.patch`,
540 added lines, ~50 touching KWin's own files): a private D-Bus switch that only
sends the values while a client keeps renewing a lease, and restores ordinary HDR
when it stops. Silage has the client (`ExperimentalDvSession`). Both are prototypes
gated by environment variables, DisplayPort only, one monitor.

## Goals

- Dolby Vision in Silage on **Arch (incl. CachyOS) and Fedora** KDE Plasma 6, any GPU
  whose driver honours `HDR_OUTPUT_METADATA` (NVIDIA, AMD, Intel), DisplayPort or HDMI.
- No root at playback time: no helper, no password prompt.
- Opt-in, reversible, and never breaking a system update.
- Monitors are enabled only when verified (trigger values are per monitor); the hardware
  report (`silage --dv-report`) is how more get added.

## Non-goals

- Ubuntu 24.04 / Kubuntu (Plasma 5.27 has no HDR). Debian later.
- Changing stock KWin behaviour for anything but Silage's lease.
- Full Dolby colour conformance (unchanged from today's limitations).

## KWin patch (`packaging/kwin/`, one patch per KWin minor version)

- Always present in our build (no environment variable); does nothing until asked.
- `dev.silage.KWin.HdrMetadata1` on the session bus (unchanged API): `acquire(output)`,
  `renew(token)`, `release(token)`; leases expire after 5 s without renewal and on the
  caller disconnecting; the output's previous metadata is restored.
- Any connector type (DP and HDMI).
- A small table of verified monitors, matched by EDID maker/product and Dolby Vision
  block, each with its trigger metadata. First entry: ASUS PG32UCDM.
- Only callers whose process executable is Silage's launcher may acquire.
- Built for the distribution's exact KWin version: Arch 6.8.x, Fedora 43 6.7.x.

## Packaging and updates — "fall back, then catch up"

- The patched build keeps KDE's package name `kwin` and the distro's version, with a
  higher release (for example `6.8.2-2.99`). It lives in a **separate opt-in
  repository** (`[silage-kwin]` for pacman, `silage-kwin` for dnf), signed with the
  existing repository key, never in the main Silage repository.
- The package manager installs whichever `kwin` is newest. When KDE ships a new KWin,
  the stock one is briefly newer: the system updates normally (no conflicts, no held
  packages), Dolby Vision pauses and Silage says it's waiting for an update.
- A scheduled GitHub workflow (every 6 h) compares Arch's and Fedora's current `kwin`
  with ours; when newer, it applies the patch to the distro's own package recipe
  (Arch PKGBUILD / Fedora SRPM), builds in that distro's container, runs the patch's
  tests, signs and publishes. If the patch no longer applies or tests fail, nothing is
  published and the maintainers are notified; users simply keep stock KWin.
- Undo: remove the repository and reinstall the distro's `kwin` (one command, offered
  by Silage and install.sh).

## Opt-in

- `install.sh`: on Arch/Fedora + Plasma 6 + a connected verified monitor, asks once
  (default No) to add the `silage-kwin` repository and install the patched KWin.
- Silage Settings → Video: when the hardware is verified but the patched KWin is
  missing, "Enable Dolby Vision" installs it through the system password prompt
  (as updates do) and explains that logging out and in is needed.

## Silage

- Detects the patched KWin by its D-Bus object; with it, the Dolby Vision setting is
  offered for verified monitors on any GPU, and playback uses the compositor session
  (profiles 5, 7, 8 as with the helper). The Intel helper stays for anyone without the
  patched KWin and is retired in a later release.
- Status in Settings: active · waiting for a KWin update · monitor not verified (with
  the report button).

## Upstream

In parallel, a KDE merge request for a general mechanism (a fullscreen client's HDR
mastering metadata passed to the output), citing the HDMI/NVIDIA result. If accepted,
`silage-kwin` is retired.

## Testing

- KWin patch unit tests (existing, extended for HDMI, the monitor table, caller check).
- CI: build both packages for each KWin release before publishing.
- Hardware: RTX 5090 HDMI and Arc A380 DP on the PG32UCDM; DV logo from Silage,
  restore on stop, Silage killed mid-playback, KWin update fall-back and catch-up.

## Calls made without asking (owner said "do whatever is best")

- Fall back instead of hold (hold breaks `pacman -Syu` on Arch).
- Same package name with a higher release in a separate repo, instead of `kwin-silage`.
- Verified-monitor table instead of "any monitor advertising Dolby Vision".
- Caller restricted to Silage's executable.
