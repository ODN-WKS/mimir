package io.github.odnwks.mimir.data

/**
 * PHU-20: Confirmation step before removing a paired phone.
 *
 * "Remove another phone" previously proceeded in one tap with no confirmation.
 * Now the owner sees: "Remove [name]? This can't be undone." with Cancel / Remove buttons.
 */
data class RemovePhoneDialog(val phoneName: String) {
    val message: String get() = "Remove $phoneName? This can't be undone."
}

/**
 * Returns the confirmation dialog that must be shown before removing [phoneId], or
 * null if the phone is not found in [phones].
 */
fun confirmRemovePhone(phoneId: String, phones: List<Phone>): RemovePhoneDialog? {
    val phone = phones.firstOrNull { it.id == phoneId } ?: return null
    return RemovePhoneDialog(phone.name)
}
