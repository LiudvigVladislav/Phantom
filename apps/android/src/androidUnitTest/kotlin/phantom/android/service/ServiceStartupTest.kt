package phantom.android.service

import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowLog
import phantom.android.PhantomApplication
import phantom.android.security.DeviceUnlockGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStartupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun grantOwnReceiverPermission() {
        shadowOf(context).grantPermissions("phantom.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    private fun locked(value: Boolean) {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(value)
    }
    private fun unlock() {
        locked(false)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun locked_read_waits_for_unlock_and_unregisters() = runTest {
        locked(true)
        val receivers = shadowOf(context).registeredReceivers.size
        var reads = 0
        val work = async { DeviceUnlockGate(context).readWhenUnlocked { ++reads } }
        runCurrent()
        assertEquals(0, reads)
        assertFalse(work.isCompleted)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(0, reads, "a broadcast cannot authorize a locked device")
        unlock()
        assertEquals(1, work.await())
        assertEquals(receivers, shadowOf(context).registeredReceivers.size)
    }

    @Test fun cancelled_unlock_wait_never_reads_and_releases_receiver() = runTest {
        locked(true)
        val receivers = shadowOf(context).registeredReceivers.size
        var reads = 0
        val work = async { DeviceUnlockGate(context).readWhenUnlocked { ++reads } }
        runCurrent()
        work.cancelAndJoin()
        unlock()
        runCurrent()
        assertEquals(0, reads)
        assertEquals(receivers, shadowOf(context).registeredReceivers.size)
    }

    @Test fun lock_between_admission_and_read_retries_only_after_unlock() = runTest {
        locked(false)
        var reads = 0
        val work = async {
            DeviceUnlockGate(context).readWhenUnlocked {
                if (++reads == 1) {
                    locked(true)
                    throw SecurityException("test key unavailable")
                }
                "same identity"
            }
        }
        runCurrent()
        assertEquals(1, reads)
        assertFalse(work.isCompleted)
        unlock()
        assertEquals("same identity", work.await())
        assertEquals(2, reads)
    }

    @Test fun unlocked_key_failure_is_reported_not_retried_or_called_absent() = runTest {
        locked(false)
        val failure = SecurityException("test permanent failure")
        var caught: Throwable? = null
        var reads = 0
        var missingIdentity = false
        val result = serviceStartupOrNull(onFailure = { caught = it }) {
            val identity = DeviceUnlockGate(context).readWhenUnlocked<String?> {
                ++reads
                throw failure
            }
            missingIdentity = identity == null
            true
        }
        assertNull(result)
        assertSame(failure, caught)
        assertEquals(1, reads)
        assertFalse(missingIdentity)
    }

    @Test fun cancellation_is_not_an_initialization_failure() = runTest {
        locked(false)
        val cancellation = CancellationException("test cancellation")
        var reported = false
        val thrown = runCatching {
            serviceStartupOrNull(onFailure = { reported = true }) {
                DeviceUnlockGate(context).readWhenUnlocked<String> { throw cancellation }
            }
        }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertFalse(reported)
    }

    @Test fun ready_failure_never_calls_the_container_getter() = runTest {
        val ready = CompletableDeferred<Unit>()
        val failure = IllegalStateException("test initialization failed")
        ready.completeExceptionally(failure)
        var reads = 0
        var caught: Throwable? = null
        assertNull(serviceStartupOrNull(onFailure = { caught = it }) {
            awaitReadyValue(ready) { ++reads }
        })
        assertTrue(caught === failure || caught?.cause === failure,
            "Deferred.await may recover the coroutine stack with a copy of the exception")
        assertEquals(0, reads)
    }

    @Test fun pending_readiness_does_not_read_until_success() = runTest {
        val ready = CompletableDeferred<Unit>()
        var reads = 0
        val work = async { awaitReadyValue(ready) { ++reads } }
        runCurrent()
        assertEquals(0, reads)
        ready.complete(Unit)
        assertEquals(1, work.await())
    }

    /**
     * A start that cannot reach a ready container stops the service and
     * hands recovery to the coordinator, which schedules ONE bounded
     * wake-up rather than trying again immediately.
     *
     * Review round 8 pass 5. This case asserted that a failed start left
     * the coroutine count exactly where `onCreate` had it -- "no coroutine
     * of its own" -- and that was wrong twice over. It described planned
     * recovery as a leak, and it asserted a number that production had
     * already stopped producing.
     *
     * What production actually does, and what this case now pins:
     *
     *   runStartAttempt fails readiness
     *     -> onStartJobCompleted(stamp)
     *     -> ensureRecoveryProgress("start_job_completed")
     *     -> no owner, no live session, and a start attempt one instant old
     *     -> START_BUDGET_MS spacing defers, arming one `spacingWakeJob`
     *
     * That wake-up is the mechanism that stops a failed start from waiting
     * on the alarm heartbeat, so its absence would be the defect, not its
     * presence. It is bounded, there is at most one, and it dies with the
     * instance -- all three of which are asserted below.
     */
    @Test fun a_failed_readiness_stops_the_service_and_arms_one_bounded_recovery_wake() = runTest {
        val app = PhantomApplication() // Deliberately no native Application.onCreate.
        app.ready.completeExceptionally(IllegalStateException("test initialization failed"))
        val controller = Robolectric.buildService(PhantomMessagingService::class.java)
        val service = controller.get()
        val job = SupervisorJob()
        ReflectionHelpers.setField(service, "mApplication", app)
        ReflectionHelpers.setField(service, "serviceScope", CoroutineScope(job + StandardTestDispatcher(testScheduler)))
        try {
            controller.create()
            runCurrent()
            // `onCreate` launches the long-lived collectors, including the
            // one that mirrors the recovery coordinator's start-job state
            // into presentation. They are SUPPOSED to run until
            // `onDestroy`, so the baseline is measured rather than written
            // down: a future lifecycle coroutine must not silently become
            // an accepted leak, and a fixture that stops measuring must
            // fail rather than pass.
            val lifecycleJobs = job.children.count { it.isActive }
            assertTrue(
                lifecycleJobs > 0,
                "onCreate is expected to leave lifecycle coroutines running; a zero here " +
                    "means this case has stopped measuring anything",
            )

            // Deliberately NOT ShadowLog.clear() here: the
            // `UninitializedPropertyAccessException` assertion below is
            // meant to cover everything this instance has logged, and
            // clearing would quietly narrow it to the start alone. The
            // trace assertion does not need a clear -- `recovery_deferred`
            // is only ever emitted by a decision, and no decision runs
            // before the start.
            service.onStartCommand(null, 0, 1)
            runCurrent()
            assertTrue(shadowOf(service).isStoppedBySelf)

            // Exactly ONE coroutine survives the failed start, and it is the
            // coordinator's bounded start-spacing wake. The count alone
            // would accept any stray job, so the coordinator's own trace is
            // the discriminator: it names both the trigger that produced the
            // wake and the reason it was deferred.
            assertTrue(
                ShadowLog.getLogsForTag("PhantomHybrid").any {
                    "recovery_deferred" in it.msg &&
                        "trigger=start_job_completed" in it.msg &&
                        "reason=start_spacing" in it.msg
                },
                "the failed start must hand recovery to the coordinator, which defers on " +
                    "START_BUDGET_MS spacing and arms one wake-up. Without this line the " +
                    "extra coroutine below is unattributed. Logs: " +
                    ShadowLog.getLogsForTag("PhantomHybrid").map { it.msg },
            )
            assertEquals(
                lifecycleJobs + 1, job.children.count { it.isActive },
                "the failed start leaves exactly one coroutine behind: the bounded " +
                    "`spacingWakeJob`. It is planned recovery, not a leak -- without it a " +
                    "restored-but-deferred start would wait for the alarm heartbeat, whose " +
                    "delivery Doze can defer without bound",
            )
            assertFalse(ShadowLog.getLogsForTag("PhantomMessaging").any {
                "UninitializedPropertyAccessException" in it.msg
            })
            // app.container was never assigned; an access would throw the physical crash.

            // And none of it outlives the instance: the collectors' licence
            // to outlive a start, and the spacing wake's licence to fire
            // later, both expire at onDestroy.
            controller.destroy()
            runCurrent()
            assertTrue(
                job.children.none { it.isActive },
                "onDestroy must take the lifecycle collectors AND the bounded recovery " +
                    "wake with it",
            )
        } finally {
            job.cancelAndJoin()
        }
    }

    /**
     * N1 integration -- a service that never reached a ready container is
     * torn down without touching one.
     *
     * The two lines meet here. The delivery line added readiness
     * admission, so nothing may read `container` before it exists; the
     * privacy/teardown line requires that an explicit stop ALWAYS stops
     * the retry timer and never arms recovery. Both hold at once: the
     * timer and the scheduler are stopped first, unconditionally, and
     * only then does the unready case return without walking a teardown
     * that would dereference the container.
     *
     * The discriminator is the container-access failure the full teardown
     * logs. Without the unready branch, `onDestroy` reaches
     * `container.networkChangeObserver` and that warning appears.
     */
    @Test fun destroying_a_service_that_never_became_ready_never_touches_the_container() = runTest {
        val app = PhantomApplication() // container is never assigned.
        val controller = Robolectric.buildService(PhantomMessagingService::class.java)
        val service = controller.get()
        val job = SupervisorJob()
        ReflectionHelpers.setField(service, "mApplication", app)
        ReflectionHelpers.setField(service, "serviceScope", CoroutineScope(job + StandardTestDispatcher(testScheduler)))
        ShadowLog.clear()
        controller.create()
        runCurrent()

        controller.destroy()
        runCurrent()

        assertFalse(
            ShadowLog.getLogsForTag("PhantomMessaging").any { "UninitializedPropertyAccessException" in it.msg },
            "teardown dereferenced a container that was never built",
        )
        assertFalse(
            ShadowLog.getLogs().any { "NetworkChangeObserver unregister failed" in it.msg },
            "teardown reached the container's observer on an unready service",
        )
        assertTrue(job.children.none { it.isActive }, "no coroutine survived the teardown")
        job.cancelAndJoin()
    }

    @Test fun destroyed_service_does_not_resume_after_readiness() = runTest {
        val app = PhantomApplication()
        val controller = Robolectric.buildService(PhantomMessagingService::class.java)
        val service = controller.get()
        val job = SupervisorJob()
        ReflectionHelpers.setField(service, "mApplication", app)
        ReflectionHelpers.setField(service, "serviceScope", CoroutineScope(job + StandardTestDispatcher(testScheduler)))
        try {
            controller.create()
            runCurrent()
            val lifecycleJobs = job.children.count { it.isActive }
            assertTrue(
                lifecycleJobs > 0,
                "onCreate is expected to leave lifecycle coroutines running; a zero here " +
                    "means this case has stopped measuring anything",
            )

            service.onStartCommand(null, 0, 1)
            service.onStartCommand(null, 0, 2)
            runCurrent()
            // Each `onStartCommand` launches exactly ONE coroutine on the
            // service scope. Stage 2 does not make the second one a second
            // ATTEMPT -- the coordinator admits one start and the other
            // joins it -- but it is still a coroutine and it is still
            // waiting, which is what this case needs it to be.
            //
            // This asserted a bare `3` until review round 8 pass 4. That
            // literal folded the lifecycle coroutines and the start
            // coroutines into one number, so adding a lifecycle collector
            // broke it for a reason it could not name. The baseline is now
            // measured and only the delta is asserted.
            assertEquals(
                lifecycleJobs + 2, job.children.count { it.isActive },
                "one coroutine per onStartCommand, on top of the onCreate lifecycle set",
            )
        } finally {
            controller.destroy()
            job.cancelAndJoin()
        }
        app.ready.complete(Unit)
        runCurrent()
        assertTrue(job.isCompleted)
        assertFalse(shadowOf(service).isStoppedBySelf, "cancellation must not enter startup failure handling")
    }

    @Test fun application_waits_for_unlock_before_constructing_the_container() {
        val source = File("src/androidMain/kotlin/phantom/android/PhantomApplication.kt").readText()
        val gate = source.indexOf("DeviceUnlockGate(this@PhantomApplication).awaitUnlocked()")
        assertTrue(gate >= 0)
        assertTrue(gate < source.indexOf("container = AppContainer("))
    }
}
