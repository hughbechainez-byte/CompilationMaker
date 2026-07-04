package com.example.compilationmaker

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Base64
import android.view.View
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.compilationmaker.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedVideoUri: Uri? = null
    private var isBusy = false
    private var isScrubbing = false
    private val statusFeedLines = ArrayDeque<String>()
    private val logTag = "CompilationMaker"
    private val previewHandler = Handler(Looper.getMainLooper())
    private var selectedPreviewMs = 0
    private var lastProgressText = ""
    private var lastProgressPercent = -1
    private var lastProgressUpdateMs = 0L
    private var notificationChannelReady = false
    private var previewBitmap: Bitmap? = null
    private var pendingCompilationFile: File? = null
    private var pendingCompilationFormat: ExportFormat? = null
    private var isCompilationPreviewScrubbing = false
    private var selectedCompilationPreviewMs = 0
    private var roiTouchMode = RoiTouchMode.NONE
    private var roiTouchStartX = 0f
    private var roiTouchStartY = 0f
    private var roiStartXPercent = 0f
    private var roiStartYPercent = 0f
    private var roiStartWPercent = 0f
    private var roiStartHPercent = 0f
    private var isUpdatingRoiFields = false
    private var selectedVideoRotationDegrees = 0
    private var selectedVideoWidth = 0
    private var selectedVideoHeight = 0
    private val roiPrefs by lazy { getSharedPreferences("roi_prefs", MODE_PRIVATE) }
    private val roiCornerHitPx = 28f

    private val permissionRequestLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                binding.statusText.text = "Ready"
            } else {
                binding.statusText.text = "Permission required to read videos"
            }
        }

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val picked = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    picked,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // optional persist permission not required for this session
            }
            onVideoSelected(picked)
        }

    private val preferredVideoPickerPackages = arrayOf(
        "com.google.android.documentsui",
        "com.android.documentsui"
    )

    private fun onVideoSelected(picked: Uri) {
        selectedVideoUri = picked
        loadSelectedVideoMetadata(picked)
        restoreRoiState(picked)
        binding.selectedVideo.text = picked.toString()
        emitProgress("Video selected: ${picked.toString().take(60)}", 2)
        setupVideoPreview(picked)
    }

    private fun launchVideoPicker() {
        val baseIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val packageIntent = preferredVideoPickerPackages.firstNotNullOfOrNull { pkg ->
            Intent(baseIntent).setPackage(pkg).also { candidate ->
                return@firstNotNullOfOrNull if (candidate.resolveActivity(packageManager) != null) candidate else null
            }
        }
        val fallback = baseIntent
        val launchIntent = packageIntent ?: fallback
        launchIntent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/mp4", "video/webm", "video/quicktime", "video/*"))
        if (packageIntent != null) {
            videoPickerLauncher.launch(launchIntent)
        } else {
            videoPickerLauncher.launch(Intent.createChooser(launchIntent, "Select video"))
        }
    }

    private lateinit var qualitySpinner: Spinner
    private lateinit var formatSpinner: Spinner
    private lateinit var scanSpeedSpinner: Spinner
    private lateinit var videoPreview: VideoView
    private lateinit var previewSeekBar: SeekBar
    private lateinit var compilationPreview: VideoView
    private lateinit var compilationPreviewSeekBar: SeekBar
    private lateinit var playCompilationPreviewButton: Button
    private lateinit var saveCompilationButton: Button
    private lateinit var discardCompilationButton: Button
    private lateinit var frameImage: ImageView
    private lateinit var frameContainer: FrameLayout
    private lateinit var roiOverlay: View
    private lateinit var captureFrameButton: Button
    private lateinit var refreshRoiButton: Button
    private lateinit var scanAreaX: EditText
    private lateinit var scanAreaY: EditText
    private lateinit var scanAreaWidth: EditText
    private lateinit var scanAreaHeight: EditText
    private lateinit var progressPercentText: TextView
    private lateinit var statusFeedText: TextView
    private lateinit var statusFeedScroll: ScrollView
    private lateinit var checkUpdatesButton: Button
    private lateinit var transitionStyleSpinner: Spinner

    private val qualityOptions = arrayOf(ExportQuality.Low, ExportQuality.Medium, ExportQuality.High)
    private val formatOptions = arrayOf(ExportFormat.Mp4, ExportFormat.Webm, ExportFormat.Mov)
    private val transitionStyleOptions = arrayOf(TransitionStyle.Instant, TransitionStyle.Gradual)
    private val scanProfiles = arrayOf(
        ScanProfile("Very fast (0.5s)", 500L, ScanMode.Fast),
        ScanProfile("Balanced (1s)", 1_000L, ScanMode.Fast),
        ScanProfile("Slow (2s)", 2_000L, ScanMode.Fast),
        ScanProfile("Very slow (4s)", 4_000L, ScanMode.Fast),
        ScanProfile("3-minute checkpoints", 180_000L, ScanMode.Checkpoints3Min),
        ScanProfile("4-minute checkpoints", 240_000L, ScanMode.Checkpoints3Min),
        ScanProfile("5-minute checkpoints", 300_000L, ScanMode.Checkpoints3Min)
    )
    private val defaultScanWindow = ScanWindow(0.0f, 0.8f, 0.10f, 0.30f)
    private val progressNotificationChannelId = "compilation_progress"
    private val progressNotificationId = 6106
    private val updateNotificationChannelId = "compilation_updates"
    private val updateNotificationId = 6110
    private val updateManifestRawEndpoint =
        "https://raw.githubusercontent.com/hughbechainez-byte/CompilationMaker/master/app-update.json"
    private val updateManifestBlobEndpoint =
        "https://api.github.com/repos/hughbechainez-byte/CompilationMaker/contents/app-update.json"
    private val fallbackReleaseEndpoint =
        "https://api.github.com/repos/hughbechainez-byte/CompilationMaker/releases/latest"
    private val updateCooldownMs = 12L * 60L * 60L * 1000L
    private val updatePrefs by lazy { getSharedPreferences("update_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpUi()
        ensureProgressNotificationChannel()
        ensureUpdateNotificationChannel()
        requestPermissionsIfNeeded()
        checkForUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopPreviewProgressUpdates()
        videoPreview.stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreviewProgressUpdates()
        stopCompilationPreviewProgressUpdates()
        pendingCompilationFile?.delete()
        pendingCompilationFile = null
        previewBitmap?.recycle()
        previewBitmap = null
    }

    private fun setUpUi() {
        qualitySpinner = binding.qualityPicker
        formatSpinner = binding.formatPicker
        scanSpeedSpinner = binding.scanSpeedPicker
        transitionStyleSpinner = binding.transitionStylePicker
        videoPreview = binding.videoPreview
        previewSeekBar = binding.previewSeekBar
        compilationPreview = binding.compilationPreview
        compilationPreviewSeekBar = binding.compilationPreviewSeekBar
        playCompilationPreviewButton = binding.playCompilationPreviewButton
        saveCompilationButton = binding.saveCompilationButton
        discardCompilationButton = binding.discardCompilationButton
        frameImage = binding.frameImage
        frameContainer = binding.frameContainer
        roiOverlay = binding.roiOverlay
        captureFrameButton = binding.captureFrameButton
        refreshRoiButton = binding.refreshRoiButton
        scanAreaX = binding.scanAreaX
        scanAreaY = binding.scanAreaY
        scanAreaWidth = binding.scanAreaWidth
        scanAreaHeight = binding.scanAreaHeight
        progressPercentText = binding.progressPercentText
        statusFeedText = binding.statusFeedText
        statusFeedScroll = binding.statusFeedScroll
        checkUpdatesButton = binding.checkUpdatesButton
        roiOverlay.isClickable = false
        roiOverlay.isFocusable = false
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoPreview)
        videoPreview.setMediaController(mediaController)

        binding.selectButton.setOnClickListener {
            launchVideoPicker()
        }

        previewSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                isScrubbing = true
                selectedPreviewMs = progress
                videoPreview.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isScrubbing = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                selectedPreviewMs = seekBar.progress
                videoPreview.seekTo(selectedPreviewMs)
                isScrubbing = false
            }
        })

        captureFrameButton.setOnClickListener {
            captureFrame(selectedPreviewMs)
        }

        videoPreview.setOnPreparedListener { mediaPlayer ->
            val duration = max(1, mediaPlayer.duration)
            previewSeekBar.max = duration
            previewSeekBar.isEnabled = true
            captureFrameButton.isEnabled = true
            selectedPreviewMs = 0
            previewSeekBar.progress = 0
            startPreviewProgressUpdates()
            captureFrame(0)
        }

        videoPreview.setOnCompletionListener {
            selectedPreviewMs = 0
            previewSeekBar.progress = 0
        }

        compilationPreview.setOnPreparedListener { mediaPlayer ->
            val duration = max(1, mediaPlayer.duration)
            compilationPreviewSeekBar.max = duration
            compilationPreviewSeekBar.progress = 0
            compilationPreviewSeekBar.isEnabled = true
            selectedCompilationPreviewMs = 0
            compilationPreview.seekTo(0)
            compilationPreview.pause()
            playCompilationPreviewButton.text = "Play"
            startCompilationPreviewProgressUpdates()
        }

        compilationPreview.setOnCompletionListener {
            selectedCompilationPreviewMs = 0
            compilationPreviewSeekBar.progress = 0
            playCompilationPreviewButton.text = "Play"
        }

        compilationPreviewSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                isCompilationPreviewScrubbing = true
                selectedCompilationPreviewMs = progress
                compilationPreview.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isCompilationPreviewScrubbing = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                selectedCompilationPreviewMs = seekBar.progress
                compilationPreview.seekTo(selectedCompilationPreviewMs)
                isCompilationPreviewScrubbing = false
            }
        })

        playCompilationPreviewButton.setOnClickListener {
            if (pendingCompilationFile == null) {
                emitProgress("Build a compilation preview first.", 100)
                return@setOnClickListener
            }
            if (compilationPreview.isPlaying) {
                compilationPreview.pause()
                playCompilationPreviewButton.text = "Play"
            } else {
                compilationPreview.start()
                playCompilationPreviewButton.text = "Pause"
                startCompilationPreviewProgressUpdates()
            }
        }

        saveCompilationButton.setOnClickListener {
            savePendingCompilation()
        }

        discardCompilationButton.setOnClickListener {
            clearPendingCompilationPreview(deleteFile = true)
            emitProgress("Compilation preview discarded.", 100)
        }

        frameImage.setOnTouchListener { _, event ->
            if (previewBitmap == null) {
                return@setOnTouchListener true
            }

            val width = frameImage.width.toFloat()
            val height = frameImage.height.toFloat()
            if (width <= 0f || height <= 0f) {
                return@setOnTouchListener true
            }

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
                        event.x >= right - roiCornerHitPx && event.y >= bottom - roiCornerHitPx && event.x <= right && event.y <= bottom ->
                            RoiTouchMode.RESIZE
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
                            setScanAreaFromPercents(
                                roiStartXPercent,
                                roiStartYPercent,
                                nextW,
                                nextH
                            )
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
        wireScanAreaInputs()
        refreshRoiButton.setOnClickListener {
            val window = readScanWindow()
            setScanAreaFromPercents(window.xPercent, window.yPercent, window.widthPercent, window.heightPercent)
            if (previewBitmap != null) {
                updateRoiOverlay()
                emitProgress("ROI refreshed from input values", 4)
            } else {
                emitProgress("ROI values saved. Capture a frame to preview ROI box.", 4)
            }
        }

        binding.processButton.setOnClickListener {
            if (isBusy) return@setOnClickListener
            val source = selectedVideoUri
            if (source == null) {
                Toast.makeText(this, "Pick a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val quality = qualityOptions[qualitySpinner.selectedItemPosition]
            val format = formatOptions[formatSpinner.selectedItemPosition]
            val transitionStyle = transitionStyleOptions[transitionStyleSpinner.selectedItemPosition.coerceIn(0, transitionStyleOptions.lastIndex)]
            runCompilation(source, quality, format, transitionStyle)
        }
        checkUpdatesButton.setOnClickListener {
            checkForUpdates(force = true)
        }

        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            qualityOptions.map { it.label },
        )

        formatSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            formatOptions.map { it.label },
        )
        scanSpeedSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            scanProfiles.map { it.label },
        )
        scanSpeedSpinner.setSelection(4, false)
        transitionStyleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            transitionStyleOptions.map { it.label },
        )
        transitionStyleSpinner.setSelection(1, false)
        setScanAreaFromPercents(
            defaultScanWindow.xPercent,
            defaultScanWindow.yPercent,
            defaultScanWindow.widthPercent,
            defaultScanWindow.heightPercent
        )

        binding.progressBar.max = 100
        progressPercentText.text = "0%"
        clearStatusFeed("Ready")
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.POST_NOTIFICATIONS
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty() && needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionRequestLauncher.launch(needed.toTypedArray())
        }
    }

    private fun runCompilation(uri: Uri, quality: ExportQuality, format: ExportFormat, transitionStyle: TransitionStyle) {
        lifecycleScope.launch {
            isBusy = true
            setUiBusy(true)
            clearPendingCompilationPreview(deleteFile = true)
            clearStatusFeed("Starting")
            emitProgress("Analyzing number changes...", 0)

            try {
                val engine = VideoCompilationEngine(this@MainActivity)
                val scanWindow = readScanWindow()
                val scanProfile = selectedScanProfile()
                val timingBuckets = linkedMapOf<String, Long>()
                emitProgress(
                    "Scan mode: ${scanProfile.label} (step ${scanProfile.frameStepMs}ms), focus area X:${String.format(Locale.US, "%.1f", scanWindow.xPercent * 100f)}% Y:${String.format(Locale.US, "%.1f", scanWindow.yPercent * 100f)}%",
                    0
                )
                val scanResult = engine.findNumberTransitionSegments(
                    uri,
                    scanProfile.frameStepMs,
                    scanProfile.mode,
                    scanWindow,
                    selectedVideoRotationDegrees,
                    scanProfile.label,
                    transitionStyle
                ) { message, percent ->
                    emitProgress(message, (percent * 50 / 100).coerceIn(0, 50))
                }
                timingBuckets["scan"] = scanResult.timing.totalMs()
                emitProgress(
                    "Scan timing (ms): decode=${scanResult.timing.decodeMs} crop=${scanResult.timing.cropMs} preprocess=${scanResult.timing.preprocessMs} ocr=${scanResult.timing.ocrMs} merge=${scanResult.timing.mergeMs}",
                    51
                )

                val windows = scanResult.segments.toMutableList()
                if (windows.isEmpty()) {
                    emitProgress("No number transitions found. Falling back to a short full-video clip.", 52)
                    val durationMs = engine.getDurationMs(uri)
                    if (durationMs <= 0L) {
                        emitProgress("Unable to read source duration.", 100)
                        return@launch
                    }
                    windows.clear()
                    windows.add(SegmentWindow(0L, min(durationMs, 30_000L)))
                }
                val clipWindowMs = windows.sumOf { it.endMs - it.startMs }.coerceAtLeast(1L)
                emitProgress(
                    "Scan complete: ${windows.size} segments, combined duration ${formatDurationMs(clipWindowMs)}",
                    54
                )

                emitProgress("Preparing ${windows.size} trim/copy segments (${formatDurationMs(clipWindowMs)} source span)...", 55)
                var compiledOutput: File? = null
                val exportTiming = measureTimeMillis {
                    compiledOutput = engine.renderCompilation(uri, windows, quality, format, transitionStyle) { message, percent ->
                        emitProgress(message, percent)
                    }
                }
                timingBuckets["export"] = exportTiming

                val output = compiledOutput ?: throw IllegalStateException("Compilation output missing")
                showPendingCompilationPreview(output, format)
                emitProgress("Export timing (ms): preview=$exportTiming. Review preview, then save or discard.", 100)
                emitProgress(
                    "Performance summary: scan=${timingBuckets["scan"]}ms preview=${timingBuckets["export"]}ms",
                    100
                )
                emitProgress("Scan report stored: ${engine.latestScanReportPath}", 100)
                Toast.makeText(this@MainActivity, "Preview ready", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(logTag, "Compilation failed", e)
                    emitProgress("Error: ${e.message ?: "failed"}", 100)
                }
            } finally {
                isBusy = false
                setUiBusy(false)
            }
        }
    }

    private fun setupVideoPreview(uri: Uri) {
        frameContainer.visibility = View.VISIBLE
        roiOverlay.visibility = View.GONE
        previewBitmap?.recycle()
        previewBitmap = null
        frameImage.setImageDrawable(null)
        previewSeekBar.isEnabled = false
        captureFrameButton.isEnabled = false
        previewSeekBar.progress = 0
        selectedPreviewMs = 0
        stopPreviewProgressUpdates()
        videoPreview.setVideoURI(uri)
        videoPreview.requestFocus()
        videoPreview.start()
        videoPreview.pause()
    }

    private fun captureFrame(positionMs: Int) {
        val source = selectedVideoUri ?: return
        val duration = max(1, previewSeekBar.max)
        val safePosition = positionMs.coerceIn(0, duration)
        lifecycleScope.launch {
            emitProgress("Capturing frame for ROI at ${safePosition}ms", 5)
            val frame = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                val targetWidth = max(640, frameImage.width).coerceAtMost(1280)
                val targetHeight = max(360, frameImage.height).coerceAtMost(720)
                try {
                    retriever.setDataSource(this@MainActivity, source)
                    retriever.getScaledFrameAtTime(
                        safePosition * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight
                    ) ?: retriever.getFrameAtTime(
                        safePosition * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                } finally {
                    retriever.release()
                }
            }
            if (frame == null) {
                emitProgress("Unable to capture frame for ROI", 5)
                return@launch
            }
            val normalizedFrame = normalizeBitmapForRoi(frame, selectedVideoRotationDegrees)
            if (normalizedFrame !== frame) {
                frame.recycle()
            }
            previewBitmap?.recycle()
            previewBitmap = normalizedFrame
            frameImage.setImageBitmap(normalizedFrame)
            selectedPreviewMs = safePosition
            roiOverlay.visibility = View.VISIBLE
            updateRoiOverlay()
            emitProgress("Captured frame. Tap within frame to place ROI center.", 6)
        }
    }

    private fun setScanAreaFromPercents(xPercent: Float, yPercent: Float, widthPercent: Float, heightPercent: Float) {
        isUpdatingRoiFields = true
        val cx = xPercent.coerceIn(0f, 1f)
        val cy = yPercent.coerceIn(0f, 1f)
        val cw = widthPercent.coerceIn(0.01f, 1f)
        val ch = heightPercent.coerceIn(0.01f, 1f)
        try {
            scanAreaX.setText(String.format(Locale.US, "%.2f", cx * 100f))
            scanAreaY.setText(String.format(Locale.US, "%.2f", cy * 100f))
            scanAreaWidth.setText(String.format(Locale.US, "%.2f", cw * 100f))
            scanAreaHeight.setText(String.format(Locale.US, "%.2f", ch * 100f))
        } finally {
            isUpdatingRoiFields = false
        }
        addStatusLine(
            "ROI set to X:${String.format(Locale.US, "%.1f", xPercent * 100f)}% Y:${String.format(Locale.US, "%.1f", yPercent * 100f)}% "
                + "W:${String.format(Locale.US, "%.1f", widthPercent * 100f)}% H:${String.format(Locale.US, "%.1f", heightPercent * 100f)}%"
        )
        persistCurrentRoi()
    }

    private fun wireScanAreaInputs() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingRoiFields || previewBitmap == null) return
                updateRoiOverlay()
            }
        }
        scanAreaX.addTextChangedListener(watcher)
        scanAreaY.addTextChangedListener(watcher)
        scanAreaWidth.addTextChangedListener(watcher)
        scanAreaHeight.addTextChangedListener(watcher)
    }

    private fun updateRoiOverlay() {
        val window = readScanWindow()
        updateRoiOverlay(window)
    }

    private fun updateRoiOverlay(window: ScanWindow) {
        frameImage.post {
            val width = frameImage.width.toFloat()
            val height = frameImage.height.toFloat()
            if (width <= 0f || height <= 0f) return@post

            val boxW = max(1f, width * window.widthPercent)
            val boxH = max(1f, height * window.heightPercent)
            val left = max(0f, min(width - 1f, width * window.xPercent))
            val top = max(0f, min(height - 1f, height * window.yPercent))
            val params = roiOverlay.layoutParams as FrameLayout.LayoutParams
            params.width = boxW.toInt().coerceAtLeast(1)
            params.height = boxH.toInt().coerceAtLeast(1)
            params.leftMargin = left.toInt().coerceAtLeast(0)
            params.topMargin = top.toInt().coerceAtLeast(0)
            roiOverlay.layoutParams = params
            roiOverlay.visibility = if (previewBitmap == null) View.GONE else View.VISIBLE
        }
    }

    private val previewProgressUpdater = object : Runnable {
        override fun run() {
            if (videoPreview.isPlaying && previewSeekBar.isEnabled && !isScrubbing) {
                val current = videoPreview.currentPosition
                if (current >= 0 && current <= previewSeekBar.max) {
                    selectedPreviewMs = current
                    if (!previewSeekBar.isPressed) {
                        previewSeekBar.progress = current
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

    private val compilationPreviewProgressUpdater = object : Runnable {
        override fun run() {
            if (compilationPreview.isPlaying && compilationPreviewSeekBar.isEnabled && !isCompilationPreviewScrubbing) {
                val current = compilationPreview.currentPosition
                if (current >= 0 && current <= compilationPreviewSeekBar.max) {
                    selectedCompilationPreviewMs = current
                    if (!compilationPreviewSeekBar.isPressed) {
                        compilationPreviewSeekBar.progress = current
                    }
                }
            }
            previewHandler.postDelayed(this, 250L)
        }
    }

    private fun startCompilationPreviewProgressUpdates() {
        stopCompilationPreviewProgressUpdates()
        previewHandler.postDelayed(compilationPreviewProgressUpdater, 250L)
    }

    private fun stopCompilationPreviewProgressUpdates() {
        previewHandler.removeCallbacks(compilationPreviewProgressUpdater)
    }

    private fun showPendingCompilationPreview(file: File, format: ExportFormat) {
        pendingCompilationFile = file
        pendingCompilationFormat = format
        binding.compilationPreviewContainer.visibility = View.VISIBLE
        compilationPreviewSeekBar.isEnabled = false
        compilationPreviewSeekBar.progress = 0
        selectedCompilationPreviewMs = 0
        playCompilationPreviewButton.text = "Play"
        playCompilationPreviewButton.isEnabled = true
        saveCompilationButton.isEnabled = true
        discardCompilationButton.isEnabled = true
        compilationPreview.setVideoURI(Uri.fromFile(file))
        compilationPreview.seekTo(0)
        compilationPreview.pause()
    }

    private fun clearPendingCompilationPreview(deleteFile: Boolean) {
        stopCompilationPreviewProgressUpdates()
        runCatching { compilationPreview.stopPlayback() }
        if (deleteFile) {
            pendingCompilationFile?.delete()
        }
        pendingCompilationFile = null
        pendingCompilationFormat = null
        selectedCompilationPreviewMs = 0
        compilationPreviewSeekBar.progress = 0
        compilationPreviewSeekBar.isEnabled = false
        playCompilationPreviewButton.text = "Play"
        binding.compilationPreviewContainer.visibility = View.GONE
    }

    private fun savePendingCompilation() {
        val output = pendingCompilationFile
        val format = pendingCompilationFormat
        if (output == null || format == null) {
            emitProgress("No compilation preview is ready to save.", 100)
            return
        }

        lifecycleScope.launch {
            isBusy = true
            setUiBusy(true)
            try {
                val saveTiming = measureTimeMillis {
                    val saved = saveToPhoneStorage(output, format)
                    emitProgress("Saved: $saved", 100)
                }
                emitProgress("Save timing (ms): save=$saveTiming", 100)
                Toast.makeText(this@MainActivity, "Export saved", Toast.LENGTH_LONG).show()
                clearPendingCompilationPreview(deleteFile = true)
            } catch (e: Exception) {
                Log.e(logTag, "Save failed", e)
                emitProgress("Save failed: ${e.message ?: "failed"}", 100)
            } finally {
                isBusy = false
                setUiBusy(false)
            }
        }
    }

    private suspend fun saveToPhoneStorage(file: File, format: ExportFormat): String = withContext(Dispatchers.IO) {
        val filename = "compilation_${System.currentTimeMillis()}"
        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$filename.${safeFormat.extension}")
            put(MediaStore.Video.Media.MIME_TYPE, safeFormat.mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CompilationMaker")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver: ContentResolver = contentResolver
        fun copyWithProgress(input: java.io.InputStream, out: java.io.OutputStream) {
            val buffer = ByteArray(1024 * 1024)
            var bytes = 0L
            var read: Int
            val sourceLength = file.length().coerceAtLeast(1L)
            var lastReportMs = 0L
            while (true) {
                read = input.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                bytes += read
                val now = SystemClock.elapsedRealtime()
                if (now - lastReportMs >= 300L) {
                    val savedPercent = ((bytes * 100L) / sourceLength).coerceIn(0L, 100L).toInt()
                    emitProgress("Saving output: ${formatBytes(bytes)} / ${formatBytes(sourceLength)} ($savedPercent%)", 95 + savedPercent / 20)
                    lastReportMs = now
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri, "w").use { out ->
                        file.inputStream().use { input ->
                            copyWithProgress(input, out ?: throw IllegalStateException("No output stream"))
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return@withContext uri.toString()
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            }
        }

        val fallbackDir = Environment.getExternalStoragePublicDirectory("${Environment.DIRECTORY_MOVIES}/CompilationMaker")
        fallbackDir.mkdirs()
        val outFile = File(fallbackDir, "$filename.${safeFormat.extension}")
        runCatching {
            file.inputStream().use { input ->
                outFile.outputStream().use { out ->
                    copyWithProgress(input, out)
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                MediaScannerConnection.scanFile(this@MainActivity, arrayOf(outFile.absolutePath), arrayOf(safeFormat.mimeType)) { p, uri ->
                    Log.d(logTag, "Scanned output: $p -> $uri")
                }
            }
            return@withContext outFile.absolutePath
        }

        val privateBase = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IllegalStateException("No app output directory available")
        val privateDir = File(privateBase, "CompilationMaker").apply { mkdirs() }
        val privateOut = File(privateDir, "$filename.${safeFormat.extension}")
        privateOut.outputStream().use { out ->
            file.inputStream().use { input ->
                copyWithProgress(input, out)
            }
        }
        return@withContext privateOut.absolutePath
    }

    private fun setUiBusy(busy: Boolean) {
        runOnUiThread {
            binding.processButton.isEnabled = !busy
            binding.selectButton.isEnabled = !busy
            checkUpdatesButton.isEnabled = !busy
            saveCompilationButton.isEnabled = !busy && pendingCompilationFile != null
            discardCompilationButton.isEnabled = !busy && pendingCompilationFile != null
            playCompilationPreviewButton.isEnabled = !busy && pendingCompilationFile != null
            binding.progressBar.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.INVISIBLE
            progressPercentText.visibility = View.VISIBLE
            statusFeedScroll.visibility = View.VISIBLE
        }
    }

    private fun emitProgress(message: String, percent: Int) {
        val percentValue = percent.coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()
        if (percentValue == lastProgressPercent && message == lastProgressText && now - lastProgressUpdateMs < 250L) return
        lastProgressText = message
        lastProgressPercent = percentValue
        lastProgressUpdateMs = now
        Log.d(logTag, "Progress $percentValue%: $message")
        runOnUiThread {
            binding.statusText.text = message
            progressPercentText.text = "$percentValue%"
            binding.progressBar.progress = percentValue
            addStatusLine(message)
        }
        updateProgressNotification(message, percentValue, percentValue < 100)
    }

    private fun ensureProgressNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationChannelReady) return
        val channel = NotificationChannel(
            progressNotificationChannelId,
            "Compilation progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows export progress while compilations are running"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        notificationChannelReady = true
    }

    private fun updateProgressNotification(message: String, percent: Int, ongoing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureProgressNotificationChannel()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, progressNotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(
                when {
                    message.startsWith("Error:", ignoreCase = true) -> "Compilation failed"
                    ongoing -> "Compilation in progress"
                    else -> "Compilation complete"
                }
            )
            .setContentText(message.take(90))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(progressNotificationId, notification)
        } catch (e: SecurityException) {
            Log.w(logTag, "Notification permission unavailable", e)
        }
    }

    private fun clearProgressNotification() {
        NotificationManagerCompat.from(this).cancel(progressNotificationId)
    }

    private fun formatDurationMs(totalMs: Long): String {
        val totalSec = max(0L, totalMs) / 1000
        val sec = totalSec % 60
        val min = totalSec / 60
        val ms = max(0L, totalMs) % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", min, sec, ms)
    }

    private fun formatDurationUs(totalUs: Long): String {
        return formatDurationMs(totalUs / 1000L)
    }

    private fun formatBytes(totalBytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = totalBytes.toDouble()
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) {
            value /= 1024.0
            idx++
        }
        return if (idx == 0) {
            String.format(Locale.US, "%d%s", totalBytes, units[idx])
        } else {
            String.format(Locale.US, "%.2f%s", value, units[idx])
        }
    }

    private fun clearStatusFeed(initialMessage: String) {
        statusFeedLines.clear()
        addStatusLine(initialMessage)
        binding.statusText.text = initialMessage
        progressPercentText.text = "0%"
        binding.progressBar.progress = 0
    }

    private fun selectedScanProfile(): ScanProfile {
        val index = scanSpeedSpinner.selectedItemPosition.coerceIn(0, scanProfiles.lastIndex)
        return scanProfiles[index]
    }

    private fun readScanWindow(): ScanWindow {
        val rawX = parsePercent(scanAreaX.text?.toString(), defaultScanWindow.xPercent)
        val rawY = parsePercent(scanAreaY.text?.toString(), defaultScanWindow.yPercent)
        val rawW = parsePercent(scanAreaWidth.text?.toString(), defaultScanWindow.widthPercent)
        val rawH = parsePercent(scanAreaHeight.text?.toString(), defaultScanWindow.heightPercent)

        val x = rawX.coerceIn(0f, 1f)
        val y = rawY.coerceIn(0f, 1f)
        val maxWidth = max(0.01f, 1f - x)
        val maxHeight = max(0.01f, 1f - y)
        val widthPercent = rawW.coerceIn(0.01f, maxWidth)
        val heightPercent = rawH.coerceIn(0.01f, maxHeight)

        return ScanWindow(x, y, widthPercent, heightPercent)
    }

    private fun parsePercent(raw: String?, fallback: Float): Float {
        if (raw.isNullOrBlank()) return fallback
        val value = raw.trim().toFloatOrNull() ?: return fallback
        return if (value > 1f) value / 100f else value
    }

    private fun addStatusLine(message: String) {
        val ts = DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        statusFeedLines.addLast("[$ts] $message")
        while (statusFeedLines.size > 12) {
            statusFeedLines.removeFirst()
        }
        statusFeedText.text = statusFeedLines.joinToString("\n")
        statusFeedScroll.post { statusFeedScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun roiStateKey(uri: Uri): String {
        return "roi:${Base64.encodeToString(uri.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)}"
    }

    private fun persistCurrentRoi() {
        val uri = selectedVideoUri ?: return
        val window = readScanWindow()
        roiPrefs.edit().putString(
            roiStateKey(uri),
            JSONObject().apply {
                put("uri", uri.toString())
                put("rotation", selectedVideoRotationDegrees)
                put("videoWidth", selectedVideoWidth)
                put("videoHeight", selectedVideoHeight)
                put("xPercent", window.xPercent)
                put("yPercent", window.yPercent)
                put("widthPercent", window.widthPercent)
                put("heightPercent", window.heightPercent)
                put("updatedAtMs", System.currentTimeMillis())
            }.toString()
        ).apply()
    }

    private fun restoreRoiState(uri: Uri) {
        val payload = roiPrefs.getString(roiStateKey(uri), null) ?: return
        try {
            val json = JSONObject(payload)
            val savedWindow = ScanWindow(
                (json.getDouble("xPercent")).toFloat(),
                (json.getDouble("yPercent")).toFloat(),
                (json.getDouble("widthPercent")).toFloat(),
                (json.getDouble("heightPercent")).toFloat()
            )
            val savedRotation = json.optInt("rotation")
            val restoredWindow = normalizeRoiWindowForCurrentRotation(savedWindow, savedRotation)
            setScanAreaFromPercents(
                restoredWindow.xPercent,
                restoredWindow.yPercent,
                restoredWindow.widthPercent,
                restoredWindow.heightPercent
            )
            emitProgress(
                "Loaded saved ROI for video (storedRotation=${savedRotation}°, currentRotation=${selectedVideoRotationDegrees}°)",
                1
            )
        } catch (e: Exception) {
            Log.w(logTag, "Failed to restore ROI metadata", e)
            emitProgress("Saved ROI metadata was unavailable. Using defaults.", 1)
        }
    }

    private fun normalizeRoiWindowForCurrentRotation(saved: ScanWindow, savedRotation: Int): ScanWindow {
        val from = ((savedRotation % 360) + 360) % 360
        val to = ((selectedVideoRotationDegrees % 360) + 360) % 360
        return when ((to - from + 360) % 360) {
            90 -> ScanWindow(
                saved.yPercent,
                1f - saved.xPercent - saved.widthPercent,
                saved.heightPercent,
                saved.widthPercent
            )
            180 -> ScanWindow(
                1f - saved.xPercent - saved.widthPercent,
                1f - saved.yPercent - saved.heightPercent,
                saved.widthPercent,
                saved.heightPercent
            )
            270 -> ScanWindow(
                1f - saved.yPercent - saved.heightPercent,
                saved.xPercent,
                saved.heightPercent,
                saved.widthPercent
            )
            else -> saved
        }.coerceInBounds()
    }

    private fun ScanWindow.coerceInBounds(): ScanWindow {
        val x = xPercent.coerceIn(0f, 1f)
        val y = yPercent.coerceIn(0f, 1f)
        val w = widthPercent.coerceIn(0.01f, max(0.01f, 1f - x))
        val h = heightPercent.coerceIn(0.01f, max(0.01f, 1f - y))
        return ScanWindow(x, y, w, h)
    }

    private fun loadSelectedVideoMetadata(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            selectedVideoRotationDegrees = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0).let {
                ((it % 360) + 360) % 360
            }
            selectedVideoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            selectedVideoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.w(logTag, "Could not read video metadata", e)
            selectedVideoRotationDegrees = 0
            selectedVideoWidth = 0
            selectedVideoHeight = 0
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

    private fun checkForUpdates(force: Boolean = false) {
        if (updateNotificationChannelId.isBlank()) {
            return
        }
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val lastCheckMs = updatePrefs.getLong("last_update_check_ms", 0L)
            if (!force && now - lastCheckMs < updateCooldownMs) {
                return@launch
            }

            updatePrefs.edit().putLong("last_update_check_ms", now).apply()

            val info = withContext(Dispatchers.IO) { fetchUpdateInfo() }
            if (info == null) {
                if (force) {
                    runOnUiThread {
                        emitProgress("No update information found. Check connection and manifest URL.", 100)
                    }
                }
                return@launch
            }
            val latest = info.versionName
            val current = BuildConfig.VERSION_NAME.ifBlank { "0.0.0" }
            if (!isRemoteVersionNewer(latest, current)) {
                if (force) {
                    runOnUiThread {
                        emitProgress("You're on the latest version (${current}).", 100)
                    }
                }
                return@launch
            }

            val notifiedVersion = updatePrefs.getString("notified_version", "")
            if (!force && notifiedVersion == latest) {
                return@launch
            }
            updatePrefs.edit().putString("notified_version", latest).apply()
            if (force) {
                showUpdateAvailableDialog(info)
            } else {
                notifyUserOfUpdate(info)
                runOnUiThread {
                    emitProgress("Update ${info.versionName} available. Tap Check for updates to install.", 100)
                }
            }
        }
    }

    private fun notifyUserOfUpdate(info: UpdateInfo) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return
        }

        val updateIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        updateIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val updatePendingIntent = PendingIntent.getActivity(
            this,
            0,
            updateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, updateNotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("CompilationMaker update available")
            .setContentText("New version ${info.versionName} is available")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "New version ${info.versionName} is available. Open the app and tap Check for updates to install."
                            .plus(if (info.releaseNotes.isNotBlank()) "\n\n${info.releaseNotes}" else "")
                    )
            )
            .setContentIntent(updatePendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify(updateNotificationId, notification)
    }

    private fun showUpdateAvailableDialog(info: UpdateInfo) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Update ${info.versionName} available")
                .setMessage(
                    if (info.releaseNotes.isNotBlank()) {
                        info.releaseNotes
                    } else {
                        "A newer version is ready to install."
                    }
                )
                .setPositiveButton("Install") { _, _ ->
                    downloadAndInstallUpdate(info)
                }
                .setNegativeButton("Later", null)
                .show()
            emitProgress("Update ${info.versionName} available.", 100)
        }
    }

    private fun downloadAndInstallUpdate(info: UpdateInfo) {
        if (info.downloadUrl.isBlank()) {
            emitProgress("Update APK link missing from manifest.", 100)
            return
        }
        lifecycleScope.launch {
            emitProgress("Downloading update ${info.versionName} in the background...", 100)
            val apk = withContext(Dispatchers.IO) { downloadUpdateApk(info) }
            if (apk == null) {
                emitProgress("Update download failed.", 100)
                return@launch
            }
            emitProgress("Update downloaded. Opening Android installer.", 100)
            installDownloadedApk(apk)
        }
    }

    private fun downloadUpdateApk(info: UpdateInfo): File? {
        val targetDir = File(cacheDir, "updates").apply { mkdirs() }
        targetDir.listFiles()
            ?.filter { it.extension.equals("apk", ignoreCase = true) }
            ?.forEach { it.delete() }
        val safeVersion = info.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(targetDir, "CompilationMaker-$safeVersion.apk")
        val connection = try {
            java.net.URL(info.downloadUrl).openConnection() as java.net.HttpURLConnection
        } catch (e: Exception) {
            Log.w(logTag, "Unable to open update download connection", e)
            return null
        }

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                Log.w(logTag, "Update download failed with HTTP ${connection.responseCode}")
                return null
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (target.length() > 0L) target else null
        } catch (e: Exception) {
            Log.w(logTag, "Update download failed", e)
            target.delete()
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun installDownloadedApk(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            showInstallPermissionDialog()
            return
        }

        val apkUri = FileProvider.getUriForFile(
            this,
            "$packageName.updateprovider",
            apk
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .forEach { resolveInfo ->
                grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    apkUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        try {
            startActivity(installIntent)
        } catch (e: Exception) {
            Log.w(logTag, "Unable to open Android package installer", e)
            emitProgress("Could not open Android installer for downloaded update.", 100)
        }
    }

    private fun showInstallPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Allow app updates")
            .setMessage("Android needs permission to install updates downloaded by CompilationMaker. Enable Allow from this source, then tap Check for updates again.")
            .setPositiveButton("Open settings") { _, _ ->
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                startActivity(settingsIntent)
            }
            .setNegativeButton("Cancel", null)
            .show()
        emitProgress("Enable install permission, then check for updates again.", 100)
    }

    private fun ensureUpdateNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                updateNotificationChannelId,
                "Compilation Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Notifies when a newer app version is available"
            nm.createNotificationChannel(channel)
        }
    }

    private fun fetchUpdateInfo(): UpdateInfo? {
        fetchManifestUpdateInfo()?.let { return it }
        return try {
            val response = fetchUrlText(fallbackReleaseEndpoint) ?: return null
            val json = JSONObject(response)
            val version = json.optString("tag_name", "")
                .ifBlank { json.optString("name", "") }
                .ifBlank { "0.0.0" }
            val releaseUrl = json.optString("html_url", "")
            val assets = json.optJSONArray("assets")
            val download = if (assets != null && assets.length() > 0) {
                (assets.get(0) as? JSONObject)?.optString("browser_download_url", "") ?: ""
            } else {
                ""
            }
            UpdateInfo(
                version,
                releaseUrl,
                download,
                "Release from GitHub API"
            )
        } catch (_: Exception) {
            runOnUiThread {
                emitProgress("Update check failed. Verify manifest and/or release endpoint settings.", 100)
            }
            null
        }
    }

    private fun fetchManifestUpdateInfo(): UpdateInfo? {
        for (endpoint in listOf(updateManifestRawEndpoint, updateManifestBlobEndpoint)) {
            try {
                val response = fetchUrlText(endpoint) ?: continue
                val manifest = parseUpdateManifest(response) ?: continue
                val version = manifest.optString("version", "")
                    .ifBlank { manifest.optString("tag", "") }
                    .ifBlank { manifest.optString("name", "") }
                    .ifBlank { "0.0.0" }
                val apkUrl = (manifest.optJSONObject("apk")?.optString("url", "") ?: "")
                    .ifBlank { manifest.optString("apkUrl", "") }
                val releaseUrl = manifest.optString("releaseUrl", "")
                    .ifBlank { fallbackReleaseEndpoint }
                val notes = manifest.optString("notes", "").ifBlank { manifest.optString("changelog", "") }
                return UpdateInfo(
                    version,
                    releaseUrl,
                    apkUrl,
                    notes
                )
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun parseUpdateManifest(response: String): JSONObject? {
        val top = JSONObject(response)
        val encoding = top.optString("encoding", "")
        val raw = top.optString("content", "")
        if (encoding == "base64" && raw.isNotBlank()) {
            val manifestContent = String(Base64.decode(raw.replace("\\s".toRegex(), ""), Base64.DEFAULT))
            return JSONObject(manifestContent)
        }
        return top
    }

    private fun isRemoteVersionNewer(remote: String, current: String): Boolean {
        val remoteNormalized = remote.trim().trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        val currentNormalized = current.trim().trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        val maxParts = max(remoteNormalized.size, currentNormalized.size)
        for (i in 0 until maxParts) {
            val remotePart = remoteNormalized.getOrElse(i) { 0 }
            val currentPart = currentNormalized.getOrElse(i) { 0 }
            when {
                remotePart > currentPart -> return true
                remotePart < currentPart -> return false
            }
        }
        return false
    }

    private fun fetchUrlText(url: String): String? {
        val connection = try {
            java.net.URL(url).openConnection() as java.net.HttpURLConnection
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                if (connection.responseCode == 404 && url.contains("/contents/")) {
                    runOnUiThread {
                        emitProgress("Update manifest not found at app-update.json in your GitHub repo.", 100)
                    }
                } else if (connection.responseCode == 404 && url.contains("/releases/")) {
                    runOnUiThread {
                        emitProgress("Release endpoint not found. Ensure releases are enabled for your repo.", 100)
                    }
                }
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

private class VideoCompilationEngine(private val context: MainActivity) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val tag = "CompilationEngine"
    var latestScanReportPath: String? = null
        private set

    suspend fun findNumberTransitionSegments(
        sourceUri: Uri,
        frameStepMs: Long,
        scanMode: ScanMode,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        scanProfileLabel: String,
        transitionStyle: TransitionStyle,
        progress: (String, Int) -> Unit
    ): ScanFindResult = withContext(Dispatchers.IO) {
        Log.d(tag, "Starting scan for number transitions: $sourceUri")
        val retriever = android.media.MediaMetadataRetriever().apply {
            setDataSource(context, sourceUri)
        }
            val timing = ScanTimingSummary()
            val sourceWidthPx = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val sourceHeightPx = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

        try {
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("Could not read duration")
            if (durationMs <= 0L) return@withContext ScanFindResult(emptyList(), timing)

            val transitions = mutableListOf<Long>()
            val checkpointMs = frameStepMs.coerceAtLeast(1L)
            var previousNumber: Int? = null
            var hasCapturedFirstOne = false
            val fastModeScaleWidthPx = when {
                scanMode != ScanMode.Fast -> 0
                frameStepMs <= 750L -> 240
                else -> 300
            }
            var previousSignature: RoiSignature? = null
            var framesSinceRecognition = 0
            var cursorMs = 0L
            var checkpointCount = 0
            while (cursorMs <= durationMs) {
                if (!coroutineContext.isActive) {
                    return@withContext ScanFindResult(emptyList(), timing)
                }
                val percent = ((cursorMs.toFloat() / durationMs.toFloat()) * 100f).toInt().coerceIn(0, 100)
                val elapsedSeconds = String.format(Locale.US, "%.1f", cursorMs / 1000f)
                val totalSeconds = String.format(Locale.US, "%.1f", durationMs / 1000f)
                if (scanMode == ScanMode.Checkpoints3Min) {
                    progress("Checkpoint $checkpointCount at ${elapsedSeconds}s (${percent}%, interval ${frameStepMs / 1000}s)", percent)
                } else {
                    progress("Scanning timeline ${elapsedSeconds}s / ${totalSeconds}s (${percent}%, step ${frameStepMs}ms)", percent)
                }

                var frame: Bitmap?
                val decodeMs = measureTimeMillis {
                    frame = getAnalysisFrameAtTime(
                        retriever,
                        cursorMs,
                        fastModeScaleWidthPx,
                        sourceRotationDegrees,
                        sourceWidthPx,
                        sourceHeightPx
                    )
                }
                timing.decodeMs += decodeMs
                if (frame == null) {
                    cursorMs = when (scanMode) {
                        ScanMode.Fast -> cursorMs + frameStepMs
                        ScanMode.Checkpoints3Min -> {
                            if (frameStepMs <= 0L) break
                            (cursorMs / checkpointMs + 1) * checkpointMs
                        }
                    }
                    checkpointCount++
                    continue
                }

                var didDetectChange = false
                val frameBitmap = frame
                if (frameBitmap == null) {
                    cursorMs = when (scanMode) {
                        ScanMode.Fast -> cursorMs + frameStepMs
                        ScanMode.Checkpoints3Min -> {
                            if (frameStepMs <= 0L) break
                            (cursorMs / checkpointMs + 1) * checkpointMs
                        }
                    }
                    checkpointCount++
                    continue
                }
                try {
                    val signature = measureTimeMillisWithResult {
                        computeRoiSignature(frameBitmap, scanWindow, sourceRotationDegrees)
                    }
                    timing.preprocessMs += signature.elapsedMs
                    val shouldRecognize = when (scanMode) {
                        ScanMode.Checkpoints3Min -> true
                        ScanMode.Fast -> {
                            val changed = previousSignature?.let { roiDifference(it, signature.value) >= 7.5f } ?: true
                            changed || framesSinceRecognition >= max(1, 5_000L / frameStepMs).toInt()
                        }
                    }
                    val detected = if (shouldRecognize) {
                        framesSinceRecognition = 0
                        var ocrFrame: Bitmap? = null
                        var mustRecycleOcrFrame = false
                        try {
                            if (fastModeScaleWidthPx > 0) {
                                ocrFrame = getAnalysisFrameAtTime(
                                    retriever,
                                    cursorMs,
                                    0,
                                    sourceRotationDegrees,
                                    sourceWidthPx,
                                    sourceHeightPx
                                )
                                if (ocrFrame != null) {
                                    mustRecycleOcrFrame = true
                                }
                            }
                            val detection = detectCornerNumberWithTimings(ocrFrame ?: frameBitmap, scanWindow, sourceRotationDegrees)
                            timing.cropMs += detection.cropMs
                            timing.preprocessMs += detection.preprocessMs
                            timing.ocrMs += detection.ocrMs
                            detection.value
                        } catch (e: Exception) {
                            Log.w(tag, "OCR failed at ${cursorMs}ms", e)
                            null
                        } finally {
                            if (mustRecycleOcrFrame) {
                                ocrFrame?.recycle()
                            }
                        }
                    } else {
                        framesSinceRecognition++
                        previousNumber
                    }
                    previousSignature = signature.value

                    var nextNumber = detected
                    var transitionMs: Long? = null
                    var transitionLabel = "Transition"
                    if (detected != null && !hasCapturedFirstOne) {
                        val firstOneSearchStartMs = when (scanMode) {
                            ScanMode.Checkpoints3Min -> max(0L, cursorMs - checkpointMs)
                            ScanMode.Fast -> max(0L, cursorMs - max(frameStepMs, 5_000L))
                        }
                        val firstOneMs = locateFirstNumberAppearance(
                            retriever,
                            firstOneSearchStartMs,
                            cursorMs,
                            scanWindow,
                            sourceRotationDegrees,
                            targetNumber = 1,
                            stepMs = if (scanMode == ScanMode.Checkpoints3Min) 1_000L else frameStepMs.coerceIn(250L, 1_000L),
                            timing
                        )
                        if (firstOneMs != null) {
                            transitionMs = firstOneMs
                            transitionLabel = "First 1"
                            hasCapturedFirstOne = true
                            didDetectChange = true
                        }
                    }
                    if (transitionMs == null) {
                        didDetectChange = detected != null && previousNumber != null && detected != previousNumber
                        if (didDetectChange) {
                            transitionMs = if (scanMode == ScanMode.Checkpoints3Min) {
                                refineTransitionNearHit(
                                    retriever,
                                    max(0L, cursorMs - min(checkpointMs, 30_000L)),
                                    cursorMs,
                                    scanWindow,
                                    sourceRotationDegrees,
                                    previousNumber!!,
                                    timing
                                )
                            } else {
                                cursorMs
                            }
                        }
                    }
                    if (transitionMs != null) {
                        val transitionAtMs = transitionMs
                        transitions.add(transitionAtMs)
                        val cutStartMs = max(0L, transitionAtMs - 10_000L)
                        val cutEndMs = min(durationMs, transitionAtMs + 30_000L)
                        nextNumber = detected
                        progress(
                            "$transitionLabel at ${formatMs(transitionAtMs)}; cut ${formatMs(cutStartMs)} -> ${formatMs(cutEndMs)}",
                            min(100, ((transitionAtMs.toFloat() / durationMs.toFloat()) * 100f + 1).toInt())
                        )
                    }
                    if (nextNumber != null) {
                        previousNumber = nextNumber
                    }
                } finally {
                    frameBitmap.recycle()
                }

                cursorMs = when (scanMode) {
                    ScanMode.Fast -> cursorMs + frameStepMs
                    ScanMode.Checkpoints3Min -> {
                        if (didDetectChange) {
                            val afterMarkMs = cursorMs + 30_000L
                            val nextAlignedCheckpoint = ((cursorMs / checkpointMs) + 1) * checkpointMs
                            if (nextAlignedCheckpoint > afterMarkMs) {
                                nextAlignedCheckpoint
                            } else {
                                afterMarkMs
                            }
                        } else {
                            if (frameStepMs <= 0L) break
                            (cursorMs / checkpointMs + 1) * checkpointMs
                        }
                    }
                }
                checkpointCount++
            }

            Log.d(tag, "Detected transitions: ${transitions.size}")
            val artifactPadMs = transitionStyle.edgePaddingMs
            val rawSegments = transitions.map { t ->
                SegmentWindow(
                    startMs = max(0L, t - 10_000L - artifactPadMs),
                    endMs = min(durationMs, t + 30_000L + artifactPadMs)
                )
            }.sortedBy { it.startMs }
            val mergedTiming = measureTimeMillis {
                mergeWithGap(rawSegments, transitionStyle.mergeGapMs)
            }
            val merged = mergeWithGap(rawSegments, transitionStyle.mergeGapMs)
            timing.mergeMs = mergedTiming
            val report = ScanFindReport(
                sourceUri = sourceUri.toString(),
                profileLabel = scanProfileLabel,
                scanWindow = scanWindow,
                frameStepMs = frameStepMs,
                mode = scanMode.name,
                durationMs = durationMs,
                transitionsFound = merged.size,
                timing = timing
            )
            latestScanReportPath = writeScanReport(report)
            return@withContext ScanFindResult(merged, timing)
        } finally {
            recognizer.close()
            retriever.release()
        }
    }

    private suspend fun locateFirstNumberAppearance(
        retriever: MediaMetadataRetriever,
        startMs: Long,
        hitMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        targetNumber: Int,
        stepMs: Long,
        timing: ScanTimingSummary
    ): Long? {
        val safeStartMs = startMs.coerceAtLeast(0L)
        val safeHitMs = hitMs.coerceAtLeast(safeStartMs)
        val safeStepMs = stepMs.coerceAtLeast(250L)
        var cursorMs = safeStartMs
        var lastNotTargetMs = safeStartMs

        while (cursorMs <= safeHitMs) {
            val detected = detectNumberAt(retriever, cursorMs, scanWindow, sourceRotationDegrees, timing)
            if (detected == targetNumber) {
                return refineNumberAppearanceBetween(
                    retriever,
                    lastNotTargetMs,
                    cursorMs,
                    scanWindow,
                    sourceRotationDegrees,
                    targetNumber,
                    timing
                )
            }
            lastNotTargetMs = cursorMs
            val nextCursorMs = min(safeHitMs, cursorMs + safeStepMs)
            if (nextCursorMs == cursorMs) break
            cursorMs = nextCursorMs
        }
        return null
    }

    private suspend fun refineNumberAppearanceBetween(
        retriever: MediaMetadataRetriever,
        lowerBoundMs: Long,
        upperBoundMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        targetNumber: Int,
        timing: ScanTimingSummary
    ): Long {
        var lowerMs = lowerBoundMs
        var upperMs = upperBoundMs

        repeat(8) {
            if (upperMs - lowerMs <= 250L) return@repeat
            val midMs = lowerMs + (upperMs - lowerMs) / 2L
            val detected = detectNumberAt(retriever, midMs, scanWindow, sourceRotationDegrees, timing)
            if (detected == targetNumber) {
                upperMs = midMs
            } else {
                lowerMs = midMs
            }
        }
        return upperMs
    }

    private suspend fun refineTransitionNearHit(
        retriever: MediaMetadataRetriever,
        startMs: Long,
        hitMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        previousNumber: Int,
        timing: ScanTimingSummary
    ): Long {
        var lowerMs = startMs
        var upperMs = hitMs
        var probeMs = max(startMs, hitMs - 5_000L)

        while (probeMs > startMs) {
            val detected = detectNumberAt(retriever, probeMs, scanWindow, sourceRotationDegrees, timing)
            if (detected == null || detected == previousNumber) {
                lowerMs = probeMs
                break
            }
            upperMs = probeMs
            probeMs = max(startMs, probeMs - 5_000L)
        }

        repeat(7) {
            if (upperMs - lowerMs <= 250L) return@repeat
            val midMs = lowerMs + (upperMs - lowerMs) / 2L
            val detected = detectNumberAt(retriever, midMs, scanWindow, sourceRotationDegrees, timing)
            if (detected != null && detected != previousNumber) {
                upperMs = midMs
            } else {
                lowerMs = midMs
            }
        }
        return upperMs
    }

    private suspend fun detectNumberAt(
        retriever: MediaMetadataRetriever,
        cursorMs: Long,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int,
        timing: ScanTimingSummary
    ): Int? {
        var frame: Bitmap?
        val decodeMs = measureTimeMillis {
        frame = getAnalysisFrameAtTime(retriever, cursorMs)
        }
        timing.decodeMs += decodeMs
        val localFrame = frame ?: return null
        return try {
            val detection = detectCornerNumberWithTimings(localFrame, scanWindow, sourceRotationDegrees)
            timing.cropMs += detection.cropMs
            timing.preprocessMs += detection.preprocessMs
            timing.ocrMs += detection.ocrMs
            detection.value
        } finally {
            localFrame.recycle()
        }
    }

    suspend fun getDurationMs(sourceUri: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = android.media.MediaMetadataRetriever().apply {
            setDataSource(context, sourceUri)
        }
        return@withContext try {
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    suspend fun renderCompilation(
        sourceUri: Uri,
        segments: List<SegmentWindow>,
        quality: ExportQuality,
        format: ExportFormat,
        transitionStyle: TransitionStyle,
        progress: (String, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val cache = context.cacheDir
        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format

        if (segments.isEmpty()) throw IllegalStateException("No segments to assemble")
        val output = File(cache, "compilation_${System.currentTimeMillis()}.${safeFormat.extension}")
        progress("Using ${quality.label} profile with ${transitionStyle.label}", 56)
        progress("Opening source for direct export", 57)
        val alignedSegments = alignSegmentsToVideoSyncSamples(sourceUri, mergeWithGap(segments.sortedBy { it.startMs }, transitionStyle.mergeGapMs))
        materializeCompilation(sourceUri, alignedSegments, safeFormat, output) { message, percent ->
            progress(message, percent)
        }
        progress("Compilation ready to save", 94)
        return@withContext output
    }

    private suspend fun detectCornerNumber(frame: Bitmap, scanWindow: ScanWindow): Int? {
        val corner = try {
            if (frame.width <= 0 || frame.height <= 0) {
                return null
            }
            cropToWindow(frame, scanWindow)
        } catch (e: Exception) {
            Log.w(tag, "Failed to crop scan area", e)
            null
        } ?: return null

        val scaled = scaleBitmapForOcr(corner)
        return try {
            val image = InputImage.fromBitmap(scaled, 0)
            val result = recognizer.process(image).await()
            val text = result.text.replace("\n", " ")
            val match = Regex("[-+]?[0-9]+").find(text)
            match?.value?.toIntOrNull()
        } finally {
            if (scaled != corner) {
                scaled.recycle()
            }
            corner.recycle()
        }
    }

    private suspend fun detectCornerNumberWithTimings(
        frame: Bitmap,
        scanWindow: ScanWindow,
        sourceRotationDegrees: Int
    ): NumberDetectionResult {
        val normalizedFrame = if (sourceRotationDegrees == 0) frame else normalizeBitmapForOcr(frame, sourceRotationDegrees)
        val startedCropMs = System.currentTimeMillis()
        val corner = try {
            if (normalizedFrame.width <= 0 || normalizedFrame.height <= 0) return NumberDetectionResult(null, 0L, 0L, 0L)
            cropToWindow(normalizedFrame, scanWindow)
        } catch (e: Exception) {
            Log.w(tag, "Failed to crop scan area", e)
            if (normalizedFrame !== frame) {
                normalizedFrame.recycle()
            }
            return NumberDetectionResult(null, 0L, 0L, 0L)
        } ?: return NumberDetectionResult(null, 0L, 0L, 0L)
        val cropMs = System.currentTimeMillis() - startedCropMs

        val preprocessStartMs = System.currentTimeMillis()
        val scaled = scaleBitmapForOcr(corner)
        val preprocessMs = System.currentTimeMillis() - preprocessStartMs

        return try {
            val image = InputImage.fromBitmap(scaled, 0)
            val ocrStart = System.currentTimeMillis()
            val result = recognizer.process(image).await()
            val ocrMs = System.currentTimeMillis() - ocrStart
            val text = result.text.replace("\n", " ")
            val match = Regex("[-+]?[0-9]+").find(text)
            NumberDetectionResult(match?.value?.toIntOrNull(), cropMs, preprocessMs, ocrMs)
        } finally {
            if (scaled != corner) {
                scaled.recycle()
            }
            corner.recycle()
            if (normalizedFrame !== frame) {
                normalizedFrame.recycle()
            }
        }
    }

    private fun normalizeBitmapForOcr(source: Bitmap, rotationDegrees: Int): Bitmap {
        return when (((rotationDegrees % 360) + 360) % 360) {
            0 -> source
            else -> {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }
        }
    }

    private fun getAnalysisFrameAtTime(
        retriever: MediaMetadataRetriever,
        cursorMs: Long,
        targetWidthPx: Int = 0,
        sourceRotationDegrees: Int = 0,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0
    ): Bitmap? {
        val timeUs = cursorMs * 1000L
        val decodeWidth = targetWidthPx.coerceAtLeast(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && decodeWidth > 0) {
            val decodeHeight = scaledHeightForWidth(decodeWidth, sourceRotationDegrees, sourceWidth, sourceHeight)
            if (decodeHeight > 0) {
                return retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    decodeWidth,
                    decodeHeight
                ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                640,
                360
            ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }

    private fun computeRoiSignature(frame: Bitmap, scanWindow: ScanWindow, sourceRotationDegrees: Int): RoiSignature {
        val normalizedFrame = if (sourceRotationDegrees == 0) frame else normalizeBitmapForOcr(frame, sourceRotationDegrees)
        return try {
            val startX = (normalizedFrame.width * scanWindow.xPercent).roundToInt().coerceIn(0, normalizedFrame.width - 1)
            val startY = (normalizedFrame.height * scanWindow.yPercent).roundToInt().coerceIn(0, normalizedFrame.height - 1)
            val roiWidth = (normalizedFrame.width * scanWindow.widthPercent).roundToInt().coerceIn(1, normalizedFrame.width - startX)
            val roiHeight = (normalizedFrame.height * scanWindow.heightPercent).roundToInt().coerceIn(1, normalizedFrame.height - startY)
            val grid = IntArray(64)
            var total = 0
            var index = 0
            for (gy in 0 until 8) {
                val y = (startY + (gy + 0.5f) * roiHeight / 8f).roundToInt().coerceIn(0, normalizedFrame.height - 1)
                for (gx in 0 until 8) {
                    val x = (startX + (gx + 0.5f) * roiWidth / 8f).roundToInt().coerceIn(0, normalizedFrame.width - 1)
                    val pixel = normalizedFrame.getPixel(x, y)
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    val luma = (r * 30 + g * 59 + b * 11) / 100
                    grid[index++] = luma
                    total += luma
                }
            }
            RoiSignature(grid, total / grid.size)
        } finally {
            if (normalizedFrame !== frame) {
                normalizedFrame.recycle()
            }
        }
    }

    private fun scaledHeightForWidth(
        targetWidthPx: Int,
        sourceRotationDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): Int {
        val width = targetWidthPx.coerceAtLeast(1)
        val sourceW = if (sourceRotationDegrees == 90 || sourceRotationDegrees == 270) sourceHeight else sourceWidth
        val sourceH = if (sourceRotationDegrees == 90 || sourceRotationDegrees == 270) sourceWidth else sourceHeight
        if (sourceW <= 0 || sourceH <= 0) return 0
        if (width >= sourceW) return 0
        val scale = width.toFloat() / sourceW.toFloat()
        return (sourceH.toFloat() * scale).toInt().coerceAtLeast(1)
    }

    private fun roiDifference(previous: RoiSignature, current: RoiSignature): Float {
        var total = kotlin.math.abs(previous.average - current.average).toFloat()
        for (i in previous.samples.indices) {
            total += kotlin.math.abs(previous.samples[i] - current.samples[i])
        }
        return total / (previous.samples.size + 1).toFloat()
    }

    private inline fun <T> measureTimeMillisWithResult(block: () -> T): TimedResult<T> {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        return TimedResult(result, SystemClock.elapsedRealtime() - start)
    }

    private fun writeScanReport(report: ScanFindReport): String {
        val reportDir = File(context.filesDir, "scan_reports")
        if (!reportDir.exists()) {
            reportDir.mkdirs()
        }
        val output = File(reportDir, "scan-${System.currentTimeMillis()}.json")
        val payload = JSONObject().apply {
            put("sourceUri", report.sourceUri)
            put("profileLabel", report.profileLabel)
            put("frameStepMs", report.frameStepMs)
            put("mode", report.mode)
            put("durationMs", report.durationMs)
            put("transitionsFound", report.transitionsFound)
            put("scanWindow", JSONObject().apply {
                put("xPercent", report.scanWindow.xPercent)
                put("yPercent", report.scanWindow.yPercent)
                put("widthPercent", report.scanWindow.widthPercent)
                put("heightPercent", report.scanWindow.heightPercent)
            })
            put("timing", JSONObject().apply {
                put("decodeMs", report.timing.decodeMs)
                put("cropMs", report.timing.cropMs)
                put("preprocessMs", report.timing.preprocessMs)
                put("ocrMs", report.timing.ocrMs)
                put("mergeMs", report.timing.mergeMs)
            })
            put("savedAtMs", System.currentTimeMillis())
        }
        output.writeText(payload.toString(2))
        return output.absolutePath
    }

    private fun cropToWindow(frame: Bitmap, scanWindow: ScanWindow): Bitmap {
        val x = (frame.width * scanWindow.xPercent).roundToInt().coerceIn(0, frame.width - 1)
        val y = (frame.height * scanWindow.yPercent).roundToInt().coerceIn(0, frame.height - 1)
        val width = (frame.width * scanWindow.widthPercent).roundToInt().coerceIn(1, frame.width - x)
        val height = (frame.height * scanWindow.heightPercent).roundToInt().coerceIn(1, frame.height - y)
        return Bitmap.createBitmap(frame, x, y, width, height)
    }

    private fun scaleBitmapForOcr(input: Bitmap): Bitmap {
        val maxSide = 320
        if (input.width <= maxSide && input.height <= maxSide) return input
        val scale = min(maxSide.toFloat() / input.width, maxSide.toFloat() / input.height)
        val targetW = max(1, (input.width * scale).roundToInt())
        val targetH = max(1, (input.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(input, targetW, targetH, true)
    }

    private fun materializeCompilation(
        sourceUri: Uri,
        segments: List<SegmentWindow>,
        format: ExportFormat,
        output: File,
        progress: (String, Int) -> Unit
    ) {
        if (segments.isEmpty()) {
            throw IllegalStateException("No segments to assemble")
        }

        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format
        val muxerFormat = when (safeFormat) {
            ExportFormat.Mp4, ExportFormat.Mov -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            ExportFormat.Webm -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(context, sourceUri, null)
        val trackMap = mutableMapOf<Int, Int>()

        val muxer = MediaMuxer(output.absolutePath, muxerFormat)
        val trackMimeTypes = mutableMapOf<Int, String>()
        for (trackIndex in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            if (mime == null || (!mime.startsWith("audio/") && !mime.startsWith("video/"))) {
                continue
            }
            trackMap[trackIndex] = muxer.addTrack(trackFormat)
            trackMimeTypes[trackIndex] = mime
        }

        if (trackMap.isEmpty()) {
            extractor.release()
            throw IllegalStateException("No audio/video tracks found for export")
        }

        muxer.start()
        val totalSegments = segments.size
        val segmentBuffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
        val sampleInfo = MediaCodec.BufferInfo()
        val trackOutputOffsetUs = trackMap.keys.associateWith { 0L }.toMutableMap()
        var outputCursorUs = 0L
        val totalSourceUs = max(1L, segments.sumOf { max(1L, it.endMs - it.startMs) } * 1000L)
        val progressTrack = trackMap.keys.firstOrNull { (trackMimeTypes[it] ?: "").startsWith("video/") } ?: trackMap.keys.firstOrNull()
        var exportedUsEstimate = 0L
        var lastExportLogMs = 0L

        segments.forEachIndexed { index, segment ->
            val segStartUs = segment.startMs * 1000L
            val segEndUs = segment.endMs * 1000L
            val segmentDurationUs = max(1L, segEndUs - segStartUs)
            var segmentMaxUs = outputCursorUs

            for (trackIndex in trackMap.keys) {
                var lastInputSampleUs = Long.MIN_VALUE
                var lastOutputSampleUs = outputCursorUs
                extractor.selectTrack(trackIndex)
                extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val isProgressTrack = trackIndex == progressTrack
                var segTrackMaxUs = outputCursorUs

                while (true) {
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs == -1L || sampleTimeUs >= segEndUs) {
                        break
                    }
                    if (sampleTimeUs < segStartUs) {
                        extractor.advance()
                        continue
                    }

                    val sampleSize = extractor.readSampleData(segmentBuffer, 0)
                    if (sampleSize < 0) {
                        break
                    }

                    sampleInfo.size = sampleSize
                    val targetPtsUs = trackOutputOffsetUs.getValue(trackIndex) + sampleTimeUs - segStartUs
                    val outputPtsUs = if (targetPtsUs > lastOutputSampleUs) {
                        targetPtsUs
                    } else {
                        lastOutputSampleUs + 1L
                    }
                    sampleInfo.presentationTimeUs = outputPtsUs
                    sampleInfo.flags = extractor.sampleFlags
                    sampleInfo.offset = 0
                    muxer.writeSampleData(trackMap.getValue(trackIndex), segmentBuffer, sampleInfo)
                    lastInputSampleUs = sampleTimeUs
                    lastOutputSampleUs = outputPtsUs
                    if (isProgressTrack) {
                        segTrackMaxUs = maxOf(segTrackMaxUs, outputPtsUs)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastExportLogMs >= 500L) {
                            val completedUs = (exportedUsEstimate + (segTrackMaxUs - outputCursorUs)).coerceAtMost(totalSourceUs)
                            val exportPercent = (60 + (completedUs * 35L / totalSourceUs).toInt()).coerceIn(60, 95)
                            val remaining = formatDurationUs(totalSourceUs - completedUs)
                            progress(
                                "Exporting segments ${index + 1}/$totalSegments (${formatDurationMs(completedUs / 1000L)} / ${formatDurationMs(totalSourceUs / 1000L)}, remaining $remaining)",
                                exportPercent
                            )
                            lastExportLogMs = now
                        }
                    }
                    extractor.advance()
                }
                segmentMaxUs = maxOf(segmentMaxUs, lastOutputSampleUs + 1L)
                if (isProgressTrack) {
                    val segmentTrackWrittenUs = (segTrackMaxUs - outputCursorUs).coerceAtLeast(0L)
                    exportedUsEstimate += segmentTrackWrittenUs
                }
                trackOutputOffsetUs[trackIndex] = segmentMaxUs
                extractor.unselectTrack(trackIndex)
            }

            outputCursorUs = maxOf(outputCursorUs + segmentDurationUs, segmentMaxUs)
            trackMap.keys.forEach { trackIndex ->
                if (trackOutputOffsetUs.getValue(trackIndex) < outputCursorUs) {
                    trackOutputOffsetUs[trackIndex] = outputCursorUs
                }
            }
            val percent = 60 + ((index + 1) * 35 / totalSegments)
            progress("Writing segment ${index + 1}/$totalSegments", percent)
        }

        muxer.stop()
        muxer.release()
        extractor.release()
    }

    private fun alignSegmentsToVideoSyncSamples(
        sourceUri: Uri,
        segments: List<SegmentWindow>
    ): List<SegmentWindow> {
        if (segments.isEmpty()) return segments

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, sourceUri, null)
            val videoTrackIndex = (0 until extractor.trackCount).firstOrNull { trackIndex ->
                extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return segments

            extractor.selectTrack(videoTrackIndex)
            val aligned = segments.map { segment ->
                val requestedStartUs = segment.startMs * 1000L
                extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val syncSampleUs = extractor.sampleTime
                val alignedStartMs = if (syncSampleUs >= 0L && syncSampleUs <= requestedStartUs) {
                    syncSampleUs / 1000L
                } else {
                    segment.startMs
                }
                SegmentWindow(alignedStartMs, segment.endMs)
            }.sortedBy { it.startMs }
            mergeOverlapping(aligned)
        } catch (e: Exception) {
            Log.w(tag, "Unable to align export segments to video sync samples", e)
            segments
        } finally {
            extractor.release()
        }
    }

    private fun mergeOverlapping(input: List<SegmentWindow>): List<SegmentWindow> {
        return mergeWithGap(input, 0L)
    }

    private fun mergeWithGap(input: List<SegmentWindow>, maxGapMs: Long): List<SegmentWindow> {
        if (input.isEmpty()) return emptyList()
        val merged = ArrayList<SegmentWindow>()
        val sorted = input.sortedBy { it.startMs }
        var current = sorted[0]
        for (i in 1 until input.size) {
            val next = sorted[i]
            if (next.startMs <= current.endMs + maxGapMs) {
                current = SegmentWindow(current.startMs, max(current.endMs, next.endMs))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun formatDurationMs(totalMs: Long): String {
        val totalSec = max(0L, totalMs) / 1000
        val sec = totalSec % 60
        val min = totalSec / 60
        val ms = max(0L, totalMs) % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", min, sec, ms)
    }

    private fun formatDurationUs(totalUs: Long): String {
        return formatDurationMs(totalUs / 1000L)
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val frac = ms % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", min, sec, frac)
    }
}

private data class ScanTimingSummary(
    var decodeMs: Long = 0L,
    var cropMs: Long = 0L,
    var preprocessMs: Long = 0L,
    var ocrMs: Long = 0L,
    var mergeMs: Long = 0L
) {
    fun totalMs(): Long = decodeMs + cropMs + preprocessMs + ocrMs + mergeMs
}

private data class ScanFindResult(
    val segments: List<SegmentWindow>,
    val timing: ScanTimingSummary
)

private data class ScanFindReport(
    val sourceUri: String,
    val profileLabel: String,
    val scanWindow: ScanWindow,
    val frameStepMs: Long,
    val mode: String,
    val durationMs: Long,
    val transitionsFound: Int,
    val timing: ScanTimingSummary
)

private data class NumberDetectionResult(
    val value: Int?,
    val cropMs: Long,
    val preprocessMs: Long,
    val ocrMs: Long
)

private data class RoiSignature(
    val samples: IntArray,
    val average: Int
)

private data class TimedResult<T>(
    val value: T,
    val elapsedMs: Long
)

private data class UpdateInfo(
    val versionName: String,
    val releaseUrl: String,
    val downloadUrl: String,
    val releaseNotes: String
)

private data class SegmentWindow(val startMs: Long, val endMs: Long)
private data class ScanWindow(val xPercent: Float, val yPercent: Float, val widthPercent: Float, val heightPercent: Float)
private data class ScanProfile(val label: String, val frameStepMs: Long, val mode: ScanMode)
private enum class ScanMode { Fast, Checkpoints3Min }
private enum class RoiTouchMode { NONE, MOVE, RESIZE }

private enum class TransitionStyle(val label: String, val edgePaddingMs: Long, val mergeGapMs: Long) {
    Instant("Instant cuts", 0L, 0L),
    Gradual("Gradual transitions", 3_000L, 2_000L)
}

private enum class ExportQuality(val label: String, val preset: String, val crf: Int, val extraVideoArgs: List<String>) {
    Low("Low (faster)", "ultrafast", 32, listOf()),
    Medium("Medium", "medium", 23, listOf("-movflags", "+faststart")),
    High("High (slower)", "slow", 18, listOf("-movflags", "+faststart"))
}

private enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val videoCodec: String,
    val audioCodec: String,
    val audioBitrate: String,
    val extraArgs: List<String>
) {
    Mp4("MP4 (H.264)", "mp4", "video/mp4", "libx264", "aac", "160k", listOf("-pix_fmt", "yuv420p")),
    Webm("WEBM (VP9)", "webm", "video/webm", "libvpx-vp9", "libopus", "128k", listOf()),
    Mov("MOV (ProRes)", "mov", "video/quicktime", "prores_ks", "pcm_s16le", "256k", listOf("-profile:v", "3"))
}
