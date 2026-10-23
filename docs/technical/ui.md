# User interface (`composeApp`)

Compose Multiplatform over the shared state layer, following the spec's
unidirectional data flow: screens subscribe to ViewModel `StateFlow`s
and emit intents; ViewModels wrap the `PlayerController`,
`QueueManager` and repositories; nothing below the FFI knows the UI
exists, and no composable ever calls the engine.

## ViewModels and the state mapping

Each screen has one ViewModel with one `uiState` stream; intents are
plain `on*` functions launching on the injected command dispatcher.
The engine snapshot reaches the UI through `PlaybackState.fromEngineState`
— the plan-mandated pure projection of the FFI's `EngineState`
(status enum, position, duration, current track, the two entry ids,
volume, backend id), unit-tested without any engine. The state layer
arrives as Kotlin `Lazy` handles (Koin `inject()`) so the heavy first
engine resolution happens inside the first dispatcher coroutine — the
graph's off-main rule, held by construction. PlayerScreen (transport,
seek, volume, up-next), LibraryScreen (the queue rows, client-side
search, the SAF import, tap-to-jump) and SettingsScreen (the backend
picker, remote status, volume) hang off a bottom navigation
(`AppNavHost`); routes are a sealed class. The honest limits the state
layer documents are surfaced, never papered over: a duration of `0.0`
renders as "—", and the up-next line shows an unknown-marker key (not a
fake title) when an armed row is not in the queue flow.

## The seek/volume throttle

The sliders fire continuously during a drag and carry no drag-end
callback, so **commit-at-stop is inferred**: each tick publishes the
pending value into the UI instantly (the bar tracks the finger with no
engine round-trip) and re-arms one trailing-debounce commit
(`DRAG_COMMIT_DEBOUNCE_MILLIS = 200`). A continuous drag reaches the
engine zero times; ~200 ms of silence reads as "drag stopped" and
exactly one commit with the latest value goes out — flooding the
conductor's queue with per-tick commands would risk `Busy`-dropping
the final value, the one that matters.

After an **accepted** commit the pending value stays until the
engine's polled position reaches it (settle tolerance, bounded by a
settle timeout so a wedged engine cannot pin the bar) — the accepted
target is more truthful than the stale poll while the conductor
applies the seek. A `Busy`/failed commit clears the pending value at
once — never a lying seek bar — and the outcome lands in the error
channel. A dedicated test (`d45c0b1`) locks the constants to
`PlayerViewModel`'s so no screen can drift them.

## Lifecycle-aware collection

The screens' state streams are collected through
`collectUiStateWithLifecycle()`, an expect/actual seam in commonMain —
not plain `collectAsState()`. On Android the actual delegates to
`collectAsStateWithLifecycle` (`androidx.lifecycle:lifecycle-runtime-compose`,
2.9.4, the exact line already on the classpath): collection freezes at
`ON_STOP` and resumes from the latest state at `ON_START` (StateFlow
conflation — missed values are skipped, never replayed). The seam
exists because the service's keep-awake gate deliberately keeps the
state layer emitting at 10 Hz while backgrounded and playing —
without the gate a backgrounded composition recomposed all night; the
measured effect is the screen-off CPU collapse in
[release measurements](../release/measurements.md).

## The media session service

`PlaybackService` extends media3's `MediaSessionService` and publishes
one `MediaSession` whose `Player` is `FaustePlayerAdapter` — a
`SimpleBasePlayer` over the Koin state layer (no ViewModel anywhere in
the bridge: they are UI-scoped and die with the screen). The mapping
rules live in the pure, host-tested `SessionPlaybackSnapshot`; the
adapter only translates. Available commands are exactly the engine
surface — play/pause, stop, in-item seek, previous (the deck's
crossfade-back), set/get volume — and deliberately no next, because
advancing is the engine's own transition, not a queue command. The
**publish hop** — collection on the background scope, the volatile
write plus `invalidateState` on `Dispatchers.Main.immediate`, because
media3 throws off the application looper — and the boot window are
covered in [Threading](threading-and-realtime.md), including why both
were on-device finds the host lanes missed.

