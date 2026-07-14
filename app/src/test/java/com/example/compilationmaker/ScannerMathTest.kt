package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerMathTest {
    @Test
    fun rotationTransformsRoiAsExpected() {
        val saved = ScanWindow(0.10f, 0.20f, 0.30f, 0.40f)
        val rot90 = rotateScanWindowForCurrentRotation(saved, 0, 90)
        val rot180 = rotateScanWindowForCurrentRotation(saved, 0, 180)
        val rot270 = rotateScanWindowForCurrentRotation(saved, 0, 270)

        assertEquals(0.20f, rot90.xPercent, 0.0001f)
        assertEquals(0.60f, rot90.yPercent, 0.0001f)
        assertEquals(0.40f, rot90.widthPercent, 0.0001f)
        assertEquals(0.30f, rot90.heightPercent, 0.0001f)

        assertEquals(0.60f, rot180.xPercent, 0.0001f)
        assertEquals(0.40f, rot180.yPercent, 0.0001f)
        assertEquals(0.30f, rot180.widthPercent, 0.0001f)
        assertEquals(0.40f, rot180.heightPercent, 0.0001f)

        assertEquals(0.40f, rot270.xPercent, 0.0001f)
        assertEquals(0.10f, rot270.yPercent, 0.0001f)
        assertEquals(0.40f, rot270.widthPercent, 0.0001f)
        assertEquals(0.30f, rot270.heightPercent, 0.0001f)
    }

    @Test
    fun mergeSegmentWindowsCollapsesOverlapAndGap() {
        val merged = mergeSegmentWindows(
            listOf(
                SegmentWindow(0L, 10_000L),
                SegmentWindow(9_500L, 15_000L),
                SegmentWindow(40_000L, 50_000L)
            ),
            maxGapMs = 1_000L
        )

        assertEquals(2, merged.size)
        assertEquals(SegmentWindow(0L, 15_000L), merged[0])
        assertEquals(SegmentWindow(40_000L, 50_000L), merged[1])
    }

    @Test
    fun requestedSegmentsClampToDuration() {
        val segment = buildRequestedSegment(boundaryMs = 25_000L, durationMs = 40_000L)
        assertEquals(15_000L, segment.startMs)
        assertEquals(40_000L, segment.endMs)
    }

    @Test
    fun sourceDurationDoesNotCollapseToProcessingTime() {
        val resolved = resolveSourceDurationMs(3_600_000L, 12_345L)
        assertEquals(3_600_000L, resolved)
    }

    @Test
    fun transitionClassificationDistinguishesSequentialAndNonSequential() {
        val first = classifyTransition(null, 1)
        val sequential = classifyTransition(1, 2)
        val nonSequential = classifyTransition(4, 7)
        val unchanged = classifyTransition(5, 5)

        assertTrue(first.accepted)
        assertTrue(first.sequential)
        assertTrue(sequential.accepted)
        assertTrue(sequential.sequential)
        assertTrue(nonSequential.accepted)
        assertFalse(nonSequential.sequential)
        assertFalse(unchanged.accepted)
    }

    @Test
    fun hysteresisOpensAndClosesAfterConsecutiveSamples() {
        var decision = HysteresisDecision(open = false, consecutiveChanged = 0, consecutiveStable = 0)
        decision = updateHysteresisDecision(decision, changed = true)
        assertFalse(decision.open)
        decision = updateHysteresisDecision(decision, changed = true)
        assertTrue(decision.open)
        decision = updateHysteresisDecision(decision, changed = false)
        assertTrue(decision.open)
        decision = updateHysteresisDecision(decision, changed = false)
        assertFalse(decision.open)
    }

    @Test
    fun coarseCheckpointsAreDirectAndNeverTreatZeroAsAChange() {
        val checkpoints = generateCheckpointTimestamps(4_782_000L, 60_000L)

        assertEquals(81, checkpoints.size)
        assertEquals(0L, checkpoints.first())
        assertEquals(60_000L, checkpoints[1])
        assertEquals(4_782_000L, checkpoints.last())
        assertTrue(checkpoints.zipWithNext().all { (a, b) -> b - a == 60_000L || b == 4_782_000L })
        assertFalse(classifyTransition(null, null).accepted)
    }

    @Test
    fun videoAUsesExactlySixtyOneCheckpointsWithoutRedundantTailSeek() {
        val checkpoints = generateCheckpointTimestamps(3_600_500L, 60_000L)

        assertEquals(61, checkpoints.size)
        assertEquals(0L, checkpoints.first())
        assertEquals(3_600_000L, checkpoints.last())
    }

    @Test
    fun visualOnlyIntervalsReceiveOneProbeWhileSemanticEndpointsMayBisect() {
        assertEquals(1, checkpointInvestigationProbeLimit(false, false, null, null))
        assertEquals(1, checkpointInvestigationProbeLimit(true, false, 4, null))
        assertEquals(15, checkpointInvestigationProbeLimit(true, true, 4, 5))
    }

    @Test
    fun supportedTransitionStatesIncludeNoNumberToOneAndOneToTwo() {
        assertEquals("null -> 1", classifyTransition(null, 1).label)
        assertTrue(classifyTransition(null, 1).accepted)
        assertEquals("1 -> 2", classifyTransition(1, 2).label)
        assertTrue(classifyTransition(1, 2).accepted)
        assertFalse(classifyTransition(1, 1).accepted)
    }
}
