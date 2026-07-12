package com.example.compilationmaker

import androidx.work.WorkInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CompilationJobStorePolicyTest {
    @Test
    fun allWorkManagerActiveStatesIncludingBlockedPreventRoiInitialization() {
        listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED).forEach { state ->
            assertTrue("$state must be active", isActiveWorkManagerState(state))
            assertFalse(
                "$state must block ROI initialization",
                shouldInitializeRoi(CompilationPipelineState.READY, state)
            )
        }
    }

    @Test
    fun everyActivePipelineStatePreventsRoiInitializationEvenWhenWorkInfoIsMissing() {
        val activeStates = CompilationPipelineState.values().filter { it.isActive }

        assertEquals(
            listOf(
                CompilationPipelineState.QUEUED,
                CompilationPipelineState.PREPARING,
                CompilationPipelineState.COARSE_SCAN,
                CompilationPipelineState.REFINING,
                CompilationPipelineState.FINALIZING,
                CompilationPipelineState.BUILDING_CLIP_PLAN,
                CompilationPipelineState.EXPORTING,
                CompilationPipelineState.VERIFYING
            ),
            activeStates
        )
        activeStates.forEach { state ->
            assertFalse(shouldInitializeRoi(state, null))
        }
        assertTrue(shouldInitializeRoi(CompilationPipelineState.READY, null))
    }

    @Test
    fun terminalJobScreensAreRestoredInsteadOfBeingOverwrittenByRoi() {
        listOf(
            CompilationPipelineState.SUCCEEDED,
            CompilationPipelineState.FAILED,
            CompilationPipelineState.CANCELLED,
            CompilationPipelineState.NO_RESULTS
        ).forEach { state ->
            assertFalse("$state must retain its terminal screen", shouldInitializeRoi(state, null))
        }
    }

    @Test
    fun unrelatedUiChannelsCannotWriteCompilationProgress() {
        assertTrue(shouldWriteCompilationProgress(CompilationUiChannel.COMPILATION))
        listOf(
            CompilationUiChannel.ROI,
            CompilationUiChannel.UPDATE,
            CompilationUiChannel.LOG,
            CompilationUiChannel.TRANSIENT
        ).forEach { channel ->
            assertFalse("$channel overwrote compilation progress", shouldWriteCompilationProgress(channel))
        }
    }

    @Test
    fun phaseMappingUsesExplicitLifecycleStatesInsteadOfPercentages() {
        assertEquals(CompilationPipelineState.COARSE_SCAN, pipelineStateForProgressPhase("stable scan"))
        assertEquals(CompilationPipelineState.REFINING, pipelineStateForProgressPhase("number confirmation"))
        assertEquals(CompilationPipelineState.FINALIZING, pipelineStateForProgressPhase("finalizing"))
        assertEquals(CompilationPipelineState.BUILDING_CLIP_PLAN, pipelineStateForProgressPhase("clip plan"))
        assertEquals(CompilationPipelineState.EXPORTING, pipelineStateForProgressPhase("muxing final output"))
        assertEquals(CompilationPipelineState.VERIFYING, pipelineStateForProgressPhase("verify output"))
    }

    @Test
    fun recordSerializationRestoresUuidSourceStateAndCompletedOutput() {
        val workId = UUID.randomUUID().toString()
        val original = CompilationJobRecord(
            workId = workId,
            uniqueWorkName = CompilationJobContract.UNIQUE_WORK_NAME,
            sourceUri = "content://videos/source%201.mp4",
            expectedOutputPath = "/cache/expected.mp4",
            state = CompilationPipelineState.SUCCEEDED,
            stage = "completed",
            progressPercent = 100,
            progressMessage = "Compilation complete",
            createdAtMs = 1_000L,
            updatedAtMs = 2_000L,
            completedAtMs = 3_000L,
            outputUri = "content://compilations/output%201.mp4",
            outputPath = "/cache/output 1.mp4",
            outputSizeBytes = 9_876L,
            outputDurationMs = 42_000L,
            candidateCount = 12,
            clipCount = 5,
            previewAvailable = true,
            settings = CompilationJobSettings(
                scanWindowJson = "{\"xPercent\":0.1}",
                scanModeOrdinal = 1,
                checkpointIntervalMs = 180_000L,
                experimentalDownscale = 32,
                qualityOrdinal = 2,
                formatOrdinal = 0,
                transitionStyleOrdinal = 1,
                videoRotation = 90
            )
        )

        val restored = CompilationJobRecord.fromJson(JSONObject(original.toJson().toString()))

        assertNotNull(restored)
        assertEquals(original, restored)
        assertEquals(workId, restored?.workId)
        assertEquals(original.outputUri, restored?.outputUri)
        assertEquals(original.outputSizeBytes, restored?.outputSizeBytes)
        assertEquals(original.outputDurationMs, restored?.outputDurationMs)
        assertEquals(12, restored?.candidateCount)
        assertEquals(5, restored?.clipCount)
        assertTrue(restored?.previewAvailable == true)
    }
}
