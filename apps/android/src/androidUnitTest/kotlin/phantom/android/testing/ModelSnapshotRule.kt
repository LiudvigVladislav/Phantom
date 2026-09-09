// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.testing

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * For model tests which write Compose state without a Compose test rule.
 * Keep same-thread model writes local and discard them after the test.
 * These tests observe state directly; they do not need global recomposition.
 * Do not use for UI tests or state writers dispatched to another thread.
 */
class ModelSnapshotRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val snapshot = Snapshot.takeMutableSnapshot()
                try {
                    snapshot.enter { base.evaluate() }
                } finally {
                    snapshot.dispose()
                }
            }
        }
}
