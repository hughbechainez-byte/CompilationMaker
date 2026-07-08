package com.example.compilationmaker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.File

class CompilationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceUriRaw = inputData.getString(KEY_SOURCE_URI)
        val qualityOrdinal = inputData.getInt(KEY_QUALITY_ORDINAL, ExportFormat.Mp4.ordinal)
        val formatOrdinal = inputData.getInt(KEY_FORMAT_ORDINAL, ExportFormat.Mp4.ordinal)
        val transitionOrdinal = inputData.getInt(KEY_TRANSITION_STYLE_ORDINAL, TransitionStyle.Instant.ordinal)
        val scanModeOrdinal = inputData.getInt(KEY_SCAN_MODE, ScanMode.StableCheckpoint.ordinal)
        val checkpointIntervalMs = inputData.getLong(KEY_CHECKPOINT_INTERVAL_MS, 180_000L)
        val downscaleSize = inputData.getInt(KEY_EXPERIMENTAL_DOWNSCALE, 32)
        val rotation = inputData.getInt(KEY_VIDEO_ROTATION, 0)
        val scanWindowRaw = inputData.getString(KEY_SCAN_WINDOW)

        if (sourceUriRaw.isNullOrBlank()) {
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Missing source video"))
        }

        setForegroundCompat("starting", "Preparing compilation", 0)
        setProgressCompat("starting", "Preparing compilation", 0)

        val sourceUri = runCatching { Uri.parse(sourceUriRaw) }.getOrNull()
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Invalid source URI"))

        val quality = ExportQuality.values().getOrNull(qualityOrdinal) ?: ExportQuality.Medium
        val format = ExportFormat.values().getOrNull(formatOrdinal) ?: ExportFormat.Mp4
        val transitionStyle = TransitionStyle.values().getOrNull(transitionOrdinal) ?: TransitionStyle.Instant
        val scanMode = ScanMode.values().getOrNull(scanModeOrdinal) ?: ScanMode.StableCheckpoint
        val scanWindow = parseScanWindow(scanWindowRaw)
        val engine = VideoCompilationEngine(applicationContext)
        var fallbackUsed = false
        var failureReason: String? = null

        return@withContext try {
            var scanResult = runScanWithMode(
                engine = engine,
                sourceUri = sourceUri,
                scanMode = scanMode,
                scanWindow = scanWindow,
                scanIntervalMs = checkpointIntervalMs,
                rotation = rotation,
                downscaleSize = downscaleSize,
                transitionStyle = transitionStyle
            ) { phase, message, percent ->
                setProgressCompat("$phase", message, percent)
                setForegroundCompat(phase, message, percent)
                if (!isActive) {
                    throw CancellationException("Job cancelled")
                }
            }

        if (scanMode == ScanMode.Experimental && !scanResult.success) {
                fallbackUsed = true
                failureReason = scanResult.failureReason
                setProgressCompat("fallback", "Experimental mode failed, falling back to stable checkpoint", 50, true)
                setForegroundCompat("fallback", "Experimental mode failed, falling back to stable checkpoint", 50, true)
                scanResult = runScanWithMode(
                    engine = engine,
                    sourceUri = sourceUri,
                    scanMode = ScanMode.StableCheckpoint,
                    scanWindow = scanWindow,
                    scanIntervalMs = checkpointIntervalMs,
                    rotation = rotation,
                    downscaleSize = downscaleSize,
                    transitionStyle = transitionStyle
                ) { phase, message, percent ->
                    setProgressCompat(phase, message, percent)
                    setForegroundCompat(phase, message, percent)
                }
            }

            val reportPath = scanResult.reportPath

            var windows = scanResult.segments
            if (windows.isEmpty()) {
                val durationMs = scanResult.durationMs
                if (durationMs > 0L) {
                    windows = listOf(SegmentWindow(0L, minOf(30_000L, durationMs))
                    )
                    setProgressCompat("scan", "No transitions found, using short safety clip", 52)
                }
            }

            setProgressCompat("export", "Assembling compilation", 55)
            setForegroundCompat("export", "Assembling compilation", 55)
            val outputFile = engine.renderCompilation(
                sourceUri = sourceUri,
                segments = windows,
                quality = quality,
                format = format,
                transitionStyle = transitionStyle
            ) { message, percent ->
                val exportPercent = ((percent * 0.35f) + 55f).toInt().coerceIn(55, 95)
                setProgressCompat("export", message, exportPercent)
                setForegroundCompat("export", message, exportPercent)
            }

            reportPath?.let { augmentReport(it, fallbackUsed, failureReason) }

            val finalReportPath = reportPath
            val failure = if (fallbackUsed) failureReason else null
            val result = workDataOf(
                KEY_OUTPUT_PATH to outputFile.absolutePath,
                KEY_FORMAT_ORDINAL to format.ordinal,
                KEY_REPORT_PATH to finalReportPath,
                KEY_FALLBACK_USED to fallbackUsed,
                KEY_ERROR_MESSAGE to failure
            )
            setProgressCompat("completed", "Compilation complete", 100, fallbackUsed)
            setForegroundCompat("completed", "Compilation complete", 100, fallbackUsed)
            Result.success(result)
        } catch (cancelled: CancellationException) {
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (cancelled.message ?: "Cancelled"), KEY_FALLBACK_USED to fallbackUsed))
        } catch (e: Exception) {
            Log.e("CompilationWorker", "Compilation failed", e)
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Compilation failed"), KEY_FALLBACK_USED to fallbackUsed))
        }
    }

    private suspend fun runScanWithMode(
        engine: VideoCompilationEngine,
        sourceUri: Uri,
        scanMode: ScanMode,
        scanWindow: ScanWindow,
        scanIntervalMs: Long,
        rotation: Int,
        downscaleSize: Int,
        transitionStyle: TransitionStyle,
        progress: (String, String, Int) -> Unit
    ): ScanTaskResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val result = engine.findNumberTransitionSegments(
                sourceUri = sourceUri,
                frameStepMs = scanIntervalMs,
                scanMode = scanMode,
                scanWindow = scanWindow,
                sourceRotationDegrees = rotation,
                scanProfileLabel = scanMode.name,
                transitionStyle = transitionStyle,
                experimentalDownscaleSize = downscaleSize,
                fallbackUsed = false
            ) { message, percent ->
                val phase = when {
                    scanMode == ScanMode.StableCheckpoint -> "stable scan"
                    else -> "experimental scan"
                }
                progress(phase, message, if (scanMode == ScanMode.StableCheckpoint) percent else (percent * 0.5f + 20f).toInt())
            }
            val durationMs = result.timing.totalMs().coerceAtLeast(0L)
            ScanTaskResult(success = true, segments = result.segments, durationMs = durationMs, reportPath = engine.latestScanReportPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ScanTaskResult(success = false, segments = emptyList(), durationMs = 0L, reportPath = engine.latestScanReportPath, failureReason = e.message)
        }
    }

    private fun augmentReport(path: String, fallbackUsed: Boolean, failureReason: String?) {
        if (path.isBlank()) return
        runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching
            val json = JSONObject(file.readText())
            json.put(KEY_FALLBACK_USED, fallbackUsed)
            if (failureReason != null) {
                json.put("failureReason", failureReason)
            }
            file.writeText(json.toString(2))
        }
    }

    private fun parseScanWindow(raw: String?): ScanWindow {
        if (raw.isNullOrBlank()) {
            return ScanWindow(0.0f, 0.8f, 0.10f, 0.30f)
        }
        return runCatching {
            val json = JSONObject(raw)
            ScanWindow(
                (json.optDouble("xPercent", 0.0).toFloat()),
                (json.optDouble("yPercent", 0.8).toFloat()),
                (json.optDouble("widthPercent", 0.1).toFloat()),
                (json.optDouble("heightPercent", 0.3).toFloat())
            )
        }.getOrNull() ?: ScanWindow(0.0f, 0.8f, 0.10f, 0.30f)
    }

    private fun setProgressCompat(
        phase: String,
        message: String,
        percent: Int,
        fallbackUsed: Boolean = false
    ) {
        if (percent < 0 || percent > 100) return
        val data = workDataOf(
            KEY_PROGRESS_PHASE to phase,
            KEY_PROGRESS_MESSAGE to message,
            KEY_PROGRESS_PERCENT to percent,
            KEY_FALLBACK_USED to fallbackUsed
        )
        setProgressAsync(data)
    }

    private fun setForegroundCompat(
        phase: String,
        message: String,
        percent: Int,
        fallbackUsed: Boolean = false
    ) {
        setProgressCompat(phase, message, percent, fallbackUsed)
        if (!isNotificationEnabled(applicationContext)) return
        val notification = compileNotification(phase, message, percent)
        setForegroundAsync(ForegroundInfo(NOTIFICATION_ID, notification))
    }

    private fun compileNotification(phase: String, message: String, percent: Int): android.app.Notification {
        ensureNotificationChannel()
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Compilation: $phase")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(intent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Compilation progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
        )
    }

    private fun isNotificationEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private data class ScanTaskResult(
        val success: Boolean,
        val segments: List<SegmentWindow>,
        val durationMs: Long,
        val reportPath: String?,
        val failureReason: String? = null
    )

    companion object {
        const val KEY_SOURCE_URI = "sourceUri"
        const val KEY_SCAN_WINDOW = "scanWindow"
        const val KEY_SCAN_MODE = "scanMode"
        const val KEY_CHECKPOINT_INTERVAL_MS = "checkpointIntervalMs"
        const val KEY_EXPERIMENTAL_DOWNSCALE = "experimentalDownscale"
        const val KEY_QUALITY_ORDINAL = "qualityOrdinal"
        const val KEY_FORMAT_ORDINAL = "formatOrdinal"
        const val KEY_TRANSITION_STYLE_ORDINAL = "transitionStyleOrdinal"
        const val KEY_VIDEO_ROTATION = "videoRotation"
        const val KEY_PROGRESS_PHASE = "progressPhase"
        const val KEY_PROGRESS_MESSAGE = "progressMessage"
        const val KEY_PROGRESS_PERCENT = "progressPercent"
        const val KEY_OUTPUT_PATH = "outputPath"
        const val KEY_REPORT_PATH = "reportPath"
        const val KEY_FALLBACK_USED = "fallbackUsed"
        const val KEY_ERROR_MESSAGE = "errorMessage"

        private const val NOTIFICATION_CHANNEL_ID = "compilation_progress"
        private const val NOTIFICATION_ID = 6106
    }
}
