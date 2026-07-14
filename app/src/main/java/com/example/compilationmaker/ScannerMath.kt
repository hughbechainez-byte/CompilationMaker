package com.example.compilationmaker

import kotlin.math.max

data class VisualFallbackCandidate(val timestampMs: Long, val peakScore: Float, val visualChange: Boolean)

data class StableStateVote(
    val timestampMs: Long,
    val value: Int?,
    /** Actual decoded-frame time, retained even when the requested seek is not exact. */
    val decodedTimestampMs: Long? = null,
    /** OCR/decode disposition retained as evidence rather than being collapsed into null. */
    val status: String = if (value == null) "NO_TRANSITION" else "CONFIRMED_TRANSITION"
)

data class StableNumberState(
    val value: Int?,
    val stable: Boolean,
    val votes: List<StableStateVote>
)

data class NumberStatePoint(
    val timeMs: Long,
    val value: Int?,
    val stable: Boolean
)

data class SemanticStateInterval(
    val startMs: Long,
    val endMs: Long,
    val fromNumber: Int?,
    val toNumber: Int
)

data class StateIntervalInvestigation(
    val intervals: List<SemanticStateInterval>,
    val probes: Int
)

/**
 * Recursively investigates a complete coarse interval.  Unstable samples remain local evidence;
 * they do not cancel sibling branches or manufacture transitions.
 */
suspend fun investigateStateInterval(
    left: NumberStatePoint,
    right: NumberStatePoint,
    minLeafMs: Long = 2_000L,
    maxDepth: Int = 6,
    maxProbes: Int = 15,
    sample: suspend (Long) -> NumberStatePoint
): StateIntervalInvestigation {
    val leaves = ArrayList<SemanticStateInterval>()
    var probes = 0

    suspend fun recurse(a: NumberStatePoint, b: NumberStatePoint, depth: Int) {
        if (b.timeMs <= a.timeMs) return
        if (a.stable && b.stable && a.value == b.value) return

        val semantic = if (a.stable && b.stable) classifyTransition(a.value, b.value) else null
        val spanMs = b.timeMs - a.timeMs
        if (spanMs <= minLeafMs && a.stable && b.stable) {
            if (semantic?.sequential == true && b.value != null) {
                leaves += SemanticStateInterval(a.timeMs, b.timeMs, a.value, b.value)
            }
            return
        }
        if (depth >= maxDepth || probes >= maxProbes) {
            if (semantic?.sequential == true && b.value != null) {
                leaves += SemanticStateInterval(a.timeMs, b.timeMs, a.value, b.value)
            }
            return
        }

        val midpointMs = a.timeMs + spanMs / 2L
        if (midpointMs <= a.timeMs || midpointMs >= b.timeMs) return
        val midpoint = sample(midpointMs)
        probes++
        recurse(a, midpoint, depth + 1)
        recurse(midpoint, b, depth + 1)
    }

    recurse(left, right, 0)
    val unique = leaves.distinctBy { listOf(it.startMs, it.endMs, it.fromNumber, it.toNumber) }
        .sortedBy { it.startMs }
    return StateIntervalInvestigation(unique, probes)
}

/** Five-sample state voting used by the checkpoint timeline and deterministic tests. */
fun classifyStableNumberState(votes: List<StableStateVote>): StableNumberState {
    val numbers = votes.mapNotNull { it.value }
    val grouped = numbers.groupingBy { it }.eachCount()
    val winner = grouped.maxByOrNull { it.value }
    val competingVotes = grouped.filterKeys { it != winner?.key }.values.maxOrNull() ?: 0
    val stableNumber = winner?.takeIf { it.value >= 3 && competingVotes < 2 }?.key
    val validNullVotes = votes.count { it.value == null && it.status == "NO_TRANSITION" }
    val stableNull = stableNumber == null && validNullVotes >= 3 && grouped.values.none { it >= 2 }
    return StableNumberState(
        value = stableNumber,
        stable = stableNumber != null || stableNull,
        votes = votes
    )
}

data class AdaptiveVisualThreshold(val median: Float, val mad: Float, val threshold: Float)

fun adaptiveVisualThreshold(scores: List<Float>): AdaptiveVisualThreshold {
    if (scores.isEmpty()) return AdaptiveVisualThreshold(0f, 0f, 8f)
    val sorted = scores.sorted()
    val median = sorted[sorted.size / 2]
    val deviations = scores.map { kotlin.math.abs(it - median) }.sorted()
    val mad = deviations[deviations.size / 2]
    return AdaptiveVisualThreshold(median, mad, (median + 6f * mad).coerceIn(8f, 25f))
}

class IncrementalTransitionLedger(private val dedupeMs: Long) {
    private val confirmed = ArrayList<Long>()
    val size: Int get() = confirmed.size

    fun confirm(timestampMs: Long) {
        val previous = confirmed.lastOrNull()
        if (previous == null || timestampMs - previous > dedupeMs) {
            confirmed += timestampMs
        } else {
            confirmed[confirmed.lastIndex] = minOf(previous, timestampMs)
        }
    }

    fun snapshot(): List<Long> = confirmed.toList()
}

