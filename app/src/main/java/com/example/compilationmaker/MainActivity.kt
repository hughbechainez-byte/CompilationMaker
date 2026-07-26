package com.example.compilationmaker

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.compilationmaker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Optimized MainActivity (0.17.37) + video selection hotfix
 * - Pixel 10 Pro LOW / MEDIUM RISK scan profiles labeled
 * - Menu: Clean up originals + Pixel speed info
 * - Post-success delete original prompt
 * - PiP live progress banner
 * - RESTORED: Select Video button + ACTION_OPEN_DOCUMENT picker (was stripped in size optimization)
 * Legacy VideoCompilationEngine removed; production path = CanonicalScannerBridge + Media3 + WorkManager
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
        binding.selectButton.setOnClickListener { launchVideoPicker() }
        binding.processButton.setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Pick a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Video selected. Full pipeline wiring uses WorkManager (see restoreActiveCompilationWork).", Toast.LENGTH_LONG).show()
        }
        restoreActiveCompilationWork()
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
        binding.selectedVideo.text = picked.toString()
        binding.roiStatusText.text = "Video selected: ${picked.toString().take(80)}"
        Toast.makeText(this, "Video selected", Toast.LENGTH_SHORT).show()
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
        binding.scanSpeedPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
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
