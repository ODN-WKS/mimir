package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.online.OnlineModels
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P38 (owner-found): a chat cannot run anything, so what every answerer reached from the phone is
 * told says so, forbids a made-up result, and offers project work, which the person starts.
 */
class P38NeverClaimsTest {
    private fun told(what: String) {
        for (words in listOf(
            "You cannot run, start, stop",
            "never make up its result",
            "never answer \"Stopped\"",
            "never make one up",
            "offer it as project work",
            "a task in one of their projects on their PC",
            "Replying yes in this chat starts nothing",
        )) assertTrue("$words in: $what", what.contains(words))
    }

    @Test fun thePhonesModelIsToldInEveryKindOfChat() {
        val turns = listOf(Turn(true, "run a 5 minute benchmark stressing my entire PC"))
        for (rules in listOf<String?>(null, "Be brief.")) for (online in listOf(false, true)) {
            told(Prompt.system(rules, online))
            for (format in Format.entries) told(Prompt.build(format, turns, rules = rules, online = online))
        }
        told(Prompt.system("Be brief.", online = true, ask = "Ask."))
    }

    @Test fun anOnlineModelAskedFromThePhoneIsTold() {
        told(OnlineModels.system(null))
        told(OnlineModels.system("Be brief."))
    }
}
