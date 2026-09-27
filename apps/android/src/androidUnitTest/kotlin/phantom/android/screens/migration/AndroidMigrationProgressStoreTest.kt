// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.migration

import android.app.Application
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.messaging.MigrationProgress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class AndroidMigrationProgressStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun durable_identity_scoped_progress_survives_new_store_instances() = runTest {
        val dir = temporary.newFolder()
        var store = AndroidMigrationProgressStore(dir)
        assertEquals(MigrationProgress.NOT_STARTED, store.read("alice"))
        store.write("alice", MigrationProgress.IN_PROGRESS)
        store = AndroidMigrationProgressStore(dir)
        assertEquals(MigrationProgress.IN_PROGRESS, store.read("alice"))
        assertEquals(MigrationProgress.NOT_STARTED, store.read("bob"))
        store.write("alice", MigrationProgress.COMPLETE)
        assertEquals(MigrationProgress.COMPLETE, AndroidMigrationProgressStore(dir).read("alice"))
    }

    @Test fun malformed_marker_fails_closed() = runTest {
        val dir = temporary.newFolder()
        val store = AndroidMigrationProgressStore(dir)
        store.write("alice", MigrationProgress.IN_PROGRESS)
        dir.listFiles()!!.single().writeText("v99:unknown")
        assertFailsWith<IllegalStateException> { AndroidMigrationProgressStore(dir).read("alice") }
    }

    @Test fun unfinished_replacement_does_not_hide_pending_marker() = runTest {
        val dir = temporary.newFolder()
        val store = AndroidMigrationProgressStore(dir)
        store.write("alice", MigrationProgress.IN_PROGRESS)
        File(dir.listFiles()!!.single().path + ".new").writeText("v1:complete\n")
        assertEquals(MigrationProgress.IN_PROGRESS, AndroidMigrationProgressStore(dir).read("alice"))
    }

    @Test fun failed_write_is_not_reported_as_success() = runTest {
        val notDirectory = temporary.newFile()
        assertFailsWith<java.io.IOException> {
            AndroidMigrationProgressStore(notDirectory).write("alice", MigrationProgress.IN_PROGRESS)
        }
    }

    @Test fun interrupted_first_write_has_not_started_key_mutation() = runTest {
        val dir = temporary.newFolder()
        val store = AndroidMigrationProgressStore(dir)
        store.write("alice", MigrationProgress.IN_PROGRESS)
        val base = dir.listFiles()!!.single()
        File(base.path + ".new").writeText("v1:in-")
        base.delete()
        assertEquals(MigrationProgress.NOT_STARTED, AndroidMigrationProgressStore(dir).read("alice"))
        store.write("alice", MigrationProgress.IN_PROGRESS)
        assertEquals(MigrationProgress.IN_PROGRESS, AndroidMigrationProgressStore(dir).read("alice"))
    }

    @Test fun progress_cannot_be_reset() = runTest {
        val store = AndroidMigrationProgressStore(temporary.newFolder())
        store.write("alice", MigrationProgress.COMPLETE)
        assertFailsWith<IllegalStateException> { store.write("alice", MigrationProgress.NOT_STARTED) }
        assertEquals(MigrationProgress.COMPLETE, store.read("alice"))
    }
}
