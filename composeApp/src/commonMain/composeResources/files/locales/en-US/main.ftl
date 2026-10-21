# The en-US source bundle (Task 5, i18n): every user-facing string the
# app renders, in Fluent syntax. This file is the source of truth for
# the English copy; es-ES/main.ftl is its hand-translated sibling, and
# the seam (ui/strings/FluentStrings.kt) falls back HERE whenever a key
# is missing from the target locale.

### Navigation
nav-player = Player
nav-library = Library
nav-settings = Settings

### Library screen
library-search-label = Search
library-search-description = Search the queue
library-import = Import
library-importing = Importing…
library-imported = Imported: {$path}
library-dismiss = Dismiss
library-empty-queue = Import music to build the queue
library-empty-search = No tracks match the search.

### Messages surfaced through the state layer by the view models
player-busy = The player was busy and dropped the command — try again.
library-permission-denied = Import needs access to your audio files — grant the permission and tap Import again.
library-import-failed = the import failed

### Player screen
player-up-next = Up next: {$title}
unknown-value = —

### Transport controls
control-seek = Seek bar
control-previous = Previous track
control-play = Play
control-pause = Pause
control-playing = Playing
control-paused = Paused
control-volume = Volume
timecode = {$position} / {$duration}

### Settings screen
settings-backend-title = Output backend
settings-no-backends = No backends reported.
settings-remote-title = Remote
settings-remote-on = On at {$address}
settings-remote-off = Off
settings-refresh = Refresh
settings-volume-title = Volume
settings-applying = Applying…
backend-availability = {$available ->
    [true] Available
   *[false] Unavailable
}
backend-row = {$name}, {$available ->
    [true] available
   *[false] unavailable
}, {$selected ->
    [true] selected
   *[false] not selected
}

### Queue row (TrackRow)
row-arm = Arm as next track
row-now-playing = Now playing
row-up-next = UP NEXT
row-armed = Armed as next track

### Notification (channel name + stop action label)
notification-channel = Playback
notification-stop = Stop
