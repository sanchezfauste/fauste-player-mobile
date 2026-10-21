# Release measurements

The spec's Phase-5 targets — cold start < 2 s, memory < 150 MB, CPU idle
< 2 %, 10 h screen-off playback — measured **where the emulator allows**,
with methodology stated and the emulator-vs-hardware line drawn honestly
(the plan's Ruling 7: *measurements, not vibes*). All numbers below come
from runs on **2026-10-21** against the artifact that ships: the
**R8-minified, resource-shrunk, TEST-keystore-signed release APK**
(`composeApp-release.apk`, see [signing.md](signing.md) for the test
keystore's role).

## Environment

| | |
|---|---|
| Host | KVM-accelerated x86_64 Linux |
| Guest | AVD `fauste-test`, Android 14 (**API 34**, `google_apis;x86_64`), headless, audio ON, swiftshader GPU |
| Build under test | `:composeApp:assembleRelease` — R8 8.13.19, shrinkResources on, signed with the local TEST keystore (21.2 MiB APK vs 39.9 MiB debug) |
| Workload | Seeded store (one 30 s uncompressed PCM WAV, one playlist entry); playback driven through the engine's own loopback HTTP remote (`adb forward` + curl) — the same seeding and drive the app smoke uses |
| State assertions | every window greps `/api/v1/state` for the expected `transport` before its measurement is taken |

Emulator context that bounds what these numbers mean: the guest was under
visible memory pressure during the runs (`kswapd0` at ~31 %, `system_server`
~50 %, aggregate guest CPU ~96 % — the `google_apis` image's background
services plus audio emulation). **Absolute figures are not
device-representative**; deltas between app states on the same booted guest
are the meaningful part, and each section says which it is.

## 1. Cold start (process start → first frame)

**Methodology.** Five iterations: `am force-stop`, clear logcat,
`am start -W -n org.fauste.player/.MainActivity`, then parse the logcat
timeline (`-v time`) — `ActivityManager: Start proc …:org.fauste.player` to
`ActivityTaskManager: Displayed org.fauste.player/.MainActivity +Xms`. The
`Displayed` timestamp is the first frame drawn; the delta from the process
start line is the measured cold start. `am start -W`'s `LaunchState: COLD`
was asserted on every run (no warm/HOT contamination), and one warm-up
launch preceded the series so no run carries first-launch ART/dexopt work.

| Run | LaunchState | process start → first frame | `am -W` TotalTime |
|---|---|---|---|
| 1 | COLD | 1206 ms | 1200 ms |
| 2 | COLD | 1158 ms | 1179 ms |
| 3 | COLD | 1196 ms | 1207 ms |
| 4 | COLD | 1180 ms | 1219 ms |
| 5 | COLD | 1177 ms | 1185 ms |
| **median** | | **1180 ms** | 1200 ms |
| range | | 1158–1206 ms | 1179–1219 ms |

**vs the target:** ~1.2 s cold start on the API 34 emulator — under the
spec's 2 s, with ~40 % headroom, on a *busy, swapping, software-rendered
guest*. A physical device's first frame should not be assumed from this
number; the maintainer re-measures with the same commands (the two logcat
lines above are device-legal too).

## 2. Memory (`dumpsys meminfo`)

**Methodology.** After seeding, one warm-up launch, then a cold relaunch;
`dumpsys meminfo org.fauste.player` captured in three states, each asserted
via the remote before capture: *idle with queue loaded* (restored session,
transport paused, screen on), *playing* (screen on), *stopped* (transport
stopped via the remote, screen on). PSS is a point-in-time snapshot — GC
timing moves it a few MB between captures, so the honest reading is the
order of magnitude, not the deltas.

| State | TOTAL PSS | TOTAL RSS | Java heap | Native heap |
|---|---|---|---|---|
| Idle, queue loaded | **62.3 MB** | 158.7 MB | 23.2 MB | 24.5 MB |
| Playing | **51.8 MB** | 151.9 MB | 6.8 MB | 23.7 MB |
| Stopped | **49.1 MB** | 153.8 MB | 6.3 MB | 22.7 MB |

(The Java-heap drop between the first and later captures is GC/settle
variance, not a playback effect — the first capture sat ~12 s after the
process's cold start.)

**vs the target:** peak measured PSS **62 MB** against the spec's 150 MB —
met with ~2.4× headroom, including the whole Rust engine's heap (native
column), the Compose UI and media3's session stack. One uncompressed WAV's
audio buffers are in the playing figure; large libraries (a compressed
catalog) scale the native heap — re-measure with a real library on
hardware.

## 3. CPU

**Methodology, and why two tools.** `dumpsys cpuinfo`'s per-process lines
round to whole percent and read `+0%` for this app in every state — a
useless number. The app's own cost was therefore measured directly from
`/proc/<pid>/stat` (`utime+stime`, clock ticks at the guest's `USER_HZ=100`,
i.e. 10 ms jiffies) sampled at the window edges of four 20 s windows; each
playing window re-asserts `transport: playing` first. The screen-off window
asserts `mWakefulness=Asleep` (real `KEYCODE_POWER`, not an assumption).

| Window (20 s each) | jiffies | ≈ % of one core |
|---|---|---|
| Playing, screen on, app visible | 210 | **10.5 %** |
| Playing, **screen off** | 61 | **3.1 %** |
| Playing, screen on again | 166 | 8.3 % |
| Stopped, screen on, app visible | 165 | 8.3 % |

What the deltas say (the part that generalizes):

- **The lifecycle-aware collection's battery claim is visible in the
  numbers**: with the screen off and playback running, the app's own CPU
  drops to ~⅓ of the screen-on figure (~3 % vs 8–10 % of one core). The
  screens' state collection freezes at `ON_STOP` (no recomposition at the
  state layer's ~10 Hz position cadence) while the engine, audio and the
  service's keep-awake feed keep running — playback itself does not
  degrade, only the UI work stops.
- The screen-on-playing cost (8–10 %) is dominated by the visible UI
  recomposing on position ticks; the stopped-foreground figure (8.3 %) is
  the same visible-app collection at the state layer's idling cadence plus
  the guest's own overhead.
- The **spec's "CPU idle < 2 %"** as an absolute device number cannot be
  certified on this guest (a software-rendered, swapping x86 emulator under
  KVM inflates everything); what *is* verified here is the design property
  that drives idle cost — the screen-off collapse above. Re-measure on
  hardware with the same jiffies method plus `dumpsys cpuinfo` for the
  system view.

## 4. Battery — the honest non-measurement

**No battery number in this document comes from the emulator, because none
can.** The emulator's power source is virtual (AC-plugged, synthetic drain):
its `dumpsys batterystats` and Battery-Historian-style estimates would be
fabrications, not measurements. The spec's **10 h screen-off playback target
needs real hardware** the maintainer owns. The methodology to run on a
device:

1. Charge to a stable level, unplug, then reset the stats collection:
   `adb shell dumpsys batterystats --reset`.
2. Open the app, start playback, turn the screen off, note the wall clock
   and the battery level (`adb shell dumpsys battery | grep level`).
3. Leave it playing for a timed window (≥ 2 h for a meaningful rate; the
   seeded single 30 s track loops poorly — use a real library and a Repeat
   setting, or a longer file).
4. Record the ending level, then pull the report:
   `adb shell dumpsys batterystats org.fauste.player > battery.txt`.
5. Compute %/hour from the window and extrapolate to the 10 h target; the
   report's `Foreground services`/wake-log sections attribute the drain
   (the foreground `mediaPlayback` service and the audio stream should
   dominate; a healthy `delta` shows the screen's absence, per §3's
   measured ⅓ collapse).

For calibration: the same run on the emulator still produces a *valid
`batterystats` report structure* (useful to check the command shape and the
foreground-service attribution), but its energy numbers are meaningless —
treat them as a dry run of the method only.

## 5. Release-artifact verification (the measurements' sibling evidence)

The release APK was verified end-to-end on the same guest, not just built —
the app smoke's full assertion set (19 PASS) ran against the R8-minified,
test-signed APK: install → launch → no crash lines; FFI state/play/pause/stop
over the loopback remote (the UniFFI/JNA surface survives R8 — the keep
rules in `composeApp/proguard-rules.pro`); the media notification and
foreground service live mid-play; a **real `adb emu gsm call` interrupt**
pausing playback and the ended call resuming it (the audio-focus wiring); a
**real notification tap** resuming the existing activity instance; the stop
dance tearing the service down; and `am crash` → process death through the
chained handler → a complete report in `files/crash/` → clean relaunch with
no second report. `apksigner verify -v` on the release APK: `Verifies`
(v2 scheme, the Play-mandatory one at minSdk 26 — see
[signing.md](signing.md)).

Artifact sizes: debug APK 39.9 MiB → release APK **21.2 MiB** (R8 +
shrinkResources; the single `classes.dex` is 3.0 MiB), release AAB
**21.0 MiB**. The native cdylib (the Rust engine) is the irreducible
weight on both: `libfp_backends_mobile.so` is ~8.4 MiB per ABI.

Raw captures from all runs (logcat timelines, `meminfo`/`cpuinfo` dumps,
jiffies tables) are retained with the session's tooling; the tables above
quote them verbatim.
