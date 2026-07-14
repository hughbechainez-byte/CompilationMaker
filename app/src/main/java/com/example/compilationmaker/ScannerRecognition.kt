package com.example.compilationmaker

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

data class DigitRecognition(
    val value: Int?,
    val rawText: String,
    val confidence: Float,
    val branch: String,
    val status: DigitRecognitionStatus = when {
        value != null -> DigitRecognitionStatus.PARSED
        rawText.isBlank() -> DigitRecognitionStatus.NO_TEXT
        else -> DigitRecognitionStatus.NO_VALID_INTEGER
    }
)

enum class DigitRecognitionStatus { PARSED, NO_TEXT, NO_VALID_INTEGER, TIMEOUT, ML_KIT_FAILURE }

/** Centralized, bounded confirmation timeouts. Overall work scales with candidates but stays capped. */
internal object ConfirmationTimeoutPolicy {
    const val FRAME_EXTRACTION_MS = 3_000L
    const val OCR_ATTEMPT_MS = 3_500L
    const val CANDIDATE_MS = 16_000L
    const val OVERALL_BASE_MS = 8_000L
    const val OVERALL_PER_CANDIDATE_MS = 11_000L
    const val OVERALL_CAP_MS = 180_000L

    fun overallMs(candidateCount: Int): Long =
        (OVERALL_BASE_MS + candidateCount.coerceAtLeast(0) * OVERALL_PER_CANDIDATE_MS)
            .coerceAtMost(OVERALL_CAP_MS)
}

internal object OcrPreparationPolicy {
    const val MIN_RECOGNITION_SIDE_PX = 128
    const val MAX_RECOGNITION_SIDE_PX = 512
    const val ROI_PADDING_FRACTION = 0.12f

    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val minScale = max(
            MIN_RECOGNITION_SIDE_PX.toFloat() / width,
            MIN_RECOGNITION_SIDE_PX.toFloat() / height
        )
        val maxScale = min(
            MAX_RECOGNITION_SIDE_PX.toFloat() / width,
            MAX_RECOGNITION_SIDE_PX.toFloat() / height
        )
        val scale = when {
            width < MIN_RECOGNITION_SIDE_PX || height < MIN_RECOGNITION_SIDE_PX -> minScale
            width > MAX_RECOGNITION_SIDE_PX || height > MAX_RECOGNITION_SIDE_PX -> maxScale
            else -> 1f
        }
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }
}

internal fun shouldTryNextOcrVariant(attemptIndex: Int, recognition: DigitRecognition): Boolean = when {
    recognition.value != null && recognition.confidence >= 0.95f -> false
    recognition.status == DigitRecognitionStatus.TIMEOUT -> false
    recognition.status == DigitRecognitionStatus.ML_KIT_FAILURE -> false
    attemptIndex >= 3 -> false
    else -> true
}

internal fun mlKitFailureIsCandidateLocal(errorCode: Int): Boolean = errorCode == 13 || errorCode >= 0

interface DigitRecognizer : AutoCloseable {
    val name: String
    suspend fun recognize(bitmap: Bitmap, branch: String = "raw"): DigitRecognition
}

class MlKitDigitRecognizer(context: Context) : DigitRecognizer {
    private val appContext = context.applicationContext
    private val gate = Mutex()
    private var recognizer = createRecognizer()
    private var closed = false
    override val name: String = "mlkit-text-recognition"

    override suspend fun recognize(bitmap: Bitmap, branch: String): DigitRecognition {
        require(!bitmap.isRecycled) { "OCR bitmap is recycled" }
        require(bitmap.width > 0 && bitmap.height > 0) { "OCR bitmap is empty" }
        return gate.withLock {
            try {
                recognizeOnce(bitmap, branch, attempt = 1)
            } catch (timeout: OcrRecognitionTimeoutException) {
                AppLog.w(appContext, "OCR", "[ocr] attempt timed out branch=$branch; candidate will continue without retry", timeout)
                DigitRecognition(null, "", 0f, branch, DigitRecognitionStatus.TIMEOUT)
            } catch (mlKit: MlKitException) {
                AppLog.w(appContext, "OCR", "[ocr] ML Kit failure is candidate-local branch=$branch errorCode=${mlKit.errorCode}", mlKit)
                DigitRecognition(null, "", 0f, branch, DigitRecognitionStatus.ML_KIT_FAILURE)
            }
        }
    }

