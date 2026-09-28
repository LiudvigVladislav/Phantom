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
    @Config(sdk = [32])
    fun localeChangeRenamesExistingChannelWithoutResettingItsSettings() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = PhantomNotificationManager.CHANNEL_ID
        manager.createNotificationChannel(android.app.NotificationChannel(id, "Custom", NotificationManager.IMPORTANCE_LOW))
        phantom.android.locale.AppLanguageStore.set(context, phantom.android.locale.AppLanguage.RUSSIAN)
        PhantomNotificationManager.createChannel(context)
        assertEquals("Сообщения", manager.getNotificationChannel(id).name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(id).importance)
        phantom.android.locale.AppLanguageStore.set(context, phantom.android.locale.AppLanguage.ENGLISH)
        PhantomNotificationManager.createChannel(context)
        assertEquals("Messages", manager.getNotificationChannel(id).name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(id).importance)
    }

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
