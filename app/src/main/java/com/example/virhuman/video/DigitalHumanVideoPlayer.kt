package com.example.virhuman.video

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    private data class Slot(
        val name: String,
        val player: ExoPlayer,
        var view: PlayerView? = null
    )

    private val slotFront = Slot(
        "front",
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    )
    private val slotBack = Slot(
        "back",
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    )

    private var activeSlot: Slot? = null
    private var currentState: DigitalHumanState? = null
    private var targetState: DigitalHumanState? = null
    private var transitionToken = 0
    private var pendingSlot: Slot? = null
    private var pendingListener: Player.Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val stateToAssetPath = mapOf(
        DigitalHumanState.LEISURE to "asset:///videos/leisure.mp4",
        DigitalHumanState.LISTENING to "asset:///videos/listening.mp4",
        DigitalHumanState.SPEAKING to "asset:///videos/speaking.mp4"
    )

    fun bind(frontView: PlayerView, backView: PlayerView) {
        slotFront.view = frontView
        slotBack.view = backView

        frontView.player = slotFront.player
        backView.player = slotBack.player

        frontView.useController = false
        backView.useController = false
        frontView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        backView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

        frontView.alpha = 0f
        backView.alpha = 0f
        frontView.visibility = View.INVISIBLE
        backView.visibility = View.INVISIBLE
    }

    fun setState(state: DigitalHumanState) {
        Log.d(
            switchTag,
            "request state=$state current=$currentState target=$targetState active=${activeSlot?.name} thread=${Thread.currentThread().name}"
        )
        if (state == currentState && pendingSlot == null) return
        if (state == targetState && pendingSlot != null) return
        val uri = stateToAssetPath[state] ?: return
        targetState = state

        val current = activeSlot
        if (current == null) {
            val first = slotFront
            first.player.setMediaItem(MediaItem.fromUri(uri))
            first.player.prepare()
            first.player.playWhenReady = true
            first.view?.visibility = View.VISIBLE
            first.view?.alpha = 1f
            (if (first === slotFront) slotBack else slotFront).view?.apply {
                alpha = 0f
                visibility = View.INVISIBLE
            }
            activeSlot = first
            currentState = state
            targetState = null
            Log.d(switchTag, "apply-first state=$state slot=${first.name} uri=$uri")
            return
        }

        val target = if (current === slotFront) slotBack else slotFront
        transitionToken += 1
        val token = transitionToken

        clearPendingListener()
        pendingSlot = target

        val targetView = target.view
        targetView?.animate()?.cancel()
        targetView?.visibility = View.VISIBLE
        targetView?.alpha = 0f

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (token != transitionToken) {
                    target.player.removeListener(this)
                    return
                }
                target.player.removeListener(this)
                pendingListener = null
                pendingSlot = null
                Log.d(switchTag, "first-frame state=$state slot=${target.name} token=$token")
                crossFade(from = current, to = target)
            }
        }
        pendingListener = listener
        target.player.addListener(listener)

        Log.d(switchTag, "apply state=$state from=${current.name} to=${target.name} uri=$uri token=$token")
        target.player.stop()
        target.player.clearMediaItems()
        target.player.setMediaItem(MediaItem.fromUri(uri))
        target.player.prepare()
        target.player.playWhenReady = true

        // Some devices may skip onRenderedFirstFrame callback occasionally.
        mainHandler.postDelayed({
            if (token != transitionToken) return@postDelayed
            if (pendingSlot !== target) return@postDelayed
            if (target.player.playbackState == Player.STATE_READY) {
                Log.d(switchTag, "fallback-ready state=$state slot=${target.name} token=$token")
                clearPendingListener()
                crossFade(from = current, to = target)
            }
        }, 420L)
    }

    fun release() {
        clearPendingListener()
        slotFront.view?.player = null
        slotBack.view?.player = null
        slotFront.view = null
        slotBack.view = null
        slotFront.player.release()
        slotBack.player.release()
        activeSlot = null
        currentState = null
        targetState = null
    }

    private fun clearPendingListener() {
        val slot = pendingSlot
        val listener = pendingListener
        if (slot != null && listener != null) {
            slot.player.removeListener(listener)
        }
        pendingSlot = null
        pendingListener = null
    }

    private fun crossFade(from: Slot, to: Slot) {
        val fromView = from.view
        val toView = to.view
        if (toView == null) return

        toView.animate().cancel()
        fromView?.animate()?.cancel()

        toView.visibility = View.VISIBLE
        toView.alpha = 0f

        toView.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()

        fromView?.animate()
            ?.alpha(0f)
            ?.setDuration(180L)
            ?.withEndAction {
                // Stop old decoder after visual handoff to avoid early-frame glitches.
                from.player.stop()
                from.player.clearMediaItems()
                fromView.alpha = 0f
                fromView.visibility = View.INVISIBLE
                Log.d(switchTag, "crossfade-complete from=${from.name} to=${to.name}")
            }
            ?.start()

        activeSlot = to
        currentState = targetState
        targetState = null
        Log.d(tag, "Switch completed to slot=${to.name}")
    }
}
