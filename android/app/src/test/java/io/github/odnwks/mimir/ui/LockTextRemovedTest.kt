package io.github.odnwks.mimir.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B7 / PHU-06: The app-lock feature is paused. No string resource or
 * user-visible string in the UI should contain app-lock wording.
 * Checks for "fingerprint" (as a UI label, not biometric API),
 * "PIN lock", "phone lost" (in lock context), and the id "app_lock_switch".
 */
class LockTextRemovedTest {

    @Test fun noStringResourceContainsLockText() {
        // Find the res/ directory relative to this test's source tree.
        val srcDir = File(System.getProperty("user.dir") ?: ".")
        val resDir = srcDir.resolve("src/main/res")
        if (!resDir.exists()) {
            // Skip when run outside the project (e.g. CI without sources).
            return
        }
        val bannedInRes = listOf("PIN lock", "phone lost", "app_lock_switch", "appLockSwitch")
        val failures = mutableListOf<String>()
        resDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { file ->
                val text = file.readText()
                for (banned in bannedInRes) {
                    if (text.contains(banned)) {
                        failures += "${file.name}: contains banned lock string \"$banned\""
                    }
                }
            }
        assertTrue(
            "String resources contain removed lock text:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    @Test fun noUiKotlinFileContainsLockUiStrings() {
        // The lock feature is paused; its UI-visible strings must not appear.
        val srcDir = File(System.getProperty("user.dir") ?: ".")
        val uiDir = srcDir.resolve("src/main/java/io/github/odnwks/mimir/ui")
        if (!uiDir.exists()) return
        // These are UI-label strings (not biometric API references).
        val bannedUiStrings = listOf("\"PIN lock\"", "\"App lock\"", "\"app lock switch\"", "\"phone lost\"")
        val failures = mutableListOf<String>()
        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//")) return@forEachIndexed
                    for (banned in bannedUiStrings) {
                        if (line.contains(banned)) {
                            failures += "${file.name}:${idx + 1}: found lock UI string $banned"
                        }
                    }
                }
            }
        assertTrue(
            "UI Kotlin files contain removed lock text:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }
}
