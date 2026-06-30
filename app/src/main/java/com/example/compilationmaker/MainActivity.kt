package com.example.compilationmaker

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
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
import android.provider.MediaStore
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
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
    private var previewBitmap: Bitmap? = null
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

    private val qualityOptions = arrayOf(ExportQuality.Low, ExportQuality.Medium, ExportQuality.High)
    private val formatOptions = arrayOf(ExportFormat.Mp4, ExportFormat.Webm, ExportFormat.Mov)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpUi()
        requestPermissionsIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        stopPreviewProgressUpdates()
        videoPreview.stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreviewProgressUpdates()
        previewBitmap?.recycle()
        previewBitmap = null
    }

    private fun setUpUi() {
        qualitySpinner = binding.qualityPicker
        formatSpinner = binding.formatPicker
        scanSpeedSpinner = binding.scanSpeedPicker
        videoPreview = binding.videoPreview
        previewSeekBar = binding.previewSeekBar
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
            runCompilation(source, quality, format)
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
        scanSpeedSpinner.setSelection(0, false)
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
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty() && needed.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionRequestLauncher.launch(needed.toTypedArray())
        }
    }

    private fun runCompilation(uri: Uri, quality: ExportQuality, format: ExportFormat) {
        lifecycleScope.launch {
            isBusy = true
            setUiBusy(true)
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
                    scanProfile.label
                ) { message, percent ->
                    emitProgress(message, percent)
                }
                timingBuckets["scan"] = scanResult.timing.totalMs()
                emitProgress(
                    "Scan timing (ms): decode=${scanResult.timing.decodeMs} crop=${scanResult.timing.cropMs} preprocess=${scanResult.timing.preprocessMs} ocr=${scanResult.timing.ocrMs} merge=${scanResult.timing.mergeMs}",
                    15
                )

                val windows = scanResult.segments.toMutableList()
                if (windows.isEmpty()) {
                    emitProgress("No number transitions found. Falling back to a short full-video clip.", 45)
                    val durationMs = engine.getDurationMs(uri)
                    if (durationMs <= 0L) {
                        emitProgress("Unable to read source duration.", 100)
                        return@launch
                    }
                    windows.clear()
                    windows.add(SegmentWindow(0L, min(durationMs, 30_000L)))
                }

                emitProgress("Trimming and concatenating ${windows.size} segments...", 55)
                var compiledOutput: File? = null
                val exportTiming = measureTimeMillis {
                    compiledOutput = engine.renderCompilation(uri, windows, quality, format) { message, percent ->
                        emitProgress(message, percent)
                    }
                }
                timingBuckets["export"] = exportTiming

                val saveTiming = measureTimeMillis {
                    val output = compiledOutput ?: throw IllegalStateException("Compilation output missing")
                    val saved = saveToPhoneStorage(output, format)
                    emitProgress("Saved: $saved", 100)
                }
                timingBuckets["save"] = saveTiming
                emitProgress("Export timing (ms): export=$exportTiming save=$saveTiming", 90)
                emitProgress(
                    "Performance summary: scan=${timingBuckets["scan"]}ms export=${timingBuckets["export"]}ms save=${timingBuckets["save"]}ms",
                    95
                )
                emitProgress("Scan report stored: ${engine.latestScanReportPath}", 96)
                Toast.makeText(this@MainActivity, "Export complete", Toast.LENGTH_LONG).show()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
            if (uri != null) {
                resolver.openOutputStream(uri, "w").use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out ?: throw IllegalStateException("No output stream"))
                    }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return@withContext uri.toString()
            }
        }

        val fallbackDir = Environment.getExternalStoragePublicDirectory("${Environment.DIRECTORY_MOVIES}/CompilationMaker")
        fallbackDir.mkdirs()
        val outFile = File(fallbackDir, "$filename.${safeFormat.extension}")
        runCatching {
            file.inputStream().use { input ->
                outFile.outputStream().use { out ->
                    input.copyTo(out)
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
                input.copyTo(out)
            }
        }
        return@withContext privateOut.absolutePath
    }

    private fun setUiBusy(busy: Boolean) {
        runOnUiThread {
            binding.processButton.isEnabled = !busy
            binding.selectButton.isEnabled = !busy
            binding.progressBar.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.INVISIBLE
            progressPercentText.visibility = View.VISIBLE
            statusFeedScroll.visibility = View.VISIBLE
        }
    }

    private fun emitProgress(message: String, percent: Int) {
        val percentValue = percent.coerceIn(0, 100)
        runOnUiThread {
            binding.statusText.text = message
            progressPercentText.text = "$percentValue%"
            binding.progressBar.progress = percentValue
            addStatusLine(message)
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
        progress: (String, Int) -> Unit
    ): ScanFindResult = withContext(Dispatchers.IO) {
        Log.d(tag, "Starting scan for number transitions: $sourceUri")
        val retriever = android.media.MediaMetadataRetriever().apply {
            setDataSource(context, sourceUri)
        }
        val timing = ScanTimingSummary()

        try {
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("Could not read duration")
            if (durationMs <= 0L) return@withContext ScanFindResult(emptyList(), timing)

            val transitions = mutableListOf<Long>()
            val checkpointMs = frameStepMs.coerceAtLeast(1L)
            var previousNumber: Int? = null
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
                    frame = retriever.getFrameAtTime(
                        cursorMs * 1000,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
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
                try {
                    val detected = try {
                        val detection = detectCornerNumberWithTimings(frame, scanWindow, sourceRotationDegrees)
                        timing.cropMs += detection.cropMs
                        timing.preprocessMs += detection.preprocessMs
                        timing.ocrMs += detection.ocrMs
                        detection.value
                    } catch (e: Exception) {
                        Log.w(tag, "OCR failed at ${cursorMs}ms", e)
                        null
                    }

                    didDetectChange = detected != null && previousNumber != null && detected != previousNumber
                    if (detected != null && previousNumber != null && detected != previousNumber) {
                        transitions.add(cursorMs)
                        val cutStartMs = max(0L, cursorMs - 10_000L)
                        val cutEndMs = min(durationMs, cursorMs + 30_000L)
                        progress(
                            "Transition at ${formatMs(cursorMs)}; cut ${formatMs(cutStartMs)} -> ${formatMs(cutEndMs)}",
                            min(100, ((cursorMs.toFloat() / durationMs.toFloat()) * 100f + 1).toInt())
                        )
                    }
                    if (detected != null) {
                        previousNumber = detected
                    }
                } finally {
                    frame.recycle()
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
            val rawSegments = transitions.map { t ->
                SegmentWindow(
                    startMs = max(0L, t - 10_000),
                    endMs = min(durationMs, t + 30_000)
                )
            }.sortedBy { it.startMs }
            val mergedTiming = measureTimeMillis {
                mergeOverlapping(rawSegments)
            }
            val merged = mergeOverlapping(rawSegments)
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
            latestScanReport = report
            latestScanReportPath = writeScanReport(report)
            return@withContext ScanFindResult(merged, timing)
        } finally {
            recognizer.close()
            retriever.release()
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
        progress: (String, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val cache = context.cacheDir
        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format
        val cachedInput = File(cache, "input_source.mp4")
        context.contentResolver.openInputStream(sourceUri).use { input ->
            FileOutputStream(cachedInput).use { out ->
                input?.copyTo(out) ?: throw IllegalStateException("Cannot open source video")
            }
        }

        if (segments.isEmpty()) throw IllegalStateException("No segments to assemble")
        val output = File(cache, "compilation_${System.currentTimeMillis()}.${safeFormat.extension}")
        progress("Using ${quality.label} profile", 82)
        progress("Assembling final compilation", 95)
        materializeCompilation(cachedInput, segments, safeFormat, output) { message, percent ->
            progress(message, percent)
        }
        progress("Compilation ready to save", 100)
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
            val result = withTimeoutOrNull(700) { recognizer.process(image).await() } ?: return null
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
            val result = withTimeoutOrNull(700) { recognizer.process(image).await() }
            val ocrMs = System.currentTimeMillis() - ocrStart
            if (result == null) {
                NumberDetectionResult(null, cropMs, preprocessMs, ocrMs)
            } else {
                val text = result.text.replace("\n", " ")
                val match = Regex("[-+]?[0-9]+").find(text)
                NumberDetectionResult(match?.value?.toIntOrNull(), cropMs, preprocessMs, ocrMs)
            }
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
        source: File,
        segments: List<SegmentWindow>,
        format: ExportFormat,
        output: File,
        progress: (String, Int) -> Unit
    ) {
        if (!source.exists()) {
            throw IllegalStateException("Cannot open source input for trimming")
        }

        if (segments.isEmpty()) {
            throw IllegalStateException("No segments to assemble")
        }

        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format
        val muxerFormat = when (safeFormat) {
            ExportFormat.Mp4, ExportFormat.Mov -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            ExportFormat.Webm -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val trackMap = mutableMapOf<Int, Int>()

        val muxer = MediaMuxer(output.absolutePath, muxerFormat)
        for (trackIndex in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            if (mime == null || (!mime.startsWith("audio/") && !mime.startsWith("video/"))) {
                continue
            }
            trackMap[trackIndex] = muxer.addTrack(trackFormat)
        }

        if (trackMap.isEmpty()) {
            extractor.release()
            throw IllegalStateException("No audio/video tracks found for export")
        }

        muxer.start()
        val totalSegments = segments.size
        val segmentBuffer = ByteBuffer.allocate(5 * 1024 * 1024)
        val sampleInfo = MediaCodec.BufferInfo()
        var outputOffsetUs = 0L

        segments.forEachIndexed { index, segment ->
            val segStartUs = segment.startMs * 1000L
            val segEndUs = segment.endMs * 1000L

            for (trackIndex in trackMap.keys) {
                extractor.selectTrack(trackIndex)
                extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

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
                    sampleInfo.presentationTimeUs = outputOffsetUs + sampleTimeUs - segStartUs
                    sampleInfo.flags = extractor.sampleFlags
                    sampleInfo.offset = 0
                    muxer.writeSampleData(trackMap.getValue(trackIndex), segmentBuffer, sampleInfo)
                    extractor.advance()
                }
                extractor.unselectTrack(trackIndex)
            }

            outputOffsetUs += max(segment.endMs - segment.startMs, 0L) * 1000L
            val percent = 60 + ((index + 1) * 35 / totalSegments)
            progress("Writing segment ${index + 1}/$totalSegments", percent)
        }

        muxer.stop()
        muxer.release()
        extractor.release()
    }

    private fun mergeOverlapping(input: List<SegmentWindow>): List<SegmentWindow> {
        if (input.isEmpty()) return emptyList()
        val merged = ArrayList<SegmentWindow>()
        var current = input[0]
        for (i in 1 until input.size) {
            val next = input[i]
            if (next.startMs <= current.endMs) {
                current = SegmentWindow(current.startMs, max(current.endMs, next.endMs))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
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

private data class SegmentWindow(val startMs: Long, val endMs: Long)
private data class ScanWindow(val xPercent: Float, val yPercent: Float, val widthPercent: Float, val heightPercent: Float)
private data class ScanProfile(val label: String, val frameStepMs: Long, val mode: ScanMode)
private enum class ScanMode { Fast, Checkpoints3Min }
private enum class RoiTouchMode { NONE, MOVE, RESIZE }

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
