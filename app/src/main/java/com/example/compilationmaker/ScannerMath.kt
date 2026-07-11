package com.example.compilationmaker

import kotlin.math.max

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
