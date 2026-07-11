package com.example.compilationmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CheckpointProfilesTest {
    @Test
    fun requestedCheckpointOptionsAreRestoredInOrder() {
        val profiles = compilationScanProfiles()
        val checkpoints = profiles.filter { it.label.endsWith("checkpoints") }

        assertEquals("Fast change-map (500ms)", profiles.first().label)
        assertEquals(
            listOf("3-minute checkpoints", "1-minute checkpoints", "5-minute checkpoints"),
            checkpoints.map { it.label }
        )
        assertEquals(listOf(180_000L, 60_000L, 300_000L), checkpoints.map { it.frameStepMs })
        assertTrue(checkpoints.all { it.mode == ScanMode.StableCheckpoint })
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
