package io.github.odnwks.mimir.standalone

/**
 * PHU-24: Prevents a big model download from starting on mobile data without explicit consent.
 *
 * *   On Wi-Fi: allow.
 * *   On mobile data: block with a message unless the owner tapped "Download anyway".
 */
object DownloadGate {

    sealed class Result {
        /** The download may proceed. */
        object Allow : Result()
        /** Blocked on mobile data; show [message] with a "Download anyway" button. */
        data class BlockedMobileData(val message: String = MOBILE_DATA_MESSAGE) : Result()
    }

    /**
     * Whether a model download is allowed right now.
     *
     * @param isWifi True when the active network is Wi-Fi (not mobile data).
     * @param overrideByUser True when the owner explicitly tapped "Download anyway".
     */
    fun check(isWifi: Boolean, overrideByUser: Boolean = false): Result =
        if (isWifi || overrideByUser) Result.Allow else Result.BlockedMobileData()

    const val MOBILE_DATA_MESSAGE = "Connect to Wi-Fi to download this model"
    const val OVERRIDE_LABEL = "Download anyway"
}
