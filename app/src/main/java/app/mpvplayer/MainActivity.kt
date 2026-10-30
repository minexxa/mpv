package app.mpvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.mpvplayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), MpvEngine.Listener, PlayerSurfaceView.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: MpvEngine

    private val autoHide = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideHudRunnable = Runnable { binding.textHud.visibility = View.GONE }

    private var scrubbing = false
    private var hasMedia = false
    private var lastUrl = ""

    private val speedPresets = doubleArrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0)
    private val hwdecModes = arrayOf("auto-safe" to "自动解码", "no" to "软件解码", "auto" to "硬件解码")
    private var hwdecIndex = 0

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            playUri(uri.toString())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = MpvEngine(applicationContext)
        engine.listener = this
        binding.surface.bindEngine(engine)
        binding.surface.host = this
        binding.surface.baseBrightness = currentBrightness()

        wireButtons()
        wireSeekBar()

        handleIntent(intent)
        setControlsVisible(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        autoHide.removeCallbacks(hideControlsRunnable)
        engine.listener = null
        engine.release()
        super.onDestroy()
    }

    // region 外部入口

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { playUri(it.toString()) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val url = text.lineSequence().flatMap { it.split(' ') }.firstOrNull { "://" in it }
                if (url != null) playUri(url)
            }
        }
    }

    private fun playUri(uri: String) {
        hasMedia = true
        binding.hint.visibility = View.GONE
        engine.load(uri)
        engine.setPaused(false)
        showControlsTemporarily()
    }

    // endregion

    // region UI

    private fun wireButtons() {
        binding.btnOpen.setOnClickListener { openDocument.launch(arrayOf("video/*", "audio/*", "*/*")) }
        binding.btnUrl.setOnClickListener { promptUrl() }
        binding.btnPlayPause.setOnClickListener {
            engine.togglePause()
            showControlsTemporarily()
        }
        binding.btnRewind.setOnClickListener {
            engine.seekBy(-10.0)
            showControlsTemporarily()
        }
        binding.btnForward.setOnClickListener {
            engine.seekBy(10.0)
            showControlsTemporarily()
        }
        binding.btnSpeed.setOnClickListener { promptSpeed() }
        binding.btnSubtitle.setOnClickListener { engine.cycleSubtitle() }
        binding.btnAudio.setOnClickListener { engine.cycleAudio() }
        binding.btnAspect.setOnClickListener { engine.toggleAspectRatio() }
        binding.btnHwdec.setOnClickListener { cycleHwdec() }
    }

    private fun wireSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    scrubbing = true
                    showScrub(progressMsOf(progress))
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubbing = true
                autoHide.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
                engine.seekTo(progressMsOf(bar.progress))
                scheduleHide()
            }
        })
    }

    private fun promptUrl() {
        val input = EditText(this).apply {
            hint = "http:// https:// rtmp:// rtsp:// …"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(lastUrl)
        }
        AlertDialog.Builder(this)
            .setTitle("打开网络地址")
            .setView(input)
            .setPositiveButton("播放") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    lastUrl = text
                    playUri(text)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptSpeed() {
        val labels = speedPresets.map { "${it}x" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("倍速")
            .setItems(labels) { _, which ->
                engine.setSpeed(speedPresets[which])
                binding.btnSpeed.text = labels[which]
            }
            .show()
    }

    private fun cycleHwdec() {
        hwdecIndex = (hwdecIndex + 1) % hwdecModes.size
        val (value, label) = hwdecModes[hwdecIndex]
        engine.hwdec = value
        binding.btnHwdec.text = label
    }

    private fun setControlsVisible(visible: Boolean) {
        binding.controls.visibility = if (visible) View.VISIBLE else View.GONE
        binding.hudTitle.visibility = if (visible) View.VISIBLE else View.GONE
        setSystemBarsVisible(visible)
    }

    private fun showControlsTemporarily() {
        setControlsVisible(true)
        scheduleHide()
    }

    private fun scheduleHide() {
        autoHide.removeCallbacks(hideControlsRunnable)
        if (engine.isPlayable && !scrubbing) {
            autoHide.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (visible) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showScrub(targetMs: Long) {
        scrubbing = true
        val duration = engine.durationMs.coerceAtLeast(1L)
        binding.seekBar.max = SEEK_MAX
        binding.seekBar.progress = ((targetMs.toDouble() / duration) * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
        binding.textTime.text = "${formatTime(targetMs)} / ${formatTime(duration)}"
    }

    private fun progressMsOf(progress: Int): Long {
        val duration = engine.durationMs
        if (duration <= 0) return 0L
        return (progress.toDouble() / SEEK_MAX * duration).toLong()
    }

    private fun currentBrightness(): Float {
        val value = window.attributes.screenBrightness
        return if (value < 0f || value > 1f) DEFAULT_BRIGHTNESS else value
    }

    private fun flashHud(text: String) {
        binding.textHud.text = text
        binding.textHud.visibility = View.VISIBLE
        binding.textHud.removeCallbacks(hideHudRunnable)
        binding.textHud.postDelayed(hideHudRunnable, 1200)
    }

    // endregion

    // region MpvEngine.Listener

    override fun onPlayerReady(ready: Boolean) {
        if (ready && hasMedia) binding.hint.visibility = View.GONE
    }

    override fun onPlayStateChanged(paused: Boolean) {
        binding.btnPlayPause.setIconResource(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause)
        if (paused) autoHide.removeCallbacks(hideControlsRunnable) else scheduleHide()
    }

    override fun onProgressChanged(positionMs: Long, durationMs: Long) {
        if (scrubbing) return
        if (durationMs <= 0) {
            binding.textTime.text = formatTime(positionMs)
            return
        }
        binding.seekBar.max = SEEK_MAX
        binding.seekBar.progress = ((positionMs.toDouble() / durationMs) * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
        binding.textTime.text = "${formatTime(positionMs)} / ${formatTime(durationMs)}"
    }

    override fun onTitleChanged(title: String) {
        binding.hudTitle.text = title
    }

    override fun onFileEnded(error: Boolean) {
        binding.btnPlayPause.setIconResource(R.drawable.ic_play_arrow)
        if (error) flashHud("无法播放")
    }

    override fun onNotice(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region PlayerSurfaceView.Host

    override fun onToggleControls() = showControlsTemporarily()

    override fun onTogglePlayPause() = engine.togglePause()

    override fun onSeekBySeconds(seconds: Double) {
        engine.seekBy(seconds)
        showControlsTemporarily()
    }

    override fun onSeekPreview(targetMs: Long) = showScrub(targetMs)

    override fun onSeekCommit(targetMs: Long) {
        engine.seekTo(targetMs)
        showControlsTemporarily()
    }

    override fun onVolumeChanged(targetVolume: Double) {
        engine.setVolume(targetVolume)
        flashHud("音量 ${engine.volume.toInt()}%")
    }

    override fun onBrightnessChanged(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
        flashHud("亮度 ${(value * 100).toInt()}%")
    }

    override fun currentPlaybackState() = PlayerSurfaceView.PlaybackState(
        positionMs = engine.positionMs,
        durationMs = engine.durationMs,
        volume = engine.volume,
    )

    // endregion

    private companion object {
        const val CONTROLS_TIMEOUT_MS = 3500L
        const val SEEK_MAX = 1000
        const val DEFAULT_BRIGHTNESS = 0.5f
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
