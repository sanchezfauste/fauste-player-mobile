package org.fauste.player.service

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager

/**
 * THE BRIDGE'S MEDIA3 HALF (Task 6): a [SimpleBasePlayer] adapter over
 * the state layer, built for [PlaybackService]'s [MediaSession].
 *
 * DESIGN (the ruling: the service must not depend on any ViewModel —
 * they are UI-scoped and die with the screen): the adapter takes the
 * Koin graph's own [PlayerController] + [QueueManager] and nothing
 * else. A collector in the service's background [scope] runs the pure
 * [SessionPlaybackSnapshot.from] mapping over both flows and publishes
 * the snapshot behind a volatile; media3 then re-reads [getState] on
 * the main thread on every [invalidateState]. All player METHODS run
 * on the main thread by the SimpleBasePlayer contract (media3's session
 * posts to [getApplicationLooper] — the main looper), and every one of
 * them only touches those cheap published values — the ENGINE is never
 * reached from the main thread: commands are launched into the same
 * background scope, whose dispatcher the state layer already uses for
 * its engine calls.
 *
 * The command contract: `handle*` methods fire the controller command
 * into the scope and return an already-completed future — "applied
 * synchronously" in SimpleBasePlayer's terms, because the engine IS
 * asynchronous and its own poll is the feedback loop: the state a
 * command produces arrives with the next collected snapshot (≤ one
 * poll period while the app is foregrounded), so the notification never
 * shows an intent the engine did not confirm — a Busy-dropped or
 * failed command simply leaves the state at engine truth
 * (`CommandResult`'s retry-safe contract). The [SessionPlaybackSnapshot]
 * suite pins every mapping rule; this class is only the translation.
 */
@OptIn(UnstableApi::class)
internal class FaustePlayerAdapter(
    private val controller: PlayerController,
    private val queue: QueueManager,
    private val scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    /** The last snapshot the collector published; read on the main thread by [getState]. */
    @Volatile
    private var latest: SessionPlaybackSnapshot = SessionPlaybackSnapshot.idle

    /** The latest published snapshot — the service's teardown watcher reads it (one pipeline, one truth). */
    val latestSnapshot: SessionPlaybackSnapshot
        get() = latest

    @Suppress("unused") // the Job is the collector's lifetime: cancelled with the scope in the service's onDestroy
    private val collector =
        scope.launch {
            combine(controller.engineState, queue.playlist) { engineState, playlist ->
                SessionPlaybackSnapshot.from(engineState, playlist)
            }.collect { snapshot ->
                latest = snapshot
                invalidateState()
            }
        }

    override fun getState(): State {
        val snapshot = latest
        return State
            .Builder()
            .setAvailableCommands(SESSION_COMMANDS)
            .setPlayWhenReady(snapshot.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (snapshot.playbackReady) Player.STATE_READY else Player.STATE_IDLE)
            .setPlaylist(snapshot.queue.mapIndexed { index, item -> mediaItemData(item, index, snapshot) })
            .setCurrentMediaItemIndex(snapshot.currentQueueIndex)
            .setContentPositionMs(snapshot.positionMs)
            .setVolume(snapshot.volume)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) =
        command {
            if (playWhenReady) {
                controller.play()
            } else {
                controller.pause()
            }
        }

    override fun handleStop() = command { controller.stop() }

    override fun handleSetVolume(volume: Float) = command { controller.setVolume(volume) }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int,
    ) = command {
        when (seekCommand) {
            // In-item seeking: the session's ms position is the FFI's seconds.
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> controller.seek(positionMs / 1000.0)
            // The deck's Previous is a crossfade-back to the entry the player
            // left (QueueManager.previous's own KDoc) — the target index media3
            // computed is discarded on purpose: the engine owns where it
            // resumes, exactly like the desktop's transport grid button.
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> queue.previous()
            // Nothing else is in the available command set.
            else -> Unit
        }
    }

    /**
     * Fires [block] into the service's background scope and answers with
     * an already-completed future (the class KDoc's command contract).
     * The launch itself is the only main-thread work: a coroutine
     * enqueue — no engine, no IO.
     */
    private fun command(block: suspend () -> Unit): ListenableFuture<*> =
        scope
            .launch { block() }
            .let { Futures.immediateVoidFuture() }

    /** One queue row as media3 timeline data: the row's id/title/duration, the engine's own metadata on the on-air row. */
    private fun mediaItemData(
        item: SessionQueueItem,
        index: Int,
        snapshot: SessionPlaybackSnapshot,
    ): MediaItemData =
        MediaItemData
            .Builder(item.id)
            .setMediaItem(
                MediaItem
                    .Builder()
                    .setMediaId(item.id.toString())
                    .setMediaMetadata(mediaMetadata(item, isCurrent = index == snapshot.currentQueueIndex, snapshot))
                    .build(),
            ).setDurationUs(
                if (item.durationMs == SessionPlaybackSnapshot.UNSET_DURATION_MS) {
                    C.TIME_UNSET
                } else {
                    C.msToUs(item.durationMs)
                },
            ).setIsSeekable(item.durationMs != SessionPlaybackSnapshot.UNSET_DURATION_MS)
            .build()

    /**
     * The row's spoken/displayed metadata. The ON-AIR row carries the
     * ENGINE's own report (title and artist, never the queue's guess);
     * every other row carries its own title — the FFI's rows have no
     * artist field, and none is invented.
     */
    private fun mediaMetadata(
        item: SessionQueueItem,
        isCurrent: Boolean,
        snapshot: SessionPlaybackSnapshot,
    ): MediaMetadata =
        MediaMetadata
            .Builder()
            .apply {
                if (isCurrent) {
                    setTitle(snapshot.currentTitle ?: item.title)
                    snapshot.currentArtist?.let { setArtist(it) }
                } else {
                    setTitle(item.title)
                }
                if (item.durationMs != SessionPlaybackSnapshot.UNSET_DURATION_MS) {
                    setDurationMs(item.durationMs)
                }
            }.build()

    private companion object {
        /**
         * Exactly what the engine surface supports — no more. Play/pause
         * and stop drive the transport; in-item seek rides the
         * lock-screen seek bar; previous is the deck's crossfade-back;
         * there is deliberately NO next (advancing is the engine's own
         * transition, not a queue command). The GET_* introspection set
         * is what the session and the default notification provider
         * read state through (metadata, timeline, current item, volume).
         */
        val SESSION_COMMANDS: Player.Commands =
            Player.Commands
                .Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_STOP)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SET_VOLUME)
                .add(Player.COMMAND_GET_VOLUME)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_METADATA)
                .build()
    }
}
