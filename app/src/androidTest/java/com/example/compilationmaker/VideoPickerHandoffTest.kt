package com.example.compilationmaker

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPickerHandoffTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule val intentsRule = IntentsRule()

    @Test
    fun openDocumentResultRestoresTheSelectedSourceIntoDurableDraftState() {
        val source = Uri.parse("content://media/external/video/media/987654")
        val result = Intent().apply {
            data = source
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, result))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.selectButton)).perform(click())
            scenario.onActivity { activity ->
                val record = requireNotNull(CompilationJobStore(activity).load())
                check(record.sourceUri == source.toString())
                check(record.state == CompilationPipelineState.VIDEO_SELECTED)
            }
        }
    }
}
