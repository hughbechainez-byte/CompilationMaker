package com.example.compilationmaker

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Exact-video-boundary production exporter. Video is decoded/re-encoded instead of exposing the
 * previous sync sample as part of a requested clip, while compatible source audio is transmuxed.
 */
@OptIn(UnstableApi::class)
internal class Media3CompilationExporter(context: Context) {
    private val appContext = context.applicationContext

    suspend fun export(
        sourceUri: Uri,
        segments: List<SegmentWindow>,
        output: File,
        progress: (message: String, percent: Int) -> Unit
    ) {
        require(segments.isNotEmpty()) { "No segments to export" }
        require(segments.all { it.startMs >= 0L && it.endMs > it.startMs }) {
            "Every export segment must have positive duration"
        }
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) {
            throw IllegalStateException("Unable to replace existing output ${output.absolutePath}")
        }

        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var transformer: Transformer? = null
            var progressTicker: Runnable? = null

            fun stopProgressTicker() {
                progressTicker?.let(handler::removeCallbacks)
                progressTicker = null
            }

            fun finishWithFailure(failure: Throwable, cancelTransformer: Boolean) {
                stopProgressTicker()
                val activeTransformer = transformer
                transformer = null
                if (cancelTransformer) {
                    runCatching { activeTransformer?.cancel() }
                }
                if (continuation.isActive) {
                    continuation.resumeWithException(failure)
                }
            }

            continuation.invokeOnCancellation {
                handler.post {
                    stopProgressTicker()
                    val activeTransformer = transformer
                    transformer = null
                    runCatching { activeTransformer?.cancel() }
                }
            }

            handler.post {
                if (!continuation.isActive) return@post
                try {
                    val editedItems = segments.map { segment ->
                        val clippedMediaItem = MediaItem.Builder()
                            .setUri(sourceUri)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(segment.startMs)
                                    .setEndPositionMs(segment.endMs)
                                    .build()
                            )
                            .build()
                        EditedMediaItem.Builder(clippedMediaItem).build()
                    }
                    val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)
                    val composition = Composition.Builder(sequence)
                        // Every item is a clip of the same source URI, so its AAC samples are compatible.
                        // Keep video transcoding for exact visual boundaries, but avoid re-encoding audio.
                        .setTransmuxAudio(true)
                        .setTransmuxVideo(false)
                        .build()
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            stopProgressTicker()
                            transformer = null
                            if (!continuation.isActive) return
                            try {
                                progress("Media3 exact-boundary export complete", 95)
                                continuation.resume(Unit)
                            } catch (failure: Throwable) {
                                continuation.resumeWithException(failure)
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            finishWithFailure(exportException, cancelTransformer = false)
                        }
                    }
                    val activeTransformer = Transformer.Builder(appContext)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(listener)
                        .build()
                    transformer = activeTransformer

                    val progressHolder = ProgressHolder()
                    progressTicker = object : Runnable {
                        override fun run() {
                            val currentTransformer = transformer ?: return
                            if (!continuation.isActive) {
                                stopProgressTicker()
                                runCatching { currentTransformer.cancel() }
                                transformer = null
                                return
                            }
                            try {
                                if (currentTransformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                    val exportPercent = progressHolder.progress.coerceIn(0, 100)
                                    progress(
                                        "Transcoding exact clips with Media3 ($exportPercent%)",
                                        (60 + exportPercent * 35 / 100).coerceIn(60, 95)
                                    )
                                }
                                handler.postDelayed(this, 500L)
                            } catch (failure: Throwable) {
                                finishWithFailure(failure, cancelTransformer = true)
                            }
                        }
                    }

                    progress("Starting Media3 exact-boundary export", 60)
                    activeTransformer.start(composition, output.absolutePath)
                    progressTicker?.let(handler::post)
                } catch (failure: Throwable) {
                    finishWithFailure(failure, cancelTransformer = true)
                }
            }
        }
    }
}
