package com.macguyverism.briefsona

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val controls = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()

            override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)
            override fun seekToNext() = jump(1)
            override fun seekToPrevious() = jump(-1)
            override fun seekToNextMediaItem() = jump(1)
            override fun seekToPreviousMediaItem() = jump(-1)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, controls).setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = controls.availableCommands.buildUpon()
                    if (controller.packageName != packageName) {
                        commands.remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM)
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(commands.build()).build()
                }
            }).build()
        var lastId: String? = null
        var lastPosition = 0L
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = lastId
                val position = lastPosition
                if (id != null && id != mediaItem?.mediaId) save(id, position)
                activeId = mediaItem?.mediaId
                lastId = activeId
                lastPosition = player.currentPosition
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo, reason: Int) {
                updateChapterMetadata()
                oldPosition.mediaItem?.mediaId?.let { id ->
                    save(id, if (id == newPosition.mediaItem?.mediaId) newPosition.positionMs else oldPosition.positionMs)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) checkpoint()
            }
        })
        scope.launch {
            while (true) {
                delay(2_000)
                lastPosition = player.currentPosition
                updateChapterMetadata()
                if (player.isPlaying) checkpoint()
            }
        }
    }

    private fun updateChapterMetadata() {
        val item = player.currentMediaItem ?: return
        val metadata = item.mediaMetadata
        val starts = metadata.extras?.getLongArray("chapter_starts") ?: return
        val titles = metadata.extras?.getStringArray("chapter_titles") ?: return
        if (starts.isEmpty()) return
        val title = titles.getOrNull(chapterIndex(starts.toList(), player.currentPosition)) ?: return
        if (metadata.title == title) return
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon()
            .setMediaMetadata(metadata.buildUpon().setTitle(title).setDisplayTitle(title).build()).build())
    }

    private fun jump(direction: Int) {
        val starts = player.currentMediaItem?.mediaMetadata?.extras?.getLongArray("chapter_starts") ?: return
        if (starts.isEmpty()) return
        val index = chapterIndex(starts.toList(), player.currentPosition)
        val next = (index + direction).coerceIn(starts.indices)
        player.seekTo(starts[next])
    }

    private fun checkpoint() {
        player.currentMediaItem?.mediaId?.let { save(it, player.currentPosition) }
    }

    private fun save(id: String, position: Long) {
        scope.launch { Library.get(this@PlaybackService).episodes().position(id, position) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        val id = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        if (id != null) {
            CoroutineScope(Dispatchers.IO).launch {
                Library.get(this@PlaybackService).episodes().position(id, position)
            }
        }
        activeId = null
        session?.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var activeId: String? = null
            private set

        fun metadata(segments: List<Chapter>) =
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle("Morning Edition")
                .setAlbumTitle("Morning Edition")
                .setArtist("Briefsona")
                .setExtras(Bundle().apply {
                    putLongArray("chapter_starts", segments.map { it.start }.toLongArray())
                    putStringArray("chapter_titles", segments.map { it.title }.toTypedArray())
                })
                .build()
    }
}
