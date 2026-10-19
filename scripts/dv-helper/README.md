# Experimental DV helper for stock KDE

This is the opt-in playback backend included in Silage 0.3.0 Linux downloads. It keeps
the installed KWin compositor and desktop configuration unchanged. The earlier
[patched-compositor experiment](../dv-lab/README.md) remains a separate reference.

Supported configuration is deliberately narrow: **Intel Arc A380 / i915,
ASUS PG32UCDM with the exact tested Dolby capability block, DisplayPort,
active 3840×2160 10-bit PQ / BT.2020 RGB output**. Silage requests the signal only
for fullscreen Profile 5, 7 or 8 playback with actual renderer RPU reshaping on that
monitor. HDMI, other GPUs and other monitor profiles are not enabled.
Profile 7 FEL additionally requires an active enhancement-layer GPU pass.
Profile 7 MEL instead requires decoder confirmation that residual picture data
is not needed, plus actual RPU reshaping. The private renderer below supplies
that distinction; an unknown/missing residual status does not bypass the FEL
check. Older renderers retain HDR10 fallback instead of forcing monitor mode.
The display list must contain only that fullscreen monitor, avoiding activation
while Wayland is still placing the playback window across outputs.

This reproduced the monitor mode activation and visually normal Profile 5
playback reported in the lab. It does not establish Dolby certification, complete
Dolby content mapping, creative-trim processing, or reference color accuracy.

## Enable the released app

The normal Linux download includes the helper and playback runtime. Enable
**Settings → Video → Experimental Dolby Vision**, then restart Silage. System
packages install the root-owned helper/policy; portable builds include a fixed
setup that installs and starts only the helper during the first authorization
(or after its bundled payload changes). No separate helper download or compiler
is needed by users. See [the setup and compatibility guide](../../DOLBY_VISION.md).

The app and all media/network processing stay unprivileged. Only the fixed
helper/setup process runs as root, with ordinary administrator authorization.
The setup accepts no source/destination paths or commands from the app. It
installs only the two bundled helper payloads and a fixed auth_admin policy.
There is no passwordless rule or persistent root service.

## Build and stage from source

Requires Python 3.11+, libdrm development files, a C compiler, polkit/pkexec and
Intel GPU Tools (`intel_reg` at `/usr/bin/intel_reg`). On the tested Arch system,
Intel GPU Tools is supplied by `intel-gpu-tools`. Build the current Silage source; published 0.2.0 binaries predate this client.

```sh
# Build a reviewable staged tree first; this command needs no root access.
bash scripts/dv-helper/install.sh --stage

# Install the helper and its authorization policy using sudo.
bash scripts/dv-helper/install.sh

# Run the new development app on your ordinary KDE desktop.
SILAGE_EXPERIMENTAL_DV=helper /path/to/new/silage/bin/silage
```

`SILAGE_EXPERIMENTAL_DV=1` also selects the helper. Leaving the variable unset
preserves ordinary playback. The earlier compositor backend is selected only
with `SILAGE_EXPERIMENTAL_DV=compositor` and requires its separate KWin patch.

Installation adds only:

```text
/usr/libexec/silage-dv-helper/silage-dv-helper
/usr/libexec/silage-dv-helper/kms-snapshot
/usr/share/polkit-1/actions/dev.silage.dv-helper.policy
```

These files and their parent directories must be protected from non-root
writes. The helper refuses an unprotected installation. No compositor package,
system service, sudoers exception, or passwordless authorization rule is added.

When the opt-in app opens, KDE asks once for administrator authorization for
this helper process. The app stays unprivileged. Canceling or failing
authorization leaves ordinary HDR available and does not prompt again during
movies in that app run. The authorized process stays alive with idle heartbeats
while browsing and is reused across players. Closing a player restores ordinary
HDR; closing the app ends the helper process. Startup authorization alone never
activates the signal: all source, renderer and display checks still apply.
Press **I** during playback to see the **DV helper** row: signal active,
authorization pending, normal HDR after failure, or the eligibility condition
that is missing. A monitor mode switch alone is not proof of Dolby conformance.

On a movie's details page, **Versions (N)** shows the actual copies available
on the server, including the Dolby profile when supplied by the server or its
DV codec string. Selecting a copy retains its file id even when two choices
have the same quality label. A single-copy title shows its available version;
Profile 5 cannot be selected if the server only has a Profile 8 file.

## Protocol and restoration

The app starts the fixed installed executable with `pkexec`. Its private stdin
accepts only `enable DP-N`, `renew`, `release`, and `quit`, with bounded ASCII
input. Stdout reports versioned JSON events. The caller cannot supply metadata,
addresses, file names or programs to execute.

