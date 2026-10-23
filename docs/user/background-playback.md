# Background playback

Playback continues when you leave the app or lock the screen.

## The notification and the lock screen

While something plays, the app keeps a **playback notification** (channel
*Playback*) with play/pause and **Stop** actions. Tapping the notification
returns to the app. On the lock screen, the system's media controls offer
the same actions.

- **Stop** (the notification action) ends playback and removes the
  notification and its foreground service cleanly.
- The notification is the only thing the *Notifications* permission
  governs: denying it hides the notification; playback and the media
  controls keep working (see
  [Troubleshooting](troubleshooting.md#no-notification)).

## Calls and navigation prompts

The app respects the system's audio focus:

- A **call** (a real interrupt) pauses playback; when the call ends,
  playback resumes by itself.
- A **navigation prompt** (a duckable interrupt — a brief announcement)
  lowers the volume for its duration and restores it afterwards.

## If the app is killed

Android may kill the app — low memory, or you force-stopping it. The queue
is saved as it changes (a debounced save, plus save-on-stop), so:

- After a **force-kill while playing**, reopening the app restores your
  **queue**; the transport comes back **paused** at the saved position.
  Press **Play** to continue.
- After a normal relaunch, the same queue is there.

See [Data and backups](data-and-backups.md) for what is saved and when.

## The honest limits

- **Play started through the loopback remote on a fully-stopped app does
  not raise a notification.** The remote (see
  [Settings](settings.md#remote)) can start the engine's playback while the
  app is stopped; the audio plays, but the notification and the foreground
  service belong to the app's own playback path and are not raised
  retroactively. Start playback from the app once and the notification is
  back.
- The lock-screen controls and the notification offer play/pause and
  **Stop**; they do not browse the queue or arm tracks.
