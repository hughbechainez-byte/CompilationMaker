package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CheckpointProfilesTest {
    @Test
    fun monotonicTurboIsTheDefaultCanonicalPtsProfile() {
        val profiles = compilationScanProfiles()
        val canonical = profiles.filter { it.mode == ScanMode.StableCheckpoint }

        assertEquals(
            listOf(
                "Medium-risk pixel scanner (monotonic 3m)",
                "Low-risk pixel scanner (FAST 30s)",
                "Medium-risk pixel scanner (quick 5m experimental)",
                "Canonical Balanced PTS (10s)",
                "Canonical Precise PTS (3s)"
            ),
            canonical.map { it.label }
        )
        assertEquals(listOf(180_000L, 30_000L, 300_000L, 10_000L, 3_000L), canonical.map { it.frameStepMs })
        assertEquals(
            listOf("MONOTONIC_3_MIN", "FAST", "QUICK_5_MIN", "BALANCED", "PRECISE"),
            canonical.map { it.scannerProfileId }
        )
        assertTrue(canonical.all { it.scannerProfileId != null })
    }

    @Test
    fun scanIntervalControlsAreVisibleInTheAppLayout() {
        val layout = locateLayout()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(layout)

        listOf("scanIntervalLabel", "scanSpeedPicker").forEach { id ->
            val node = (0 until document.getElementsByTagName("*").length)
                .map { document.getElementsByTagName("*").item(it) }
                .first { it.attributes?.getNamedItemNS(ANDROID_NS, "id")?.nodeValue == "@+id/$id" }
            assertFalse(node.attributes?.getNamedItemNS(ANDROID_NS, "visibility")?.nodeValue == "gone")
        }
    }

    private fun locateLayout(): File {
        return listOf(
            File("src/main/res/layout/activity_main.xml"),
            File("app/src/main/res/layout/activity_main.xml")
        ).first(File::isFile)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
