# Getting started

## Install

Fauste Player Mobile ships as an APK, downloaded from the project's
**Releases** page. It needs Android 8.0 (API 26) or newer, on an arm64-v8a
or x86_64 device.

1. Allow installing unknown apps for the browser or file manager you
   download with (system settings; the exact wording varies by Android
   version).
2. Download the APK and open it from the download notification or your file
   manager.
3. Confirm the system's install prompt. Updating means installing the newer
   APK the same way; the queue, the imported files and the settings are
   kept.

## First launch

The app asks for two permissions, each the first time it needs it:

- **Music and audio access** (Android 13 and newer) or **read external
  storage** (older devices). Required to import files. Deny it and the
  import tells you so and does nothing — grant it and tap **Import** again.
- **Notifications** (Android 13 and newer). Optional: denying it hides only
  the playback notification. Playback, the media controls and the
  background behaviour are unaffected (see
  [Troubleshooting](troubleshooting.md#no-notification)).

Nothing else is asked and nothing phones home.

## The three screens

The bottom bar switches between **Player** (the transport), **Library**
(your queue) and **Settings** (the audio backend, the volume, the remote
status). The app opens on the Player screen; nothing plays until you press
**Play**.

## Import music

1. Open the **Library** screen and tap **Import**.
2. The system file picker opens. Pick one audio file; the picker closes
   after each file, so tap **Import** again for the next one.
3. The app copies the file into its own storage and appends it to the
   queue. The line `Imported: <path>` confirms each import; **Dismiss**
   clears the message.

If the file could not be imported, a failure line appears instead — nothing
is silently dropped, and a file that failed to import is never added to the
queue. While the queue is empty, the Library says so and invites you to
import. Importing copies files: the originals stay wherever they are (see
[Data and backups](data-and-backups.md)).

Until the analysis layer ships, rows and the Player screen's title show
the internal name of the imported copy — a long identifier — rather than
the original file's name. It is cosmetic; playback, seeking and the queue
order are unaffected.

The **Search** box filters the queue by title; it only hides non-matching
rows, the queue itself is unchanged.

## Play

The Player screen carries the transport: previous-track, play/pause, the
seek bar and a volume slider. The timecode reads `position / duration`.

- **Play / Pause** toggles playback. The button announces *Playing* or
  *Paused* to screen readers.
- **Seek**: drag the seek bar — it follows your finger instantly; playback
  jumps when you release.
- **Volume**: the slider changes the player's own volume, not the system
  volume; the same control appears in Settings.
- **Previous** steps back to the track you last left. It only does
  something **while playing** — it is a live-playback control, not a queue
  cursor.

Durations read "—" until the analysis layer ships (see the
[introduction](README.md#what-it-does-not-do-yet)); the seek bar works on
the position axis regardless. What plays, and in which order, is the
queue's business: continue with [The queue](the-queue.md).
