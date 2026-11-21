package io.github.odnwks.mimir.standalone

/**
 * An item that may or may not fit this device (PHW-02).
 *
 * When [isEnabled] is false the item is greyed in the UI; [fitReason] says
 * why it does not fit (e.g. "Needs Android 11+", "Not enough RAM (4 GB required)").
 * [fitReason] is always non-empty when [isEnabled] is false.
 */
data class UnsupportedItem(
    val name: String,
    /** False when this item does not fit the device. */
    val isEnabled: Boolean,
    /**
     * Human-readable reason for the fit or lack of fit.
     * Non-empty whenever [isEnabled] is false (never "Fits this phone" for items that don't fit).
     */
    val fitReason: String,
) {
    init {
        require(isEnabled || fitReason.isNotEmpty()) {
            "fitReason must be non-empty when isEnabled is false"
        }
    }
}

/** Checks whether [item] fits this device and returns a filled [UnsupportedItem]. */
fun deviceFit(
    name: String,
    androidVersion: Int,
    ramMb: Long,
    requiredAndroid: Int,
    requiredRamMb: Long,
): UnsupportedItem {
    return when {
        androidVersion < requiredAndroid ->
            UnsupportedItem(name, isEnabled = false, fitReason = "Needs Android $requiredAndroid+")
        ramMb < requiredRamMb ->
            UnsupportedItem(name, isEnabled = false, fitReason = "Not enough RAM (${requiredRamMb / 1024} GB required)")
        else ->
            UnsupportedItem(name, isEnabled = true, fitReason = "")
    }
}