The helper discovers a single A380 and checks the EDID, active pipe/CRTC, 4K
mode, bit depth, VT, PQ/BT.2020 KMS request, packet enable bit and register
readback before writing. Its only candidate is the fixed 36-byte packet
captured from the working Windows configuration. A global lock prevents two
helper instances owning the GPU simultaneously.

An independent, detached guardian owns all hardware writes. The app renews once
per second; the guardian ends the signal after five seconds without renewal.
Application EOF, relay process death, source/fullscreen changes, and normal
close also restore ordinary HDR. Restoration is armed before the first write
and checked by readback, including recovery from partial writes. It uses the
current compositor metadata rather than blindly restoring an outdated blob.

The guardian checks output state twice per second. If stock KWin rewrites the
known ordinary HDR packet without changing the validated display configuration,
the guardian reapplies the candidate. Unknown packets or configuration changes
end the session. It never writes a saved packet into a reassigned pipe or VT.
Disconnects can make restoration unverifiable; those are reported as errors.

Direct register access cannot be synchronized fully with compositor modesets.
Killing the guardian itself with SIGKILL also bypasses its cleanup. These are
reasons to keep this backend experimental and hardware-restricted. It must not
be presented as a general Dolby Vision implementation for Linux.

## Verification

```sh
python3 -m unittest discover -s scripts/dv-helper -p 'test_*.py'
./gradlew :app:test --tests '*ExperimentalDvHelperTest'
sudo /usr/libexec/silage-dv-helper/silage-dv-helper --probe
```

The unit tests cover malformed input, heartbeat expiry, EOF, relay SIGKILL,
partial writes, compositor resets, changed VT/pipe, restoration to updated HDR
metadata, slow/canceled authorization, app lifecycle and the real stdin protocol.

The opt-in hardware tests use pinned official Dolby Profile 5 and 8.1 samples from
the [DV lab](../dv-lab/README.md). They perform privileged signal writes through
the installed helper; run only on the supported configuration with HDR enabled.
The JUnit test uses existing `sudo -n` authorization to avoid a dialog during
automation; the application itself always uses pkexec.

```sh
WAYLAND_DISPLAY=wayland-1 \
SILAGE_DV_HELPER_TEST_CLIP=/path/to/Dolby-Profile5-4K.mp4 \
  ./gradlew :app:test --tests '*ExperimentalDvHelperNativeTest' --rerun

WAYLAND_DISPLAY=wayland-1 python3 scripts/dv-helper/hardware_smoke.py \
  --clip /path/to/Dolby-Profile5-4K.mp4 --output /tmp/helper-recovery.json
```

On 2026-10-19 the actual application helper client passed on the original stock
KWin desktop: five signal readback checks during Profile 5 playback, VAAPI,
zero dropped frames, and verified restoration after client close. Two separate
hardware recovery trials passed for a missing heartbeat and SIGKILL of the root
relay. Normal HDR was verified again after each test. Evidence on the test PC:

```text
/home/admin/Videos/Silage-HDR-Tests/Helper-Stock-KWin-App-Test.xml
/home/admin/Videos/Silage-HDR-Tests/Helper-Stock-KWin-App-Test.log
/home/admin/Videos/Silage-HDR-Tests/Helper-Stock-KWin-Recovery-01.json
```

