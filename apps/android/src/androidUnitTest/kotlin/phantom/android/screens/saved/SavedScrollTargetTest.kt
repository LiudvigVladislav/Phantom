package phantom.android.screens.saved

import org.junit.Test
import kotlin.test.*

class SavedScrollTargetTest {
    @Test fun waitsForTheSavedRowInsteadOfConsumingRequestOnOldEmission() {
        assertNull(savedScrollTarget(listOf("old"), "new", false))
        assertEquals(1, savedScrollTarget(listOf("old", "new"), "new", false))
    }
    @Test fun openingScrollsOnceAndUnrequestedEmissionsDoNotMoveHistory() {
        assertNull(savedScrollTarget(emptyList(), null, true))
        assertEquals(1, savedScrollTarget(listOf("a", "b"), null, true))
        assertNull(savedScrollTarget(listOf("a", "b", "c"), null, false))
        assertEquals(0, savedScrollTarget(listOf("a", "b"), "a", false))
    }
}