    private suspend fun recognizeOnce(bitmap: Bitmap, branch: String, attempt: Int): DigitRecognition {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = try {
            withTimeout(ConfirmationTimeoutPolicy.OCR_ATTEMPT_MS) {
                val started = android.os.SystemClock.elapsedRealtime()
                AppLog.d(appContext, "OCR", "[ocr] bitmap dimensions=${bitmap.width}x${bitmap.height} config=${bitmap.config} branch=$branch attempt=$attempt")
                val task = recognizer.process(image)
                AppLog.d(appContext, "OCR", "[ocr] process submitted branch=$branch attempt=$attempt")
                awaitTask(task, started, branch, attempt)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw OcrRecognitionTimeoutException(branch, timeout)
        }
        val text = result.text.trim()
        // Confirmation accepts only one complete digit line; incidental overlay text must not
        // be interpreted as a transition number.
        val matchedLine = text.lineSequence().map(String::trim).firstOrNull { it.matches(Regex("^[0-9]{1,3}$")) }
        val value = matchedLine?.toIntOrNull()
        val confidence = when {
            value == null -> 0f
            matchedLine.length <= 2 && text.isNotBlank() -> 0.95f
            matchedLine.length <= 3 -> 0.88f
            else -> 0.72f
        }
        val status = when {
            text.isBlank() -> DigitRecognitionStatus.NO_TEXT
            value == null -> DigitRecognitionStatus.NO_VALID_INTEGER
            else -> DigitRecognitionStatus.PARSED
        }
        AppLog.d(appContext, "OCR", "[ocr] completed branch=$branch status=$status parsed=${value ?: "none"} text=${text.take(24)}")
        return DigitRecognition(value, text, confidence, branch, status)
    }

    private suspend fun awaitTask(task: com.google.android.gms.tasks.Task<Text>, started: Long, branch: String, attempt: Int): Text =
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { result ->
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val disposition = if (result.text.isBlank()) "no-text" else "text-returned"
                AppLog.d(appContext, "OCR", "[ocr] task completed in ${elapsed} ms branch=$branch attempt=$attempt disposition=$disposition")
                if (continuation.isActive) continuation.resume(result)
            }.addOnFailureListener { failure ->
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                val detail = mlKitFailureDetail(failure)
                AppLog.e(appContext, "OCR", "[ocr] task failed in ${elapsed} ms branch=$branch attempt=$attempt exception=$detail", failure)
                if (continuation.isActive) continuation.resumeWithException(failure)
            }.addOnCanceledListener {
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                AppLog.w(appContext, "OCR", "[ocr] task cancelled in ${elapsed} ms branch=$branch attempt=$attempt")
                if (continuation.isActive) continuation.cancel(CancellationException("ML Kit OCR task cancelled"))
            }
            continuation.invokeOnCancellation {
                AppLog.w(appContext, "OCR", "[ocr] task abandoned branch=$branch attempt=$attempt")
            }
        }

    private fun createRecognizer(): com.google.mlkit.vision.text.TextRecognizer {
        AppLog.d(appContext, "OCR", "[ocr] recognizer created")
        AppLog.d(appContext, "OCR", "[ocr] dependency mode=bundled")
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun mlKitFailureDetail(failure: Exception): String {
        val mlKit = failure as? MlKitException
        return if (mlKit != null) {
            "${mlKit::class.java.simpleName}(errorCode=${mlKit.errorCode}, message=${mlKit.message}, cause=${mlKit.cause})"
        } else {
            "${failure::class.java.name}(message=${failure.message}, cause=${failure.cause})"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { recognizer.close() }
            .onFailure { AppLog.w(appContext, "OCR", "[ocr] recognizer close failed", it) }
        AppLog.d(appContext, "OCR", "[ocr] recognizer closed")
    }
}

internal class OcrRecognitionTimeoutException(branch: String, cause: Throwable) :
    IllegalStateException("OCR $branch recognition timed out", cause)

fun extractDigitCandidates(rawText: String): List<Int> {
    if (rawText.isBlank()) return emptyList()
    return Regex("[-+]?[0-9]+")
        .findAll(rawText)
        .mapNotNull { it.value.toIntOrNull() }
        .toList()
}

fun binarizeForDigitRecognition(source: Bitmap, invert: Boolean = false): Bitmap {
    val scaled = if (source.width > 360 || source.height > 360) {
        val ratio = min(360f / max(1, source.width).toFloat(), 360f / max(1, source.height).toFloat())
        val targetW = max(32, (source.width * ratio).toInt())
        val targetH = max(32, (source.height * ratio).toInt())
        Bitmap.createScaledBitmap(source, targetW, targetH, true)
    } else {
        source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
    }

    val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    var sum = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        sum += (r * 30 + g * 59 + b * 11) / 100
    }
    val avg = if (pixels.isNotEmpty()) sum / pixels.size else 0
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        var luma = (r * 30 + g * 59 + b * 11) / 100
        luma = if (invert) 255 - luma else luma
        val value = if (luma >= avg) 0xffffffff.toInt() else 0xff000000.toInt()
        pixels[i] = value
    }
    output.setPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    if (scaled !== source) {
        scaled.recycle()
    }
    return output
}

fun grayscaleBitmap(source: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        val luma = (r * 30 + g * 59 + b * 11) / 100
        pixels[i] = 0xff000000.toInt() or (luma shl 16) or (luma shl 8) or luma
    }
    output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    return output
}
