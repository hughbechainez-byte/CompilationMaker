package com.example.compilationmaker

import org.junit.Assert.assertTrue
import org.junit.Test

class CompiledVideoDetectorTest {
    @Test
    fun matchesCompilationMakerOutputToOriginalByStemAndDuration() {
        assertTrue(compiledOriginalNameMatch("travel.mp4", "CompilationMaker_travel_123456789.mp4"))
    }

    @Test
    fun doesNotSuggestUnrelatedVideos() {
        assertTrue(!compiledOriginalNameMatch("travel.mp4", "CompilationMaker_other.mp4"))
    }
}
