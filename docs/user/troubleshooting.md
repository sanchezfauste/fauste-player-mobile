# Troubleshooting

## No sound

1. Check the two volumes: the Player screen's slider is the player's own
   volume, and the system volume is separate — both need to be up.
2. Open **Settings → Output backend**. Each row says whether the engine
   found it usable; only available backends can be selected, and the
   selected marker follows what the engine is actually using, so a failed
   switch or a device fallback reads honestly there. If the section lists
   no backend at all, the probe has not answered — close and reopen the
   app.
3. Playback that shows as *Playing* on the Player screen but stays silent
   is a device-side issue (muted output, a Bluetooth device that did not
   connect): the engine is playing; the system is not delivering.

## No notification

On Android 13 and newer, the playback notification needs the
*Notifications* permission. Denying it hides only the notification:
playback, the media controls and the background behaviour are unaffected.
To get it back, allow notifications for Fauste Player in the system's
settings.

## An import fails

The Library shows a failure line — nothing is silently dropped, and a file
that failed to import is never added to the queue. The causes:

- **The audio permission is missing.** The line says so; grant it and tap
  **Import** again.
- **The file could not be read** (unreadable, an I/O error) or **is not an
  importable audio file** (an unsupported type). The copy is cleaned up;
  the queue is untouched. Keep the file somewhere safe, check it plays
  elsewhere, and try again.

Importing copies files; if storage is full the copy fails the same honest
way (see [Data and backups](data-and-backups.md) for where the copies
land).

## Crash reports

App crashes land in `files/crash/`, engine panics in `files/logs/`, both in
the app's private storage. Attach them to a bug report — see
[Data and backups](data-and-backups.md) for the locations and how to read
them.

## The app's language

The interface follows the device's language: English (en-US) or Spanish
(es-ES). There is no in-app switcher. A device set to any Spanish variant
(`es-*`) gets the es-ES bundle; every other language gets English. If you
spot an error in either, corrections are welcome as issues or pull
requests — both were produced with AI assistance.
