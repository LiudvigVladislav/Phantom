package phantom.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SafetyNumberTest {

    // Fake public-key hex strings (64 hex chars = 32 bytes).
    private val keyA = "aabbccdd".repeat(8)
    private val keyB = "11223344".repeat(8)

    @Test
    fun fingerprintIsDeterministic() = runTest {
        LibsodiumInitializer.initialize()
        val first  = SafetyNumber.compute(keyA, keyB)
        val second = SafetyNumber.compute(keyA, keyB)
        assertEquals(first, second)
    }

    @Test
    fun fingerprintIsSymmetric() = runTest {
        LibsodiumInitializer.initialize()
        val fromA = SafetyNumber.compute(keyA, keyB)
        val fromB = SafetyNumber.compute(keyB, keyA)
        assertEquals(fromA, fromB, "Fingerprint must be order-independent")
    }

    @Test
    fun fingerprintHasCorrectFormat() = runTest {
        LibsodiumInitializer.initialize()
        val fp = SafetyNumber.compute(keyA, keyB)
        val groups = fp.split("  ")
        assertEquals(5, groups.size, "Expected 5 groups, got: $fp")
        groups.forEach { group ->
            assertEquals(12, group.length, "Each group must be 12 chars, got '$group'")
            assertTrue(group.all { it.isDigit() }, "Group must be all digits, got '$group'")
        }
    }

    @Test
    fun differentKeysDifferentFingerprint() = runTest {
        LibsodiumInitializer.initialize()
        val keyC = "deadbeef".repeat(8)
        val fp1 = SafetyNumber.compute(keyA, keyB)
        val fp2 = SafetyNumber.compute(keyA, keyC)
        assertNotEquals(fp1, fp2, "Different key pairs must yield different fingerprints")
    }
}
