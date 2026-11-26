package io.github.odnwks.mimir.online

/**
 * Search words the model wrote, checked before they leave the phone (P21 stage 2, plan section 5).
 * STUB: piece C2 replaces the body.
 */
object QueryGuard {
    /**
     * The words to search: [modelWords], or the owner's own [ownerText] (as Router.lookUp makes it)
     * when the model's words hold text found only in an attached file or picture ([attached]), an
     * email address, 7 or more digits the owner didn't type in this chat ([typed]: every message the
     * owner typed), or the saved place ([savedPlace]) when the message didn't say "near me" or "here".
     */
    fun safe(modelWords: String, ownerText: String, typed: List<String>, attached: String?, savedPlace: String?): String =
        modelWords.trim().ifEmpty { ownerText }
}
