package com.example.compilationmaker

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exact clip window used by CanonicalScannerBridge → Media3 export. */
data class SegmentWindow(val startMs: Long, val endMs: Long)

/** ROI in display-upright percent space (x/y/width/height). */
data class ScanWindow(
    val xPercent: Float,
    val yPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float
)

enum class TransitionStyle(val label: String, val edgePaddingMs: Long, val mergeGapMs: Long) {
    Instant("Instant cuts", 0L, 0L),
    Gradual("Gradual transitions", 3_000L, 2_000L)
}

enum class ExportQuality(val label: String, val preset: String, val crf: Int) {
    Low("Low (faster)", "ultrafast", 32),
    Medium("Medium", "medium", 23),
    High("High (slower)", "slow", 18)
}

enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    Mp4("MP4 (H.264)", "mp4", "video/mp4"),
    Webm("WEBM (VP9)", "webm", "video/webm"),
    Mov("MOV", "mov", "video/quicktime")
}

data class VerifiedCompilationOutput(
    val file: File,
    val uri: String,
    val sizeBytes: Long,
    val durationMs: Long
)

/**
 * Thin production engine used by CompilationWorker.
 * Scan path is CanonicalScannerBridge; this class only performs Media3 exact-boundary
 * export + output verification. Legacy visual/OCR scanner code is intentionally absent.
 */
class VideoCompilationEngine(private val context: Context) : AutoCloseable {

    private val tag = "CompilationEngine"

    suspend fun renderCompilation(
        sourceUri: Uri,
        segments: List<SegmentWindow>,
        quality: ExportQuality,
        format: ExportFormat,
        transitionStyle: TransitionStyle,
        outputFile: File? = null,
        progress: (String, Int) -> Unit
    ): VerifiedCompilationOutput = withContext(Dispatchers.IO) {
        val safeFormat = if (format == ExportFormat.Webm) ExportFormat.Mp4 else format
        if (segments.isEmpty()) throw IllegalStateException("No segments to assemble")

        val output = outputFile ?: File(
            File(context.filesDir, "compilations").apply { mkdirs() },
            "compilation_${System.currentTimeMillis()}.${safeFormat.extension}"
        )
        output.parentFile?.mkdirs()

        progress("Using ${quality.label} profile with exact semantic cuts", 56)
        progress("Preparing exact Media3 composition", 57)

        val exactSegments = mergeOverlapping(segments.sortedBy { it.startMs })
        val expectedDurationMs = expectedCompilationDurationMs(exactSegments)
        AppLog.i(
            context,
            tag,
            "[export] exporter=media3-transformer style=${transitionStyle.label} " +
                "segments=${exactSegments.size} expectedDurationMs=$expectedDurationMs"
        )

        Media3CompilationExporter(context).export(sourceUri, exactSegments, output) { message, percent ->
            progress(message, percent)
        }

        val verified = verifyCompilationOutput(output)
        val durationDeltaMs = kotlin.math.abs(verified.durationMs - expectedDurationMs)
        check(durationDeltaMs <= 2_000L) {
            "Export duration ${verified.durationMs}ms differs from exact clip plan ${expectedDurationMs}ms by ${durationDeltaMs}ms"
        }
        progress("Compilation ready to save", 94)
        verified
    }

    suspend fun verifyCompilationOutput(output: File): VerifiedCompilationOutput = withContext(Dispatchers.IO) {
        if (!output.exists() || !output.isFile || !output.canRead() || output.length() <= 0L) {
            throw IllegalStateException("Exported compilation was not written successfully")
        }
        output.inputStream().use { input ->
            if (input.read() < 0) throw IllegalStateException("Exported compilation cannot be opened")
        }

        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        val durationMs = try {
            retriever.setDataSource(output.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            extractor.setDataSource(output.absolutePath)
            val hasVideo = (0 until extractor.trackCount).any { trackIndex ->
                extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (!hasVideo) throw IllegalStateException("Exported compilation has no video track")
            if (duration <= 0L) throw IllegalStateException("Exported compilation has no readable duration")
            duration
        } finally {
            runCatching { retriever.release() }
            runCatching { extractor.release() }
        }

        val readableUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updateprovider",
            output
        )
        context.contentResolver.openInputStream(readableUri).use { input ->
            if (input == null || input.read() < 0) {
                throw IllegalStateException("Exported compilation URI cannot be opened")
            }
        }

        VerifiedCompilationOutput(
            file = output,
            uri = readableUri.toString(),
            sizeBytes = output.length(),
            durationMs = durationMs
        )
    }

    override fun close() = Unit

    private fun mergeOverlapping(input: List<SegmentWindow>): List<SegmentWindow> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedBy { it.startMs }
        val merged = ArrayList<SegmentWindow>()
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startMs <= current.endMs) {
                current = SegmentWindow(current.startMs, maxOf(current.endMs, next.endMs))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }
}
