// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.group

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import phantom.android.R
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GroupChatCopyTest {
    @Test
    fun groupCountsAndRecordingTimeFormatWithTheirArguments() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals("1 member", resources.getQuantityString(R.plurals.group_members, 1, 1))
        assertEquals("2 members", resources.getQuantityString(R.plurals.group_members, 2, 2))
        assertEquals("Add Members (1 selected)", resources.getQuantityString(R.plurals.create_group_selected_members, 1, 1))
        assertEquals("Recording 5:03", resources.getString(R.string.group_recording, 5, 3))
    }

    @Test
    fun partialAudioCopyReportsSubmissionRatherThanDelivery() {
        val resources = RuntimeEnvironment.getApplication().resources

        assertEquals(
            "Submitted to transport for 2 of 3 recipients",
            resources.getString(R.string.group_voice_submitted, 2, 3),
        )
    }
}
