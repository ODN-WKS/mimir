package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.nav.Screen

/** One thing Settings' search finds: its name, where it lives, the words people may use for it. */
data class Findable(
    val title: String, val where: String, val words: List<String>, val screen: Screen, val pairedOnly: Boolean = false,
    /** Not a page: the row says what a Whisper is, and a tap starts one. */
    val startsWhisper: Boolean = false,
)

/** The Whisper row's words: what it is, before and after pairing. */
fun whisperExplained(paired: Boolean) =
    if (paired) "A private chat that's never kept: with $AI_NAME on this phone, or with your PC's model and the internet cut off. Tap to start one."
    else "A private chat on this phone: $AI_NAME answers here, nothing is sent anywhere, and nothing of it is kept. Tap to start one."


/** Everything Settings' search knows, in the order results show. */
val FINDABLE = listOf(
    Findable("Start a Whisper", "Chat", listOf("whisper", "private", "incognito", "secret", "never kept", "forget", "eye"), Screen.Chat, startsWhisper = true),
    Findable(EYE_CHATS, "Chat › the eye", listOf("chat history", "chats", "history", "old chats", "earlier", "past", "conversations", "previous", "new chat"), Screen.Chats),
    Findable("Voice", "Settings", listOf("voice", "read aloud", "ingrid", "speed", "talk", "voice typing", "microphone", "dictation", "talk to type"), Screen.Voice),
    Findable("Read-aloud speed", "Voice", listOf("speed", "read aloud", "voice", "speak", "talk", "voice typing", "dictation", "slower", "faster"), Screen.Voice),
    Findable("Other voices", "Voice", listOf("voice", "voices", "read aloud", "scottish", "ingrid", "alba", "reading voice", "accent", "speak", "download"), Screen.Voices),
    Findable("Who answers", "Settings", listOf("models", "model", AI_NAME, "mimir", "download", "whisper", "claude", "free", "store", "model store", "uncensored", "online models", "this phone", "your pc"), Screen.Models),
    Findable("Online models (keys)", "Who answers", listOf("key", "keys", "online", "online models", "openrouter", "groq", "paste a key", "account"), Screen.Models),
    Findable("Rules for every chat", "Who answers", listOf("rules", "ground rules", "system prompt", "instructions", "prompt", "truth", "flattery", "behaviour", "behavior", "honest"), Screen.GroundRules),
    Findable("Privacy", "Settings", listOf("privacy check", "privacy", "what was sent", "sent", "what went out", "data", "network", "received", "traffic", "airplane", "offline", "tracking", "end whispers", "leave the app", "forget", "hidden route", "tor", "lock", "security"), Screen.PrivacyCheck),
    Findable("Lock $AI_NAME", "Privacy", listOf("lock", "unlock", "fingerprint", "face", "biometric", "pin", "passcode", "security", "screen lock", "protect", "open with"), Screen.PrivacyCheck),
    Findable("Looking things up", "Privacy", listOf("weather", "internet", "online", "look up", "lookup", "lookups", "web", "search the web", "duckduckgo", "encyclopedia", "wikipedia", "grokipedia", "location", "place", "city", "forecast", "hidden route", "offline"), Screen.PrivacyCheck),
    Findable("Notifications", "Settings", listOf("alerts", "battery", "every 5 minutes", "notify", "notifications", "instant alerts"), Screen.Notifications, pairedOnly = true),
    Findable("Look and size", "Settings", listOf("text size", "bigger text", "bigger", "smaller", "zoom", "font", "size", "dark", "light", "colors", "colours", "theme", "startup animation", "startup", "eye", "animation", "opens", "intro"), Screen.General),
    Findable("Your PC", "Settings", listOf("pair", "pc", "computer", "connect", "connection", "link", "code", "relay", "unpair", "disconnect", "phones", "name", "from anywhere"), Screen.PhonePc, pairedOnly = true),
    Findable("Connect your PC", "Your PC", listOf("pair", "pc", "computer", "connect", "link", "code"), Screen.Pair),
    Findable("Storage", "Settings", listOf("space", "delete chats", "delete all chats", "delete all", "storage", "memory", "chats", "free up space"), Screen.Storage),
    Findable("Help and about", "Settings", listOf("help", "how", "questions", "away from home", "what is a whisper", "needs you"), Screen.Help),
    Findable("What if I lose this phone?", "Help and about", listOf("lost phone", "lose phone", "lost", "stolen", "remove a phone"), Screen.HelpAnswer("lost")),
    Findable("What are Updates?", "Help and about", listOf("updates", "spoken", "spoken updates", "hear updates"), Screen.HelpAnswer("updates")),
    Findable(LABS_TITLE, "Help and about", LABS_WORDS.split(' '), Screen.Labs),
    Findable("Report a problem", "Help and about › Labs", listOf("report", "bug", "problem", "github", "feedback", "something wrong", "broken", "crash"), Screen.Labs),
    Findable("Run checks", "Help and about › Labs", listOf("checks", "troubleshoot", "test", "log", "developer log", "notes", "errors"), Screen.Labs),
    Findable("App updates", "Help and about", listOf("update", "new version", "check for a new version", "version", "app version", "upgrade", "install", "updates", "mímir", "mimir", "app name", "about"), Screen.About),
)

/** What matches [query]: every word of it is in the name, the place or the words. */
fun findSettings(query: String, paired: Boolean): List<Findable> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return FINDABLE.filter { f ->
        (paired || !f.pairedOnly) && (!paired || f.screen != Screen.Pair) &&
            (listOf(f.title, f.where) + f.words).joinToString(" ").lowercase().let { all -> words.all { it in all } }
    }
}
