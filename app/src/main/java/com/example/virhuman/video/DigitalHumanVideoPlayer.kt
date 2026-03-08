package com.example.virhuman.video

import android.content.Context
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView

class DigitalHumanVideoPlayer(context: Context) {
    private val tag = "DigitalHumanVideo"
    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = true
    }
    private var currentState: DigitalHumanState? = null
    private var boundView: PlayerView? = null

    private val stateToAssetPath = mapOf(
        DigitalHumanState.LEISURE to "asset:///videos/leisure.mp4",
        DigitalHumanState.LISTENING to "asset:///videos/listening.mp4",
        DigitalHumanState.SPEAKING to "asset:///videos/speaking.mp4"
    )

    fun bind(playerView: PlayerView) {
        boundView = playerView
        playerView.player = player
        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    fun setState(state: DigitalHumanState) {
        if (state == currentState) return
        currentState = state
        val uri = stateToAssetPath[state] ?: return
        Log.d(tag, "Switch state=$state uri=$uri")
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    fun release() {
        boundView?.player = null
        boundView = null
        player.release()
    }
}
