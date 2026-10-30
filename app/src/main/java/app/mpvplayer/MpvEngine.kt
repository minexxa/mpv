package app.mpvplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib

/**
 * mpv 内核封装。
 *
 * 生命周期由 libmpv-android 的 native 层决定（见其 main.cpp / render.cpp）：
 *  - MPVLib.create() -> mpv_create()，此后只能用 setOptionString / attachSurface
 *  - attachSurface() -> mpv_set_option("wid")，必须在 init() 之前
 *  - init()          -> mpv_initialize() 并启动事件线程
 * 所以系统销毁 surface 时直接 destroy 整个实例，surface 重建后重新走一遍上面的顺序，
 * 并把文件与进度恢复回去。
 */
class MpvEngine(private val appContext: Context) {

    interface Listener {
        fun onPlayerReady(ready: Boolean)
        fun onPlayStateChanged(paused: Boolean)
        fun onProgressChanged(positionMs: Long, durationMs: Long)
        fun onTitleChanged(title: String)
        fun onFileEnded(error: Boolean)
        fun onNotice(message: String)
    }

    private val main = Handler(Looper.getMainLooper())

    private var player: MPVLib? = null
    private var surface: Surface? = null

    private var queuedUri: String? = null
    private var queuedStartMs: Long = 0

    /** surface 销毁 -> 重建之间用来恢复播放现场 */
    private var savedUri: String? = null
    private var savedPositionMs: Long = 0
    private var eofReached = false

    var hwdec: String = "auto-safe"
        set(value) {
            field = value
            player?.setPropertyString("hwdec", value)
        }

    var durationMs: Long = 0L
        private set
    var positionMs: Long = 0L
        private set
    var isPaused: Boolean = true
        private set
    var isPlayable: Boolean = false
        private set
    var speed: Double = 1.0
        private set
    var volume: Double = 100.0
        private set

    var listener: Listener? = null

    private val observer = object : MPVLib.EventObserver {

        override fun eventProperty(property: String) {
            // MPV_FORMAT_NONE：仅表示「这个属性变了」
        }

        override fun eventProperty(property: String, value: Long) {
            // MPV_FORMAT_INT64
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> {
                    positionMs = (value * 1000).toLong()
                    post { listener?.onProgressChanged(positionMs, durationMs) }
                }
                "duration" -> if (value > 0) {
                    durationMs = (value * 1000).toLong()
                    post { listener?.onProgressChanged(positionMs, durationMs) }
                }
                "volume" -> volume = value
                "speed" -> speed = value
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> {
                    isPaused = value
                    post { listener?.onPlayStateChanged(value) }
                }
                "idle-active" -> {
                    isPlayable = !value
                    post { listener?.onPlayerReady(!value) }
                }
                "eof-reached" -> if (value) {
                    eofReached = true
                }
            }
        }

