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
    val branch: String
)

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
                AppLog.w(appContext, "OCR", "[ocr] timeout branch=$branch; recreating recognizer for one retry", timeout)
                recreateAfterTimeout()
                recognizeOnce(bitmap, branch, attempt = 2)
            }
        }
    }

    private suspend fun recognizeOnce(bitmap: Bitmap, branch: String, attempt: Int): DigitRecognition {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = try {
            withTimeout(OCR_RECOGNITION_TIMEOUT_MS) {
                val started = android.os.SystemClock.elapsedRealtime()
                AppLog.d(appContext, "OCR", "[ocr] bitmap dimensions=${bitmap.width}x${bitmap.height} config=${bitmap.config} branch=$branch attempt=$attempt")
                val task = recognizer.process(image)
                AppLog.d(appContext, "OCR", "[ocr] process submitted branch=$branch attempt=$attempt")
                awaitTask(task, started, branch, attempt)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw OcrRecognitionTimeoutException(branch, timeout)
        }
        val text = result.text.replace("\n", " ").trim()
        val match = Regex("[-+]?[0-9]+").find(text)
        val value = match?.value?.toIntOrNull()
        val confidence = when {
            value == null -> 0f
            match.value.length <= 2 && text.isNotBlank() -> 0.95f
            match.value.length <= 3 -> 0.88f
            else -> 0.72f
        }
        return DigitRecognition(value, text, confidence, branch)
    }

    private suspend fun awaitTask(task: com.google.android.gms.tasks.Task<Text>, started: Long, branch: String, attempt: Int): Text =
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { result ->
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                AppLog.d(appContext, "OCR", "[ocr] task success in ${elapsed} ms branch=$branch attempt=$attempt")
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

    private fun recreateAfterTimeout() {
        if (closed) return
        runCatching { recognizer.close() }
            .onFailure { AppLog.w(appContext, "OCR", "[ocr] recognizer close after timeout failed", it) }
        AppLog.d(appContext, "OCR", "[ocr] recognizer closed")
        recognizer = createRecognizer()
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

    private companion object {
        const val OCR_RECOGNITION_TIMEOUT_MS = 5_000L
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
