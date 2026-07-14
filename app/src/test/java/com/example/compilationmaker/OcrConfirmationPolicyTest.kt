package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrConfirmationPolicyTest {
    @Test
    fun elevenCandidatesReceiveMoreThanLegacyFixedBudgetButRemainCapped() {
        assertTrue(ConfirmationTimeoutPolicy.overallMs(11) > 35_000L)
        assertEquals(
            ConfirmationTimeoutPolicy.OVERALL_CAP_MS,
            ConfirmationTimeoutPolicy.overallMs(10_000)
        )
    }

    @Test
    fun laterCandidateTimeoutCannotEraseEarlierConfirmations() {
        val ledger = IncrementalTransitionLedger(dedupeMs = 900L)
        ledger.confirm(10_000L)
        ledger.confirm(40_000L)
        // Candidate three times out and therefore never calls confirm.
        assertEquals(listOf(10_000L, 40_000L), ledger.snapshot())
    }

    @Test
    fun smallRoiIsUpscaledWithoutChangingAspectRatio() {
        val (width, height) = OcrPreparationPolicy.targetSize(32, 48)
        assertTrue(width >= OcrPreparationPolicy.MIN_RECOGNITION_SIDE_PX)
        assertTrue(height >= OcrPreparationPolicy.MIN_RECOGNITION_SIDE_PX)
        assertEquals(32f / 48f, width.toFloat() / height.toFloat(), 0.01f)
    }

    @Test
    fun emptyAndMalformedTextHaveDistinctNormalResults() {
        assertEquals(DigitRecognitionStatus.NO_TEXT, DigitRecognition(null, "", 0f, "raw").status)
        assertEquals(DigitRecognitionStatus.NO_VALID_INTEGER, DigitRecognition(null, "score", 0f, "raw").status)
    }

    @Test
    fun preprocessingStopsAfterValidResultOrTimeout() {
        assertFalse(shouldTryNextOcrVariant(0, DigitRecognition(7, "7", 0.95f, "raw")))
        assertFalse(shouldTryNextOcrVariant(0, DigitRecognition(null, "", 0f, "raw", DigitRecognitionStatus.TIMEOUT)))
        assertTrue(shouldTryNextOcrVariant(0, DigitRecognition(null, "", 0f, "raw")))
    }

    @Test
    fun timedOutAttemptIsTerminalForThatSampleInsteadOfRetryingInCancelledContext() {
        val timeout = DigitRecognition(null, "", 0f, "raw", DigitRecognitionStatus.TIMEOUT)
        assertFalse(shouldTryNextOcrVariant(0, timeout))
    }

    @Test
    fun visionKitErrorThirteenRemainsCandidateLocal() {
        assertTrue(mlKitFailureIsCandidateLocal(13))
    }

    @Test
    fun visualFallbackUsesOnlyStrongVisualChangesAndDeduplicatesNeighbors() {
        val selected = selectVisualFallbackTransitions(
            listOf(
                VisualFallbackCandidate(20_000L, 15f, true),
                VisualFallbackCandidate(20_400L, 18f, true),
                VisualFallbackCandidate(60_000L, 4f, true),
                VisualFallbackCandidate(90_000L, 20f, false)
            ),
            visualThreshold = 10f,
            dedupeMs = 900L
        )
        assertEquals(listOf(20_000L), selected)
    }

    @Test
    fun fallbackClipBoundariesRemainClampedToTenBeforeAndThirtyAfter() {
        assertEquals(SegmentWindow(0L, 35_000L), buildRequestedSegment(5_000L, 100_000L))
        assertEquals(SegmentWindow(85_000L, 100_000L), buildRequestedSegment(95_000L, 100_000L))
    }

    @Test
    fun totalJobProgressNeverMovesBackwardAcrossPhases() {
        assertEquals(100, monotonicProgressPercent(100, 44))
        assertEquals(55, monotonicProgressPercent(44, 55))
    }

    @Test
    fun fiveSampleTimelineRequiresMajorityWithoutTwoCompetingVotes() {
        val stable = classifyStableNumberState(
            listOf(
                StableStateVote(0L, 4), StableStateVote(500L, 4), StableStateVote(1_000L, 4),
                StableStateVote(1_500L, null), StableStateVote(2_000L, null)
            )
        )
        val unstable = classifyStableNumberState(
            listOf(
                StableStateVote(0L, 4), StableStateVote(500L, 4), StableStateVote(1_000L, 4),
                StableStateVote(1_500L, 9), StableStateVote(2_000L, 9)
            )
        )
        assertTrue(stable.stable)
        assertEquals(4, stable.value)
        assertFalse(unstable.stable)
    }

    @Test
    fun adaptiveVisualThresholdResistsIsolatedNoise() {
        val threshold = adaptiveVisualThreshold(listOf(1f, 1.1f, 0.9f, 1f, 14f))
        assertTrue(threshold.threshold >= 8f)
        assertTrue(threshold.threshold < 14f)
    }

    @Test
    fun timedOutOrInvalidNullVotesCannotBecomeStableNull() {
        val unstable = classifyStableNumberState(
            listOf(
                StableStateVote(0L, null, 0L, "OCR_TIMEOUT"),
                StableStateVote(500L, null, 500L, "INVALID_FRAME"),
                StableStateVote(1_000L, null, 1_000L, "OCR_TIMEOUT"),
                StableStateVote(1_500L, null, 1_500L, "NO_TRANSITION"),
                StableStateVote(2_000L, null, 2_000L, "NO_TRANSITION")
            )
        )
        assertFalse(unstable.stable)
    }
}
