package app.mpvplayer

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs

/**
 * 播放画面 + 手势层。
 * 横向拖动：进度预览/跳转
 * 纵向拖动：左半边亮度、右半边音量
 * 双击：左/右 ±10s，中间 播放暂停
 * 单击：显隐控制条
 */
class PlayerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    interface Host {
        fun onToggleControls()
        fun onTogglePlayPause()
        fun onSeekBySeconds(seconds: Double)
        fun onSeekPreview(targetMs: Long)
        fun onSeekCommit(targetMs: Long)
        fun onVolumeChanged(targetVolume: Double)
        fun onBrightnessChanged(value: Float)
        fun currentPlaybackState(): PlaybackState
    }

    data class PlaybackState(
        val positionMs: Long,
        val durationMs: Long,
        val volume: Double,
    )

    var host: Host? = null
    var baseBrightness: Float = 0.5f

    private enum class DragMode { NONE, SEEK, VOLUME, BRIGHTNESS }

    private var dragMode = DragMode.NONE
    private var downX = 0f
    private var downY = 0f
    private var basePositionMs = 0L
    private var baseDurationMs = 1L
    private var baseVolume = 100.0

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            dragMode = DragMode.NONE
            downX = e.x
            downY = e.y
            val state = host?.currentPlaybackState()
            if (state != null) {
                basePositionMs = state.positionMs
                baseDurationMs = state.durationMs.coerceAtLeast(1L)
                baseVolume = state.volume
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            host?.onToggleControls()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val w = width.takeIf { it > 0 }?.toFloat() ?: return true
            host?.run {
                when {
                    e.x < w / 3f -> onSeekBySeconds(-10.0)
                    e.x > w * 2f / 3f -> onSeekBySeconds(10.0)
                    else -> onTogglePlayPause()
                }
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (dragMode == DragMode.NONE) {
                if (abs(dx) < TOUCH_SLOP && abs(dy) < TOUCH_SLOP) return false
                dragMode = when {
                    abs(dx) > abs(dy) -> DragMode.SEEK
                    e1.x < width / 2f -> DragMode.BRIGHTNESS
                    else -> DragMode.VOLUME
                }
            }
            val h = host ?: return false
            val w = width.takeIf { it > 0 } ?: return false
            when (dragMode) {
                DragMode.SEEK -> {
                    val deltaMs = (dx / w * baseDurationMs).toLong()
                    h.onSeekPreview((basePositionMs + deltaMs).coerceIn(0L, baseDurationMs))
                }
                DragMode.VOLUME -> {
                    val delta = -dy / height * 100.0
                    h.onVolumeChanged(baseVolume + delta)
                }
                DragMode.BRIGHTNESS -> {
                    val delta = -dy / height
                    h.onBrightnessChanged((baseBrightness + delta).coerceIn(0.01f, 1f))
                }
                DragMode.NONE -> Unit
            }
            return true
        }
    })

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            if (dragMode == DragMode.SEEK) {
                val w = width.takeIf { it > 0 }
                val dx = event.x - downX
                if (w != null) {
                    val deltaMs = (dx / w * baseDurationMs).toLong()
                    host?.onSeekCommit((basePositionMs + deltaMs).coerceIn(0L, baseDurationMs))
                }
            }
            dragMode = DragMode.NONE
        }
        return true
    }

    // region Surface 生命周期 -> 交给 engine

    private var engine: MpvEngine? = null

    fun bindEngine(engine: MpvEngine) {
        this.engine = engine
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        engine?.onSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine?.onSurfaceChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.onSurfaceDestroyed()
    }

    // endregion

    private companion object {
        const val TOUCH_SLOP = 24
    }
}
