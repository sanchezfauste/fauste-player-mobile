# Fauste Player — mobile user guide

Fauste Player is an offline-first audio player: the playback engine is a Rust
core (the same engine family as the desktop app), wrapped in a native Android
app. This guide covers installing it, getting music in, playing it, and what
to expect when it goes to the background — plus the honest list of what it
does not do yet.

Screens: **Player** (the transport), **Library** (your queue), **Settings**
(audio output, remote status, volume). The app ships in English and Spanish
(castilian, es-ES); it follows your device language automatically.

## Installing

On a device: allow "install unknown apps" for your browser/file manager,
download the release APK, open it, confirm. It needs Android 8.0
(API 26) or newer. First launch asks for two permissions:

- **Music and audio access** (on Android 13+) or *read external storage*
  (older devices) — required to import files.
- **Notifications** (on Android 13+) — optional; denying it hides only the
  playback notification. Playback, the media controls and background
  behaviour are unaffected.

No network access is used for any feature; the only network the app ever
touches is a loopback (on-device) socket (see *Settings → Remote*).

## Importing music

**Library → Import** opens your system file picker. Pick one or more audio
files; the app copies them into its own storage and appends each to the
queue. The row `Imported: <path>` confirms each import; **Dismiss** clears
the message. If the file could not be imported, a failure line appears
instead — nothing is silently dropped.

The queue is what the Library screen shows. The **Search** box filters it by
title (matching rows only; the list itself is unchanged). While the queue is
empty, the Library says so and invites you to import.

## Playing

The Player screen carries the transport: previous-track, play/pause, the
seek bar, and a volume slider. The timecode reads `position / duration`.

- **Play / Pause** toggles playback. The button's state label announces
  *Playing* or *Paused* to screen readers.
- **Seek**: drag the seek bar — it follows your finger instantly; playback
  jumps when you release.
- **Volume**: the slider changes the engine's output volume (this is the
  player's own volume, not the system volume).
- **Previous** steps back to the track you last left (a crossfade back in
  the engine's terms). It only does something **while playing** — it is a
  live-playback control, not a queue cursor; while paused or stopped it
  does nothing.

## The queue — how "next" works (tap arms, it does not play)

Tapping a track in the Library **does not start it**. A tap *arms* the tapped
track as the **next** one:

- The armed row immediately shows the **UP NEXT** badge, and the Player
  screen shows `Up next: <title>` under the transport.
- The armed track **starts when the current track finishes**, or when you
  next press **Play** (after the queue has stopped).
- Tapping another row re-arms — the badge moves; there is always at most one
  armed track. Tapping the armed row again keeps it armed.

Why arm-only? A one-tap gesture that both selects *and* starts playback
promises more than a list can honestly show: arming makes the tap's effect
visible (the badge) and predictable (the current track is never cut short by
a stray touch). Playback starts from the transport — Play — or naturally at
the end of the current track.

## Settings

- **Output backend** — the audio devices the engine probed, with an
  *Available / Unavailable* line. Only available backends can be selected;
  the selected marker follows what the engine actually uses (so a failed
  switch or a device fallback reads honestly). On a typical phone there is
  one entry (the AAudio device).
- **Remote** — the status of the engine's built-in HTTP remote (the API the
  desktop and scripting clients speak): `On at 127.0.0.1:<port>` when
  serving, `Off` otherwise. It is **loopback-only**: reachable from the
  device itself (another on-device app, or `adb forward` during
  development), never from other machines. **Refresh** re-reads the status.
- **Volume** — same engine volume as the Player slider; *Applying…* shows
  while a change is being confirmed.

## Background and lock screen

Playback continues when you leave the app or lock the screen:

- A **playback notification** appears (channel *Playback*) with
  play/pause and **Stop** actions; tapping it returns to the app. On a
  locked screen the system media controls offer the same actions.
- **Calls and other audio duck or pause you**: when a call or navigation
  instruction takes audio focus, playback pauses for a real interrupt and
  resumes when the call ends; for a duckable interrupt (e.g. a brief
  announcement) the volume ducks to a low level and restores itself.
- **Stop** (the notification action) ends playback and removes the
  notification and its foreground service cleanly.

## If the app is killed

Android may kill the app (low memory, or you force-stop it). The queue is
saved as it changes (a debounced save, plus save-on-stop), so:

- After a **force-kill while playing**, reopening the app restores your
  **queue** — the transport comes back **paused** at the saved position.
  Press Play to continue.
- After a normal relaunch, the same queue is there.

Crash reports (if the app ever crashes on you) land in the app's private
directory — `files/crash/` for app crashes (kept at the 20 newest),
`files/logs/` for engine panics (capped per run, kept) — readable with the
crash data you may be asked to attach to a bug report (`adb` or a file
manager with the app's data).

## Limitations (honest)

- **Durations show "—"** until the analysis layer ships (the engine does not
  yet read tags/duration on mobile); seek still works on the position axis
  shown. Artist names show when the file carries them.
- **No in-app language switch.** The UI follows the device language: en-US
  copy is the source; any `es-*` locale falls back to the es-ES bundle.
  No other languages yet.
- **The remote is loopback-only** — by design. No pairing, no remote
  control from other devices; the on-device API exists for the desktop's
  ecosystem and development tooling.
- **The queue is a flat list.** No playlists, drag-reorder, waveform, cue
  points or cartwall yet — those are roadmap items (see the README's
  feature table), not hidden switches.
- **Import copies files** into the app's storage; it does not deduplicate
  against existing entries, and it does not watch folders.