fun selectVisualFallbackTransitions(
    candidates: List<VisualFallbackCandidate>,
    visualThreshold: Float,
    dedupeMs: Long
): List<Long> {
    val qualified = candidates
        .filter { it.visualChange && it.peakScore >= visualThreshold * 1.15f }
        .sortedBy { it.timestampMs }
    val result = ArrayList<Long>()
    for (candidate in qualified) {
        val previous = result.lastOrNull()
        if (previous == null || candidate.timestampMs - previous > dedupeMs) result += candidate.timestampMs
    }
    return result
}

fun generateCheckpointTimestamps(durationMs: Long, intervalMs: Long): List<Long> {
    require(durationMs >= 0L)
    val step = intervalMs.coerceAtLeast(1L)
    val result = ArrayList<Long>()
    var cursor = 0L
    while (cursor < durationMs) {
        result += cursor
        val next = cursor + step
        if (next <= cursor) break
        cursor = next
    }
    val tailMs = durationMs - (result.lastOrNull() ?: 0L)
    // Avoid a redundant near-duration seek (Video A is 3,600,500 ms, so this keeps 61 points).
    if (result.isEmpty() || (result.last() != durationMs && tailMs > 1_000L)) result += durationMs
    return result
}

fun checkpointInvestigationProbeLimit(
    leftStable: Boolean,
    rightStable: Boolean,
    fromNumber: Int?,
    toNumber: Int?
): Int {
    val semanticEndpoints = leftStable && rightStable && fromNumber != toNumber
    return if (semanticEndpoints) 3 else 1
}

data class HysteresisDecision(
    val open: Boolean,
    val consecutiveChanged: Int,
    val consecutiveStable: Int
)

data class TransitionClassification(
    val accepted: Boolean,
    val sequential: Boolean,
    val label: String
)

fun rotateScanWindowForCurrentRotation(
    saved: ScanWindow,
    savedRotation: Int,
    currentRotation: Int
): ScanWindow {
    val from = ((savedRotation % 360) + 360) % 360
    val to = ((currentRotation % 360) + 360) % 360
    val rotated = when ((to - from + 360) % 360) {
        90 -> ScanWindow(saved.yPercent, 1f - saved.xPercent - saved.widthPercent, saved.heightPercent, saved.widthPercent)
        180 -> ScanWindow(1f - saved.xPercent - saved.widthPercent, 1f - saved.yPercent - saved.heightPercent, saved.widthPercent, saved.heightPercent)
        270 -> ScanWindow(1f - saved.yPercent - saved.heightPercent, saved.xPercent, saved.heightPercent, saved.widthPercent)
        else -> saved
    }
    return coerceScanWindow(rotated)
}

fun coerceScanWindow(window: ScanWindow): ScanWindow {
    val x = window.xPercent.coerceIn(0f, 1f)
    val y = window.yPercent.coerceIn(0f, 1f)
    val w = window.widthPercent.coerceIn(0.01f, max(0.01f, 1f - x))
    val h = window.heightPercent.coerceIn(0.01f, max(0.01f, 1f - y))
    return ScanWindow(x, y, w, h)
}

fun mergeSegmentWindows(input: List<SegmentWindow>, maxGapMs: Long): List<SegmentWindow> {
    if (input.isEmpty()) return emptyList()
    val merged = ArrayList<SegmentWindow>()
    val sorted = input.sortedBy { it.startMs }
    var current = sorted.first()
    for (index in 1 until sorted.size) {
        val next = sorted[index]
        current = if (next.startMs <= current.endMs + maxGapMs) {
            SegmentWindow(current.startMs, max(current.endMs, next.endMs))
        } else {
            merged.add(current)
            next
        }
    }
    merged.add(current)
    return merged
}

fun buildRequestedSegment(
    boundaryMs: Long,
    durationMs: Long,
    preRollMs: Long = 10_000L,
    postRollMs: Long = 30_000L
): SegmentWindow {
    val safeBoundary = boundaryMs.coerceAtLeast(0L)
    val safeDuration = durationMs.coerceAtLeast(0L)
    return SegmentWindow(
        startMs = max(0L, safeBoundary - preRollMs),
        endMs = minOf(safeDuration, safeBoundary + postRollMs)
    )
}

fun classifyTransition(before: Int?, after: Int?): TransitionClassification {
    return when {
        after == null -> TransitionClassification(false, false, "unconfirmed")
        before == null && after == 1 -> TransitionClassification(true, true, "null -> 1")
        before == null -> TransitionClassification(true, false, "unknown -> $after")
        before + 1 == after -> TransitionClassification(true, true, "$before -> $after")
        before == after -> TransitionClassification(false, false, "$before unchanged")
        else -> TransitionClassification(true, false, "$before -> $after")
    }
}

@Suppress("UNUSED_PARAMETER")
fun resolveSourceDurationMs(sourceDurationMs: Long, processingDurationMs: Long): Long {
    return sourceDurationMs.coerceAtLeast(0L)
}

fun updateHysteresisDecision(
    previous: HysteresisDecision,
    changed: Boolean,
    openThreshold: Int = 2,
    closeThreshold: Int = 2
): HysteresisDecision {
    val nextChanged = if (changed) previous.consecutiveChanged + 1 else 0
    val nextStable = if (changed) 0 else previous.consecutiveStable + 1
    val open = when {
        previous.open && nextStable >= closeThreshold -> false
        !previous.open && nextChanged >= openThreshold -> true
        else -> previous.open
    }
    return HysteresisDecision(open, nextChanged, nextStable)
}
