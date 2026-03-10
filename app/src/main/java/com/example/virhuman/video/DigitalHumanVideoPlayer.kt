package com.example.virhuman.video

import android.content.Context
import android.util.Log
import android.view.View
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView

class DigitalHumanVideoPlayer(context: Context) {
    private val tag = "DigitalHumanVideo"
    private val switchTag = "VideoStateSwitch"
    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = true
    }
    private var currentState: DigitalHumanState? = null
    private var boundView: PlayerView? = null
    private var maskView: View? = null
    private val frameListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            hideMask()
            Log.d(switchTag, "first-frame rendered, hide mask")
        }
    }

    private val stateToAssetPath = mapOf(
        DigitalHumanState.LEISURE to "asset:///videos/leisure.mp4",
        DigitalHumanState.LISTENING to "asset:///videos/listening.mp4",
        DigitalHumanState.SPEAKING to "asset:///videos/speaking.mp4"
    )

    fun bind(playerView: PlayerView, videoMask: View) {
        boundView = playerView
        maskView = videoMask
        playerView.player = player
        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        player.addListener(frameListener)
    }

    fun setState(state: DigitalHumanState) {
        Log.d(
            switchTag,
            "request state=$state, current=$currentState, thread=${Thread.currentThread().name}"
        )
        if (state == currentState) return
        currentState = state
        val uri = stateToAssetPath[state] ?: return
        showMask()
        Log.d(tag, "Switch state=$state uri=$uri")
        Log.d(switchTag, "apply state=$state uri=$uri")
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        Log.d(switchTag, "prepared state=$state")
    }

    fun release() {
        player.removeListener(frameListener)
        hideMask(immediate = true)
        boundView?.player = null
        boundView = null
        maskView = null
        player.release()
    }

    private fun showMask() {
        val view = maskView ?: return
        view.animate().cancel()
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(0.28f)
            .setDuration(90L)
            .start()
    }

    private fun hideMask(immediate: Boolean = false) {
        val view = maskView ?: return
        view.animate().cancel()
        if (immediate) {
            view.alpha = 0f
            view.visibility = View.GONE
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                view.visibility = View.GONE
            }
            .start()
    }
}
