// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.testing

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ModelSnapshotRuleTest {
    private fun evaluate(body: () -> Unit) {
        ModelSnapshotRule().apply(object : Statement() {
            override fun evaluate() = body()
        }, Description.EMPTY).evaluate()
    }

    @Test
    fun modelSeesItsWritesWithoutPublishingThemToTheNextTest() {
        val state = mutableStateOf(0)
        Snapshot.sendApplyNotifications()
        evaluate {
            state.value = 1
            assertEquals(1, state.value)
        }
        assertEquals(0, state.value)
        evaluate { assertEquals(0, state.value) }
    }

    @Test
    fun failureStillRestoresTheSnapshotAndPropagatesTheOriginalError() {
        val state = mutableStateOf(0)
        Snapshot.sendApplyNotifications()
        val failure = AssertionError("intentional test failure")
        val thrown = assertFailsWith<AssertionError> {
            evaluate {
                state.value = 1
                throw failure
            }
        }
        assertSame(failure, thrown)
        assertEquals(0, state.value)
        evaluate { assertEquals(0, state.value) }
    }

    @Test
    @OptIn(androidx.compose.runtime.InternalComposeApi::class)
    fun snapshotIsDisposedRatherThanJustExited() {
        val before = Snapshot.openSnapshotCount()
        repeat(3) { evaluate { mutableStateOf(it).value = it + 1 } }
        assertEquals(before, Snapshot.openSnapshotCount())
    }
}
