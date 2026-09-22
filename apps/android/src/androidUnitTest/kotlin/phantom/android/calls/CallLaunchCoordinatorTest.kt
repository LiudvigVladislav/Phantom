// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.calls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallLaunchCoordinatorTest {
    @Test
    fun navigation_waits_until_suspended_call_setup_has_completed() = runTest {
        val setupMayFinish = CompletableDeferred<Unit>()
        var active = false
        var navigated = false

        val result = async {
            startCallBeforeNavigation(
                startCall = {
                    setupMayFinish.await()
                    active = true
                },
                hasActiveCall = { active },
                navigate = { navigated = true },
            )
        }

        assertFalse(navigated)
        setupMayFinish.complete(Unit)
        assertTrue(result.await())
        assertTrue(navigated)
    }

    @Test
    fun failed_call_setup_does_not_navigate() = runTest {
        var navigated = false

        val result = startCallBeforeNavigation(
            startCall = {},
            hasActiveCall = { false },
            navigate = { navigated = true },
        )

        assertFalse(result)
        assertFalse(navigated)
    }
}
