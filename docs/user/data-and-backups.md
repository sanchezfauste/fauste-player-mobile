# Data and backups

## What is saved

The app keeps one **session**: the queue, the transport's state (including
where the current track was left) and the settings. It is written to the
app's private storage:

- a moment after it changes — a short quiet period after your last action,
- periodically while something plays,
- and once more when the app stops.

A force-kill can lose at most the very last instant; reopening restores the
queue and the paused position (see
[Background playback](background-playback.md#if-the-app-is-killed)). There
is no export or import of the session: the queue is rebuilt by importing
files again. The app does not watch folders: the queue is what you
import, nothing more.

## Where everything lives

Everything is in the app's private storage — no data is written to shared
storage, and no file is created outside the app's own directory:

| Path | Contents |
|---|---|
| `files/session.json` | The saved session (queue, transport, settings) |
| `files/import/` | The imported audio files — one copy per import |
| `files/crash/` | App crash reports, one file per crash, the 20 newest kept |
| `files/logs/` | Engine panic reports, capped per run, never deleted |

Importing **copies** files; it does not move, link or deduplicate them. The
copies are the app's — uninstalling deletes them, and the only real backup
of your music is the originals you keep elsewhere.

## Crash reports

If the app ever crashes on you, a report lands in `files/crash/`; a panic
in the engine lands in `files/logs/`. On a debug build you can find both
there, readable with `adb` (or a file manager with the app's data), and
attach them to your bug report. On a release build you cannot read them
yourself — the storage is private to the app — so report the crash and
the maintainer will ask for whatever is needed.

## Uninstalling

Uninstalling clears everything: the queue, the imported copies, the
settings and the reports. Nothing is left on the device.
