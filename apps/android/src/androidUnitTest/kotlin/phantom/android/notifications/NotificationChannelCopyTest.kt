// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import phantom.android.R
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationChannelCopyTest {
    @Test
    fun existingChannelIdUsesResourceBackedCopy() {
        val context = RuntimeEnvironment.getApplication()
        PhantomNotificationManager.createChannel(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(PhantomNotificationManager.CHANNEL_ID)
        assertEquals("phantom_messages", channel.id)
        assertEquals(context.getString(R.string.notification_messages_channel_name), channel.name.toString())
        assertEquals(context.getString(R.string.notification_messages_channel_description), channel.description)
    }
}