Lifecycle (Ruling 5): the app-side `PlaybackServiceStarter` starts the
service on the engine's first distinct Playing **observation** (not a
play command — a Busy-dropped play must not start a service, and
remote-driven play must get its notification); media3 promotes to
foreground with the `mediaPlayback` type and keeps the paused
notification; the engine's Stopped is observed at
`onUpdateNotificationAsync` — the single notification funnel — which
runs the **safe stop dance** (minimal shutdown notification →
`startForeground` → `stopForeground(REMOVE)` → pinned-id cancel →
`stopSelf`) to satisfy the API 26–30 obligation a bare `stopSelf()`
after `startForegroundService` would crash. A sticky restart after a
process kill exits quietly: nothing can be playing in a fresh process
([Persistence](persistence.md)), and the pre-kill notification is
cancelled. The notification itself is the library's
(`DefaultMediaNotificationProvider`, configured — channel id, pinned
notification id, the app's own small icon — not replaced), with a Stop
button via `setMediaButtonPreferences`.

## Audio focus

Focus lives in the service (Ruling 1): media3 runs focus through
ExoPlayer, which this native-engine bridge deliberately is not. The
policy is a **pure state machine** — `FocusPolicy`, zero Android
imports, the full event × state matrix host-pinned by `FocusPolicyTest`
— with `AudioFocusController` as plumbing over it; its actions ride
the same controller commands the UI uses, so a focus pause reaches the
UI like any other pause:

| Focus event | Action |
|---|---|
| first Playing observation | request focus (the acquisition hook is the service's own observation, not the play command) |
| `FocusLost` | pause — no auto-resume ever follows a full loss |
| `FocusLostTransient` | pause + arm the resume-when-regained flag — only if the engine was playing at loss time; a user pause later cancels the flag (never startle the user with a resume) |
| `FocusLostTransientCanDuck` | duck the engine volume to 0.2× the model's current level through the same `SetVolume` path; a re-duck while ducked is a no-op (the duck is a multiply; re-applying compounds) |
| `FocusGained` | resume only if the transient flag is set, plus unduck |

The duck restores on every exit from the ducked states — the model's
volume survives Stop, so a duck left un-restored would bleed into the
next playback at 0.2× (the factor is a documented
constant-not-yet-a-setting). Verified live on the emulator with a real
`adb emu gsm call` interrupt (call pauses, ended call resumes).

## The Nocturne theme

`ui/theme/Color.kt` copies the **spec's Nocturne block verbatim**
(Phase 4, Ruling 1): the hex values, their oklch provenance comments
and the semantic names, exactly as written in the design spec. The
desktop repo's live `theme.rs` has **drifted** (its accent is now
lavender, not the Fauste teal `#00BFA6`) — the ruling binds the mobile
to the spec, not the desktop's current palette, and the file header
says renaming or re-hexing an entry is a spec change, not a cleanup.
Typography is Inter (UI) — bundled under `composeResources/font/`
with its OFL notice (`composeResources/files/inter_ofl.txt`), loaded
through CMP's resource runtime. The timecode deliberately falls back to
the platform's `FontFamily.Monospace`: no licensed JetBrains Mono TTF
exists in either repo, and the recorded Phase-4 ruling chose the
platform monospace over bundling an unverified font. Dark-only.

## Fluent i18n

UI strings are Fluent `.ftl` bundles — **en-US is the source**, es-ES
the translation (spec §4) — under
`composeApp/src/commonMain/composeResources/files/locales/`, parsed by
Fluently (the Maven-Central Kotlin Fluent implementation; the spec's
"fluent-kotlin" hint is not published there — the catalog note records
the verification). The `Strings` seam resolves keys through
`FluentStrings`: locale bundle → en-US fallback → the raw key id
(never a crash, never an invented string). Any Spanish system locale
(`es`, `es-*`) reads the one es-ES bundle — a documented, deliberately
coarse mapping. The **key-completeness guard** (`FluentStringsTest`)
walks every `StringKeys` constant and fails if en-US has no entry or
es-ES resolves it to the raw id: both sides of a StringKeys ↔ bundle
drift would otherwise silently ship the key string, because the screen
tests assert through the same resolver. On the test lane the bundles
are published onto the unit-test classpath — the identical bytes the
APK ships — because Robolectric initializes CMP's resource context
lazily and the device lane is not available there.
