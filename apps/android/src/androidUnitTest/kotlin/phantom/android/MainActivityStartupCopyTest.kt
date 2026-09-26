// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android

import android.app.Application
import android.database.sqlite.SQLiteException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainActivityStartupCopyTest {
    @Test
    fun startupFailuresUseStableResourceCategoriesWithoutRawDetails() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(R.string.startup_keys_unavailable, startupErrorResource(SecurityException("secret")))
        assertEquals(R.string.startup_database_unavailable, startupErrorResource(SQLiteException("db path")))
        assertEquals(R.string.startup_unknown_error, startupErrorResource(IllegalStateException("token")))

        val message = context.getString(
            R.string.startup_error_detail,
            context.getString(startupErrorResource(SQLiteException("private db path"))),
        )
        assertFalse(message.contains("private db path"))
        assertFalse(message.contains("reinstall", ignoreCase = true))
    }
}
