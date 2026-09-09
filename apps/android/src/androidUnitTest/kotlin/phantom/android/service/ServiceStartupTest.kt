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

    @Test fun actual_service_observers_and_start_stop_on_failed_readiness() = runTest {
        val app = PhantomApplication() // Deliberately no native Application.onCreate.
        app.ready.completeExceptionally(IllegalStateException("test initialization failed"))
        val controller = Robolectric.buildService(PhantomMessagingService::class.java)
        val service = controller.get()
        val job = SupervisorJob()
        ReflectionHelpers.setField(service, "mApplication", app)
        ReflectionHelpers.setField(service, "serviceScope", CoroutineScope(job + StandardTestDispatcher(testScheduler)))
        try {
            controller.create()
            service.onStartCommand(null, 0, 1)
            runCurrent()
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(job.children.none { it.isActive })
            assertFalse(ShadowLog.getLogsForTag("PhantomMessaging").any {
                "UninitializedPropertyAccessException" in it.msg
            })
            // app.container was never assigned; an access would throw the physical crash.
        } finally {
            controller.destroy()
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
            service.onStartCommand(null, 0, 1)
            service.onStartCommand(null, 0, 2)
            runCurrent()
            assertEquals(3, job.children.count { it.isActive }, "two observers and one startup waiter")
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
