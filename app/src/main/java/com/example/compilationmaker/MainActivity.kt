package com.example.compilationmaker

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.compilationmaker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Optimized MainActivity (0.17.38)
 * - Pixel 10 Pro LOW / MEDIUM RISK scan profiles labeled
 * - Menu: Clean up originals + Pixel speed info
 * - Post-success delete original prompt
 * - PiP live progress banner
 * - RESTORED: Select Video + ROI preview (VideoView, frame capture, drag overlay)
 * - RESTORED: runtime permission request (READ_MEDIA_VIDEO / POST_NOTIFICATIONS)
 * Production path = CanonicalScannerBridge + Media3 + WorkManager
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var backgroundStatusBanner: TextView
    private val logTag = "MainActivity"
    private var compilationWorkId: UUID? = null
    private var isBusy = false
    private var terminalHandlingWorkId: UUID? = null
    private var selectedVideoUri: Uri? = null
    private val compilationJobStore by lazy { CompilationJobStore(this) }
    private val checkpointProfiles = compilationScanProfiles()

    // ROI / preview state
    private var previewBitmap: Bitmap? = null
    private var selectedPreviewMs = 0
    private var isScrubbing = false
    private var selectedVideoRotationDegrees = 0
    private var isUpdatingRoiFields = false
    private var roiTouchMode = RoiTouchMode.NONE
    private var roiTouchStartX = 0f
    private var roiTouchStartY = 0f
    private var roiStartXPercent = 0f
    private var roiStartYPercent = 0f
    private var roiStartWPercent = 0f
    private var roiStartHPercent = 0f
    private val roiCornerHitPx = 28f
    private val defaultScanWindow = ScanWindow(0.0f, 0.8f, 0.10f, 0.30f)
    private val previewHandler = Handler(Looper.getMainLooper())

    private val permissionRequestLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                binding.roiStatusText.text = "Permissions granted"
            } else {
                binding.roiStatusText.text = "Video access permission is still required"
            }
        }

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val picked = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    picked,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // optional for this session
            }
            onVideoSelected(picked)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        backgroundStatusBanner = binding.backgroundStatusBanner
        setupScanSpeedSpinner()
        wirePreviewUi()
        binding.selectButton.setOnClickListener { launchVideoPicker() }
        binding.processButton.setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Pick a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(
                this,
                "Video + ROI ready. Full pipeline uses WorkManager (see restoreActiveCompilationWork).",
                Toast.LENGTH_LONG
            ).show()
        }
        requestPermissionsIfNeeded()
        restoreActiveCompilationWork()
    }

    override fun onPause() {
        super.onPause()
        stopPreviewProgressUpdates()
        runCatching { binding.videoPreview.stopPlayback() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreviewProgressUpdates()
        previewBitmap?.recycle()
        previewBitmap = null
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.POST_NOTIFICATIONS
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionRequestLauncher.launch(needed.toTypedArray())
        }
    }

    private fun launchVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/mp4", "video/webm", "video/quicktime", "video/*"))
        }
        videoPickerLauncher.launch(intent)
    }

    private fun onVideoSelected(picked: Uri) {
        if (hasActiveCompilation()) {
            Toast.makeText(this, "A compilation is already active.", Toast.LENGTH_SHORT).show()
            restoreActiveCompilationWork()
            return
        }
        selectedVideoUri = picked
        loadSelectedVideoMetadata(picked)
        binding.selectedVideo.text = picked.toString()
        binding.roiStatusText.text = "Video selected — scrub and capture a frame for ROI"
        Toast.makeText(this, "Video selected", Toast.LENGTH_SHORT).show()
        setupVideoPreview(picked)
    }

    private fun wirePreviewUi() {
        val mediaController = MediaController(this)
        mediaController.setAnchorView(binding.videoPreview)
        binding.videoPreview.setMediaController(mediaController)

        binding.previewSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                isScrubbing = true
                selectedPreviewMs = progress
                binding.videoPreview.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isScrubbing = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                selectedPreviewMs = seekBar.progress
                binding.videoPreview.seekTo(selectedPreviewMs)
                isScrubbing = false
            }
        })

        binding.captureFrameButton.setOnClickListener {
            if (hasActiveCompilation()) {
                Toast.makeText(this, "ROI capture locked while compilation is active", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            captureFrame(selectedPreviewMs)
        }

        binding.refreshRoiButton.setOnClickListener {
            if (previewBitmap != null) {
                updateRoiOverlay()
                binding.roiStatusText.text = "ROI refreshed from input values"
            } else {
                binding.roiStatusText.text = "Capture a frame first to preview ROI box"
            }
        }

        binding.videoPreview.setOnPreparedListener { mediaPlayer ->
            if (hasActiveCompilation()) return@setOnPreparedListener
            val duration = max(1, mediaPlayer.duration)
            binding.previewSeekBar.max = duration
            binding.previewSeekBar.isEnabled = true
            binding.captureFrameButton.isEnabled = true
            selectedPreviewMs = 0
            binding.previewSeekBar.progress = 0
            startPreviewProgressUpdates()
            // Auto-capture first frame so second box is immediately usable
            captureFrame(0)
        }

        binding.videoPreview.setOnCompletionListener {
            selectedPreviewMs = 0
            binding.previewSeekBar.progress = 0
        }

        binding.frameImage.setOnTouchListener { _, event ->
            if (hasActiveCompilation() || previewBitmap == null) return@setOnTouchListener true
            val width = binding.frameImage.width.toFloat()
            val height = binding.frameImage.height.toFloat()
            if (width <= 0f || height <= 0f) return@setOnTouchListener true

            val window = readScanWindow()
            val boxW = max(1f, width * window.widthPercent)
            val boxH = max(1f, height * window.heightPercent)
            var left = width * window.xPercent
            var top = height * window.yPercent
            left = left.coerceIn(0f, max(0f, width - boxW))
            top = top.coerceIn(0f, max(0f, height - boxH))
            val right = left + boxW
            val bottom = top + boxH

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    roiTouchStartX = event.x
                    roiTouchStartY = event.y
                    roiStartXPercent = window.xPercent
                    roiStartYPercent = window.yPercent
                    roiStartWPercent = window.widthPercent
                    roiStartHPercent = window.heightPercent
                    roiTouchMode = when {
                        event.x >= right - roiCornerHitPx && event.y >= bottom - roiCornerHitPx &&
                            event.x <= right && event.y <= bottom -> RoiTouchMode.RESIZE
                        event.x in left..right && event.y in top..bottom -> RoiTouchMode.MOVE
                        else -> RoiTouchMode.NONE
                    }
                    if (roiTouchMode == RoiTouchMode.NONE) {
                        setScanAreaFromPercents(
                            ((event.x - boxW / 2f) / width).coerceIn(0f, 1f),
                            ((event.y - boxH / 2f) / height).coerceIn(0f, 1f),
                            window.widthPercent,
                            window.heightPercent
                        )
                        updateRoiOverlay()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (roiTouchMode == RoiTouchMode.NONE) return@setOnTouchListener true
                    val dx = event.x - roiTouchStartX
                    val dy = event.y - roiTouchStartY
                    when (roiTouchMode) {
                        RoiTouchMode.MOVE -> {
                            val nextLeft = (roiStartXPercent * width + dx).coerceIn(0f, max(0f, width - boxW))
                            val nextTop = (roiStartYPercent * height + dy).coerceIn(0f, max(0f, height - boxH))
                            setScanAreaFromPercents(
                                nextLeft / width,
                                nextTop / height,
                                roiStartWPercent,
                                roiStartHPercent
                            )
                        }
                        RoiTouchMode.RESIZE -> {
                            val maxW = 1f - roiStartXPercent
                            val maxH = 1f - roiStartYPercent
                            val nextW = ((roiStartWPercent * width + dx) / width).coerceIn(0.01f, maxW)
                            val nextH = ((roiStartHPercent * height + dy) / height).coerceIn(0.01f, maxH)
                            setScanAreaFromPercents(roiStartXPercent, roiStartYPercent, nextW, nextH)
                        }
                        RoiTouchMode.NONE -> {}
                    }
                    updateRoiOverlay()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    roiTouchMode = RoiTouchMode.NONE
                    true
                }
                else -> false
            }
        }

        setScanAreaFromPercents(
            defaultScanWindow.xPercent,
            defaultScanWindow.yPercent,
            defaultScanWindow.widthPercent,
            defaultScanWindow.heightPercent
        )
        binding.captureFrameButton.isEnabled = false
        binding.previewSeekBar.isEnabled = false
    }

    private fun setupVideoPreview(uri: Uri) {
        binding.frameContainer.visibility = View.VISIBLE
        binding.roiOverlay.visibility = View.GONE
        previewBitmap?.recycle()
        previewBitmap = null
        binding.frameImage.setImageDrawable(null)
        binding.previewSeekBar.isEnabled = false
        binding.captureFrameButton.isEnabled = false
        binding.previewSeekBar.progress = 0
        selectedPreviewMs = 0
        stopPreviewProgressUpdates()
        binding.videoPreview.setVideoURI(uri)
        binding.videoPreview.requestFocus()
        binding.videoPreview.start()
        binding.videoPreview.pause()
    }

    private fun captureFrame(positionMs: Int) {
        val source = selectedVideoUri ?: return
        val duration = max(1, binding.previewSeekBar.max)
        val safePosition = positionMs.coerceIn(0, duration)
        lifecycleScope.launch {
            binding.roiStatusText.text = "Capturing frame at ${safePosition}ms…"
            val frame = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, source)
                    val targetW = max(640, binding.frameImage.width).coerceAtMost(1280)
                    val targetH = max(360, binding.frameImage.height).coerceAtMost(720)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            safePosition * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            targetW,
                            targetH
                        ) ?: retriever.getFrameAtTime(
                            safePosition * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    } else {
                        retriever.getFrameAtTime(
                            safePosition * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }
                } finally {
                    retriever.release()
                }
            }
            if (frame == null) {
                binding.roiStatusText.text = "Unable to capture frame for ROI"
                return@launch
            }
            val normalized = normalizeBitmapForRoi(frame, selectedVideoRotationDegrees)
            if (normalized !== frame) frame.recycle()
            previewBitmap?.recycle()
            previewBitmap = normalized
            binding.frameImage.setImageBitmap(normalized)
            selectedPreviewMs = safePosition
            binding.roiOverlay.visibility = View.VISIBLE
            updateRoiOverlay()
            binding.roiStatusText.text = "Captured frame. Drag box to set ROI · corner to resize"
        }
    }

    private fun loadSelectedVideoMetadata(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            selectedVideoRotationDegrees =
                ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0) % 360 + 360) % 360
        } catch (_: Exception) {
            selectedVideoRotationDegrees = 0
        } finally {
            retriever.release()
        }
    }

    private fun normalizeBitmapForRoi(source: Bitmap, rotationDegrees: Int): Bitmap {
        return when (((rotationDegrees % 360) + 360) % 360) {
            0 -> source
            else -> {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }
        }
    }

    private fun setScanAreaFromPercents(x: Float, y: Float, w: Float, h: Float) {
        isUpdatingRoiFields = true
        try {
            binding.scanAreaX.setText(String.format(Locale.US, "%.2f", x.coerceIn(0f, 1f) * 100f))
            binding.scanAreaY.setText(String.format(Locale.US, "%.2f", y.coerceIn(0f, 1f) * 100f))
            binding.scanAreaWidth.setText(String.format(Locale.US, "%.2f", w.coerceIn(0.01f, 1f) * 100f))
            binding.scanAreaHeight.setText(String.format(Locale.US, "%.2f", h.coerceIn(0.01f, 1f) * 100f))
        } finally {
            isUpdatingRoiFields = false
        }
    }

    private fun readScanWindow(): ScanWindow {
        fun parse(raw: String?, fallback: Float): Float {
            if (raw.isNullOrBlank()) return fallback
            val v = raw.trim().toFloatOrNull() ?: return fallback
            return if (v > 1f) v / 100f else v
        }
        val x = parse(binding.scanAreaX.text?.toString(), defaultScanWindow.xPercent).coerceIn(0f, 1f)
        val y = parse(binding.scanAreaY.text?.toString(), defaultScanWindow.yPercent).coerceIn(0f, 1f)
        val maxW = max(0.01f, 1f - x)
        val maxH = max(0.01f, 1f - y)
        val w = parse(binding.scanAreaWidth.text?.toString(), defaultScanWindow.widthPercent).coerceIn(0.01f, maxW)
        val h = parse(binding.scanAreaHeight.text?.toString(), defaultScanWindow.heightPercent).coerceIn(0.01f, maxH)
        return ScanWindow(x, y, w, h)
    }

    private fun updateRoiOverlay() {
        val window = readScanWindow()
        binding.frameImage.post {
            val width = binding.frameImage.width.toFloat()
            val height = binding.frameImage.height.toFloat()
            if (width <= 0f || height <= 0f) return@post
            val boxW = max(1f, width * window.widthPercent)
            val boxH = max(1f, height * window.heightPercent)
            val left = max(0f, min(width - 1f, width * window.xPercent))
            val top = max(0f, min(height - 1f, height * window.yPercent))
            val params = binding.roiOverlay.layoutParams as FrameLayout.LayoutParams
            params.width = boxW.toInt().coerceAtLeast(1)
            params.height = boxH.toInt().coerceAtLeast(1)
            params.leftMargin = left.toInt().coerceAtLeast(0)
            params.topMargin = top.toInt().coerceAtLeast(0)
            binding.roiOverlay.layoutParams = params
            binding.roiOverlay.visibility = if (previewBitmap == null) View.GONE else View.VISIBLE
        }
    }

    private val previewProgressUpdater = object : Runnable {
        override fun run() {
            if (binding.videoPreview.isPlaying && binding.previewSeekBar.isEnabled && !isScrubbing) {
                val current = binding.videoPreview.currentPosition
                if (current >= 0 && current <= binding.previewSeekBar.max) {
                    selectedPreviewMs = current
                    if (!binding.previewSeekBar.isPressed) {
                        binding.previewSeekBar.progress = current
                    }
                }
            }
            previewHandler.postDelayed(this, 250L)
        }
    }

    private fun startPreviewProgressUpdates() {
        stopPreviewProgressUpdates()
        previewHandler.postDelayed(previewProgressUpdater, 250L)
    }

    private fun stopPreviewProgressUpdates() {
        previewHandler.removeCallbacks(previewProgressUpdater)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_cleanup_originals -> {
                startActivity(Intent(this, CleanupActivity::class.java))
                true
            }
            R.id.action_pixel10_speed_info -> {
                showPixelInfoDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            backgroundStatusBanner.visibility = View.VISIBLE
        }
    }

    override fun onUserLeaveHint() {
        if (hasActiveCompilation() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            runCatching { enterPictureInPictureMode(params) }
        }
        super.onUserLeaveHint()
    }

    private fun setupScanSpeedSpinner() {
        val labels = checkpointProfiles.map { it.label }.toTypedArray()
        binding.scanSpeedPicker.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val lowRiskIndex = checkpointProfiles.indexOfFirst { it.scannerProfileId == "QUICK_5_MIN" }
        if (lowRiskIndex >= 0) binding.scanSpeedPicker.setSelection(lowRiskIndex)
    }

    private fun showPixelInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("Pixel 10 Pro speed modes")
            .setMessage(
                "LOW RISK: Parallel OCR lanes (QUICK 5m) — more concurrent decoder/OCR work while thermal status is cool.\n\n" +
                    "MEDIUM RISK: MediaCodec + lean parallel (FAST 30s) — prefers hardware decode path + reduced frame width for higher throughput.\n\n" +
                    "Both preserve the existing PTS-aware transition accuracy and WorkManager pipeline."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun hasActiveCompilation(): Boolean {
        val record = compilationJobStore.load() ?: return false
        return record.state.isActive
    }

    private fun restoreActiveCompilationWork() {
        val record = compilationJobStore.load() ?: return
        if (record.workId.isBlank()) return
        runCatching {
            val id = UUID.fromString(record.workId)
            compilationWorkId = id
            observeCompilationWork(id)
        }
    }

    private fun observeCompilationWork(workId: UUID) {
        WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(workId)
            .observe(this) { info ->
                if (info != null) handleCompilationWorkInfo(info)
            }
    }

    private fun handleCompilationWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                val percent = workInfo.progress.getInt(CompilationWorker.KEY_PROGRESS_PERCENT, 0)
                val message = workInfo.progress.getString(CompilationWorker.KEY_PROGRESS_MESSAGE) ?: "Processing"
                emitCompilationProgress(message, percent)
                isBusy = true
            }
            WorkInfo.State.SUCCEEDED -> handleSucceededCompilation(workInfo)
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                isBusy = false
                backgroundStatusBanner.visibility = View.GONE
            }
            else -> {}
        }
    }

    private fun handleSucceededCompilation(workInfo: WorkInfo) {
        if (terminalHandlingWorkId == workInfo.id) return
        terminalHandlingWorkId = workInfo.id
        lifecycleScope.launch {
            val saved = compilationJobStore.load()
            val outputPath = workInfo.outputData.getString(CompilationWorker.KEY_OUTPUT_PATH)
                .orEmpty().ifBlank { saved?.expectedOutputPath.orEmpty() }

            val verified = withContext(Dispatchers.IO) {
                val f = File(outputPath)
                if (f.exists() && f.length() > 0) f else null
            }

            if (verified != null) {
                emitCompilationProgress("Compilation complete", 100)
                val sourceForCleanup = saved?.sourceUri?.takeIf { it.isNotBlank() }
                if (sourceForCleanup != null) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete original source video?")
                        .setMessage("Compilation finished successfully. Delete the original source video to free space?")
                        .setPositiveButton("Delete original") { _, _ ->
                            val deleted = SourceHistoryStore(this@MainActivity).tryDeleteSource(sourceForCleanup)
                            AppLog.i(this@MainActivity, logTag, "User source delete uri=$sourceForCleanup deleted=$deleted")
                        }
                        .setNegativeButton("Keep original", null)
                        .show()
                }
            }
            isBusy = false
            backgroundStatusBanner.visibility = View.GONE
        }
    }

    private fun emitCompilationProgress(message: String, percent: Int) {
        val percentValue = percent.coerceIn(0, 100)
        backgroundStatusBanner.text = "Background processing: $percentValue% • $message"
        backgroundStatusBanner.visibility = View.VISIBLE
    }
}

private enum class RoiTouchMode { NONE, MOVE, RESIZE }

internal data class ScanProfile(
    val label: String,
    val frameStepMs: Long,
    val mode: ScanMode,
    val scannerProfileId: String? = null
)

internal fun compilationScanProfiles(): Array<ScanProfile> = arrayOf(
    ScanProfile("Pixel 10 Pro [MEDIUM RISK]: MediaCodec+lean parallel (FAST 30s)", 30_000L, ScanMode.StableCheckpoint, "FAST"),
    ScanProfile("Monotonic Turbo PTS (3m adaptive, persistent 1→N)", 180_000L, ScanMode.StableCheckpoint, "MONOTONIC_3_MIN"),
    ScanProfile("Pixel 10 Pro [LOW RISK]: Parallel lanes (QUICK 5m)", 300_000L, ScanMode.StableCheckpoint, "QUICK_5_MIN"),
    ScanProfile("Canonical Balanced PTS (10s)", 10_000L, ScanMode.StableCheckpoint, "BALANCED"),
    ScanProfile("Canonical Precise PTS (3s)", 3_000L, ScanMode.StableCheckpoint, "PRECISE"),
    ScanProfile("Dense (125ms) [debug]", 125L, ScanMode.Experimental)
)

enum class ScanMode { StableCheckpoint, Experimental }
