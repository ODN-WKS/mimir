package io.github.odnwks.mimir.standalone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHW-02: items that do not fit the device must be greyed (isEnabled = false)
 * with a non-empty fitReason. Items that do fit must have isEnabled = true.
 */
class UnsupportedItemGreyedTest {

    @Test
    fun `item that does not fit has isEnabled false and non-empty fitReason`() {
        // Android version too old.
        val item = deviceFit(
            name = "Local model",
            androidVersion = 9,
            ramMb = 8192,
            requiredAndroid = 11,
            requiredRamMb = 4096,
        )
        assertFalse("isEnabled must be false when Android version is too low", item.isEnabled)
        assertTrue("fitReason must be non-empty", item.fitReason.isNotEmpty())
    }

    @Test
    fun `item that fits has isEnabled true`() {
        val item = deviceFit(
            name = "Local model",
            androidVersion = 13,
            ramMb = 8192,
            requiredAndroid = 11,
            requiredRamMb = 4096,
        )
        assertTrue("isEnabled must be true when device fits", item.isEnabled)
    }

    @Test
    fun `item greyed for insufficient RAM`() {
        val item = deviceFit(
            name = "Large model",
            androidVersion = 13,
            ramMb = 2048,
            requiredAndroid = 11,
            requiredRamMb = 4096,
        )
        assertFalse("isEnabled false for low RAM", item.isEnabled)
        assertTrue("fitReason non-empty", item.fitReason.isNotEmpty())
        assertTrue(
            "fitReason must not say 'Fits this phone' (PHW-02)",
            !item.fitReason.contains("Fits this phone"),
        )
    }

    @Test
    fun `UnsupportedItem rejects empty fitReason when disabled`() {
        val ex = runCatching {
            UnsupportedItem(name = "bad", isEnabled = false, fitReason = "")
        }.exceptionOrNull()
        assertTrue("must throw when isEnabled=false and fitReason is empty", ex != null)
    }
}