Profile 8 support was subsequently verified with Dolby's official 4K Profile
8.1 Sol Levante sample, SHA-256
`d054a818de665bc3737a4c5b1662262929ed777b102ea3539afb050fcb4d2cae`:
[official source](https://github.com/DolbyLaboratories/dolby-vision-contents/blob/main/SolLevante_Netflix/BL_RPU_dvhe-08-mapDynamic1000-81_3840x2160%4024fps_0_6313.mp4).
The actual app client passed five packet readback checks, VAAPI with zero
dropped frames, and restoration on close. Evidence:

```text
/home/admin/Videos/Silage-HDR-Tests/Helper-Stock-KWin-Profile8-App-Test.xml
/home/admin/Videos/Silage-HDR-Tests/Helper-Stock-KWin-Profile8-App-Test.log
```

Profile 8 HDR10 fallback without renderer RPU processing does not activate the
helper. This widens source eligibility, not the
GPU/display/transport allowlist, and does not establish full Dolby conformance.

## Profile 7 private playback runtime

The original installed mpv 0.41.0 / libplacebo 7.360.1 build skipped the tested
Profile 7 file's residual metadata. The private runtime adds enhancement-layer
decoding/pairing, GPU NLQ composition and reshaping using pinned upstream code:

- mpv `6a262d11f63733d37e3ee6d5fafcb1be1ef83952`
- libplacebo `ccb74c6c13b1676e3af368cbe9281bfe649c17c6` (7.377.0)

The pinned mpv source also carries
[`mpv-dovi-residual-status.patch`](mpv-dovi-residual-status.patch). Its private
`video-out-params/dolby-vision-residual` property reports `required` or
`not-required` from libplacebo's decoded NLQ classification. A scalar is copied
while frame metadata is owned, avoiding property access through a stale frame
pointer. The build/packager checks the exact patch, records its hash, and bundles
it with the runtime's source information.

Build and bundle it into a development app image:

```sh
bash scripts/dv-helper/build_runtime.sh
./gradlew :app:createDistributable -PappVersion=0.3.0
python3 scripts/dv-helper/package_runtime.py --runtime build/dv-runtime/install \
  --image app/build/compose/binaries/main/app/silage
```

The build uses Meson/Ninja, shaderc, Vulkan/OpenGL, libass, Lua, PipeWire and
FFmpeg development dependencies. The tested binary uses the host FFmpeg 9.0.2
ABI; it is not a universal binary for older Linux distributions. Other targets
need a matching build/dependency set. No compositor or system library is replaced.
The script runs 16 libplacebo and 42 mpv tests before completing the build.

The two shared libraries are copied under the app's `lib/app/dv-runtime`, with
`$ORIGIN` library lookup, source revision/hash metadata and license files. The
app image's private-runtime property tries these libraries first and falls back
to the system libmpv if their native dependencies cannot load. It does not require a global
`LD_LIBRARY_PATH` or a system mpv upgrade. Optional Linux package builds can set
`SILAGE_LINUX_DV_RUNTIME=/absolute/path/to/build/dv-runtime/install` when calling
`scripts/package.sh`; the release CI exports a verified bundle with `release_runtime.py` and sets
`SILAGE_LINUX_DV_BUNDLE` to include it in every Linux package. Local builds without
either variable use their distribution's runtime.

An eight-second original-video excerpt from the affected Profile 7 FEL movie
passed the actual application client test: VAAPI, zero dropped frames, five
candidate packet checks, and restoration on close. Disabling enhancement-layer
processing removed the GPU pass and released the helper; reenabling processing
reacquired it. This verifies that the helper follows rendered enhancement data
rather than the source's Profile 7 label alone. Cached redraw passes do not
qualify. **I → Playback Info → Enhancement layer** reports the current GPU pass.

The Wolf of Wall Street uses MEL with trivial NLQ parameters. The original
authenticated server stream passed the updated app client test with zero
dropped frames and helper activation. Stripping Dolby metadata released the
signal; restoring metadata reacquired it. Its HUD reports
**MEL · no extra picture layer required**, rather than incorrectly reporting an
inactive enhancement layer. FEL still requires its actual GPU composition pass.
This distinction is per decoded frame, never inferred from a movie name.

Evidence on the test PC:

```text
/home/admin/Videos/Silage-HDR-Tests/Profile7-Private-Runtime-Renderer-AB.json
/home/admin/Videos/Silage-HDR-Tests/Helper-Stock-KWin-Profile7-App-Test.xml
/home/admin/Videos/Silage-HDR-Tests/Helper-Stock-KWin-Profile7-App-Test.log
/home/admin/Videos/Silage-HDR-Tests/Profile7-MEL-Original-Stream-App-Test.xml
/home/admin/Videos/Silage-HDR-Tests/Profile7-MEL-Original-Stream-App-Test.log
```

The native test accepts a private Profile 7 excerpt only with an explicit
`SILAGE_DV_HELPER_TEST_PROFILE7_SHA256` matching its bytes. Private excerpts,
authenticated URLs and credentials are not committed or bundled. Enhancement
processing and monitor signal activation still do not establish full Dolby
content-mapping/creative-trim conformance or measured color accuracy.

## Remove

Close any experimental playback and unset the environment variable first. The
regular app and desktop do not depend on these optional files.

```sh
sudo rm /usr/libexec/silage-dv-helper/silage-dv-helper \
        /usr/libexec/silage-dv-helper/kms-snapshot \
        /usr/share/polkit-1/actions/dev.silage.dv-helper.policy
sudo rmdir /usr/libexec/silage-dv-helper
```

## Outstanding content-mapping and transport validation

The current renderer processes reshaping, FEL residual composition and some
brightness metadata; it does not implement Dolby L2/L8 creative trims. The
helper sends a fixed captured signaling packet rather than forwarding per-scene
creative adjustments. Monitor activation and actual enhancement GPU passes do
not establish complete Dolby content mapping, reference color accuracy or
standards-compliant LLDV/TV-led output. Controlled metadata-response tests, a
verified Dolby reference pipeline and instrumented measurements remain necessary.
See [the validation plan](../../DOLBY_VISION.md#what-remains-to-validate).
