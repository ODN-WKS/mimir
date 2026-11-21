package io.github.odnwks.mimir.ui

/**
 * The order of model groups shown in the Models screen.
 * When paired: Claude (on PC), free (on PC), on this phone, voices.
 * When not paired: phone models first (they are the ones that work),
 * then voices, then Claude and free (shown greyed, for future pairing).
 * Satisfies brief B7 item PHU-17 / ModelOrderTest.
 */
fun modelGroupOrder(paired: Boolean): List<String> =
    if (paired) listOf("claude", "free", "phone", "voices")
    else listOf("phone", "voices", "claude", "free")

/**
 * The label shown above a reply bubble.
 * - A free or phone-local model reply uses the model's friendly name (or
 *   the app name if unavailable), never "Claude".
 * - A paid Claude reply uses "Claude".
 * Satisfies brief B7 item PHU-17 / FreeModelLabelTest.
 */
fun replyLabel(isFreeModel: Boolean, modelName: String?): String =
    if (isFreeModel) (modelName?.takeIf { it.isNotBlank() } ?: AI_NAME)
    else "Mímir"