        override fun eventProperty(property: String, value: String) {
            when (property) {
                "media-title" -> post { listener?.onTitleChanged(value) }
                "eof-reached" -> if (value == "eof") eofReached = true
            }
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    isPlayable = true
                    durationMs = readDouble("duration")?.let { (it * 1000).toLong() } ?: 0L
                    post { listener?.onPlayStateChanged(readBoolean("pause") ?: true) }
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    // native 绑定拿不到 end_file.error，用 eof-reached 区分「播完」和「出错」
                    isPlayable = false
                    val errored = !eofReached
                    eofReached = false
                    post { listener?.onFileEnded(errored) }
                }
            }
        }
    }

    // region 生命周期

    fun onSurfaceCreated(newSurface: Surface) {
        surface = newSurface
        start(newSurface)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        player?.setPropertyString("android-surface-size", "${width}x${height}")
    }

    fun onSurfaceDestroyed() {
        savedUri = readString("path") ?: savedUri
        savedPositionMs = positionMs
        stop()
        surface = null
    }

    private fun start(newSurface: Surface) {
        if (player != null) return
        val mpv = MPVLib.create(appContext)
        if (mpv == null) {
            Log.e(TAG, "MPVLib.create() 返回 null，libmpv 加载失败")
            post { listener?.onNotice("mpv 内核加载失败") }
            return
        }

        // ---- 以下全部是 pre-init option ----
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("hwdec", hwdec)
        mpv.setOptionString("config", "no")
        mpv.setOptionString("terminal", "no")
        mpv.setOptionString("msg-level", "all=error")
        mpv.setOptionString("osc", "no")            // 控制条自己画
        mpv.setOptionString("volume-max", "200")
        mpv.setOptionString("keepaspect", "yes")
        mpv.attachSurface(newSurface)                // 必须在 init() 之前

        mpv.init()
        player = mpv

        mpv.addObserver(observer)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("idle-active", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("core-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING)

        post { listener?.onPlayerReady(true) }

        // 新排队的文件从 0 开始；只有「surface 重建恢复现场」才套用旧进度
        val uri = queuedUri
        val startAt = queuedStartMs
        queuedUri = null
        queuedStartMs = 0
        if (uri != null) {
            load(uri, startAt)
        } else {
            savedUri?.let { load(it, savedPositionMs) }
        }
    }

    private fun stop() {
        val mpv = player ?: return
        player = null
        try {
            mpv.removeObserver(observer)
            mpv.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "destroy 异常", t)
        }
        post { listener?.onPlayerReady(false) }
    }

    /** Activity onDestroy 时彻底释放 */
    fun release() {
        savedUri = null
        savedPositionMs = 0
        stop()
        surface = null
    }

    // endregion

    // region 播放控制

    /** uri 可以是 content:// / file:// / http(s):// / rtmp(s):// / rtsp:// 等 */
    fun load(uri: String, startAtMs: Long = 0) {
        val mpv = player
        if (mpv == null) {
            queuedUri = uri
            queuedStartMs = startAtMs
            return
        }
        savedUri = uri
        savedPositionMs = startAtMs
        durationMs = 0
        positionMs = 0
        eofReached = false
        val args = if (startAtMs > 0) {
            arrayOf("loadfile", uri, "replace", "start=${startAtMs / 1000.0}")
        } else {
            arrayOf("loadfile", uri, "replace")
        }
        mpv.command(args)
    }

    fun togglePause() = setPaused(!isPaused)

    fun setPaused(pause: Boolean) {
        player?.setPropertyBoolean("pause", pause)
    }

    fun seekTo(targetMs: Long) {
        val mpv = player ?: return
        if (durationMs <= 0) return
        mpv.setPropertyDouble("time-pos", targetMs.coerceIn(0, durationMs) / 1000.0)
    }

    fun seekBy(seconds: Double) {
        val mpv = player ?: return
        if (durationMs <= 0) return
        val target = (positionMs / 1000.0 + seconds).coerceIn(0.0, durationMs / 1000.0)
        mpv.setPropertyDouble("time-pos", target)
    }

    fun setSpeed(value: Double) {
        speed = value
        player?.setPropertyDouble("speed", value)
    }

    fun setVolume(value: Double) {
        val v = value.coerceIn(0.0, 200.0)
        volume = v
        player?.setPropertyDouble("volume", v)
    }

    /** 这个绑定没有 node API，取不到 track-list 明细，用 mpv 的 cycle 命令最省事 */
    fun cycleSubtitle() {
        val mpv = player ?: return
        val before = readString("sid")
        mpv.command(arrayOf("cycle", "sid"))
        val after = readString("sid")
        post {
            listener?.onNotice(
                if (after == before) "没有更多字幕"
                else when (after) {
                    null, "no" -> "字幕：关闭"
                    else -> "字幕：#$after"
                }
            )
        }
    }

    fun cycleAudio() {
        val mpv = player ?: return
        val before = readString("aid")
        mpv.command(arrayOf("cycle", "aid"))
        val after = readString("aid")
        post {
            listener?.onNotice(
                if (after == before) "没有更多音轨"
                else when (after) {
                    null, "no" -> "音轨：关闭"
                    else -> "音轨：#$after"
                }
            )
        }
    }

    fun toggleAspectRatio() {
        val mpv = player ?: return
        val next = if ((readDouble("panscan") ?: 0.0) > 0.0) 0.0 else 1.0
        mpv.setPropertyDouble("panscan", next)
        post { listener?.onNotice(if (next > 0.0) "画面：铺满(裁边)" else "画面：完整显示") }
    }

    // endregion

    // region 属性读取

    fun readString(property: String): String? = tryOrNull { player?.getPropertyString(property) }
    fun readDouble(property: String): Double? = tryOrNull { player?.getPropertyDouble(property) }
    fun readBoolean(property: String): Boolean? = tryOrNull { player?.getPropertyBoolean(property) }

    private inline fun <T> tryOrNull(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }

    // endregion

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    companion object {
        private const val TAG = "MpvEngine"
    }
}
