package com.example.compilationmaker

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoCompilationEndToEndTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule val intentsRule = IntentsRule()

    @Test
    fun oneMinuteCheckpointFlowExportsVideoA() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = requireNotNull(findVideoA()) { "Video A is not available in MediaStore" }
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(
                Activity.RESULT_OK,
                Intent().apply {
                    data = source
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.selectButton)).perform(click())
            onView(withId(R.id.scanSpeedPicker)).perform(scrollTo(), click())
            onData(allOf(instanceOf(String::class.java), `is`("1-minute checkpoints"))).perform(click())
            onView(withId(R.id.processButton)).perform(scrollTo(), click())

            val deadline = SystemClock.elapsedRealtime() + 10 * 60_000L
            var record: CompilationJobRecord? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                record = CompilationJobStore(context).load()
                if (record?.state?.isTerminal == true) break
                SystemClock.sleep(1_000L)
            }
            val finished = requireNotNull(record)
            assertEquals(finished.errorMessage, CompilationPipelineState.SUCCEEDED, finished.state)
            val output = File(finished.outputPath)
            assertTrue("Expected verified output file", output.isFile)
            assertTrue("Expected non-empty output", output.length() > 0L)
            output.copyTo(
                File("/sdcard/Download/compilation_output_A_${System.currentTimeMillis()}.mp4")
            )
            assertEquals("Video A must produce ten clips", 10, finished.clipCount)
        }
    }

    private fun findVideoA(): Uri? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME}=?",
            arrayOf("compilation_test_video_A.mp4"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString())
            }
        }
        return null
    }
}
