package app.simplenarrator.audiobooks.playback

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import app.simplenarrator.R

/** Benachrichtigung mit Zurückspulen, Wiedergabe/Pause und Vorspulen in dieser Reihenfolge. */
@OptIn(UnstableApi::class)
class NotificationProvider(private val context: Context) : DefaultMediaNotificationProvider(context) {

    init {
        setSmallIcon(R.drawable.ic_stat_app)
    }

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val rewind = customLayout.firstOrNull { it.sessionCommand?.customAction == PlaybackService.ACTION_REWIND }
        val forward = customLayout.firstOrNull { it.sessionCommand?.customAction == PlaybackService.ACTION_FORWARD }
        val play = CommandButton.Builder(if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName(context.getString(if (showPauseButton) R.string.ab_pause else R.string.ab_play))
            .setExtras(compact(1))
            .build()
        val out = ImmutableList.builder<CommandButton>()
        rewind?.let { out.add(it.copyWithExtras(0)) }
        out.add(play)
        forward?.let { out.add(it.copyWithExtras(2)) }
        return out.build()
    }

    override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? =
        metadata.title ?: metadata.albumTitle

    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? =
        metadata.albumTitle ?: metadata.artist

    private fun CommandButton.copyWithExtras(index: Int): CommandButton =
        CommandButton.Builder(icon)
            .setSessionCommand(sessionCommand!!)
            .setDisplayName(displayName)
            .setExtras(compact(index))
            .build()

    private fun compact(index: Int) = Bundle().apply { putInt(COMMAND_KEY_COMPACT_VIEW_INDEX, index) }
}
