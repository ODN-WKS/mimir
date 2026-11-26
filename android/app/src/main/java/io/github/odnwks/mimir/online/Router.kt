package io.github.odnwks.mimir.online

/**
 * Decides, from the owner's typed words only, whether a message needs a lookup before Mímir
 * answers. The rules are narrow on purpose: an everyday private message must never turn into a
 * lookup. Files, pictures and earlier messages are never read here.
 */
object Router {
    sealed interface Need {
        /** The weather: for [place] named in the message, or the saved place (null). */
        data class Weather(val place: String?) : Need
        /** A web search for [words] (the owner's own words, at most about 12). */
        data class Search(val words: String, val source: SearchSource = SearchSource.Default) : Need
        /** "What is / who was …": the encyclopedias, for [words] (the subject asked about). */
        data class Encyclopedia(val words: String) : Need
    }

    /** Where a search result should come from (PHE-15). */
    enum class SearchSource {
        /** Normal: SearXNG on the PC, then DuckDuckGo. */
        Default,
        /** "Grokipedia only: X" — the source is Grokipedia, not Wikipedia first. */
        GrokipediaOnly,
    }

    /**
     * Asking for a search in so many words. The rest of the message, after the asking words, is
     * what's searched.
     */
    private val SEARCH = Regex(
        "^(?:please\\s+)?(?:can you\\s+|could you\\s+)?(?:search(?: the web| online| the internet)?(?: for)?|look up|google|find online)\\s+(.+)" +
            "|\\b(?:latest news|news) (?:about|on)\\s+(.+)" +
            "|\\bwhat(?:'s| is) the latest (?:on|about|with)\\s+(.+)" +
            "|\\b((?:current )?price of .+? (?:today|now|right now))\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Personal pronouns in a news/search query make it too personal to look up (PHE-05):
     * "news about my friend's illness" is about a private person, not a public topic.
     */
    private val PERSONAL_PRONOUNS = Regex(
        "\\b(my|mine|your|yours|our|ours|his|her|hers|their|theirs|me|us|him|them|" +
            "myself|yourself|ourselves|himself|herself|themselves|" +
            "i\\b|we\\b|you\\b|he\\b|she\\b|they\\b)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Grokipedia only:" prefix: a forced search that should use Grokipedia as the source, not
     * Wikipedia first (PHE-15).
     */
    private val GROKIPEDIA_ONLY = Regex(
        "^grokipedia\\s+only\\s*:\\s*(.+)",
        RegexOption.IGNORE_CASE,
    )

    /** "What is a black hole", "who was Ada Lovelace", "tell me about the Roman Empire". */
    private val ENCYCLOPEDIA = Regex(
        "^(?:please\\s+)?(?:(?:what|who)(?:'s|\\s+(?:is|was|are|were))|tell me about)\\s+(.{2,80}?)\\s*[?.!]*$",
        RegexOption.IGNORE_CASE,
    )

    /** A subject that's about the owner, the moment or the news is never an encyclopedia question. */
    private val NOT_ENCYCLOPEDIA = Regex(
        "\\b(my|mine|your|yours|our|me|i|you|we|us|he|she|they|it|this|that|these|those|there|here|today|tonight|tomorrow|yesterday|now|" +
            "time|date|day|weather|latest|news|price|best|wrong|happening|going on|up)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Products, ratings, prices, reviews, buying and recommendations: the web has these, an
     * encyclopedia does not (0.09 item 25: "what is highest rated shaving replacement razor
     * blades" only asked Wikipedia). Asked as a question or a request, not in passing.
     */
    private val SHOPPING = Regex(
        "\\b(?:best|(?:top|highest|best)[- ](?:rated|selling)|most popular|cheapest|ratings?|reviews?|reviewed|" +
            "recommend(?:ed|ation|ations)?|buy|buying|purchase|prices? (?:of|for)|cost of (?:a|an|new|buying)|" +
            "worth (?:it|buying|the money)|deals? (?:on|for)|which .{1,40} should i (?:buy|get))\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The start of a question or request (not a statement): "what is", "which", "can you recommend", "find me". */
    private val ASKING = Regex(
        "^(?:please\\s+)?(?:what|which|who|where|how|is|are|should|can|could|do|does|any|tell me|give me|find me|show me|suggest|recommend|i want|i need|i'?m looking)\\b|\\?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Pronouns for the person asking or someone else, but not "you" ("can you recommend a tent" is a plain request). */
    private val SELF_OR_OTHER = Regex(
        "\\b(my|mine|our|ours|his|her|hers|their|theirs|me|us|him|them|myself|ourselves|himself|herself|themselves|i|we|he|she|they)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "Which laptop should I buy" is a shopping question; "I have depression" is private. */
    private val SHOULD_I_BUY = Regex("\\bshould i (?:buy|get)\\b", RegexOption.IGNORE_CASE)

    private fun aboutPerson(t: String) = SELF_OR_OTHER.containsMatchIn(SHOULD_I_BUY.replace(t, ""))

    /**
     * Anything current, worded loosely (P21 stage 2, plan section 5): the web has it, the model's
     * memory may be out of date. Each cue is narrow, so general knowledge still just gets answered.
     */
    private val TIMELY = Regex(
        // "the current president", "currently": as a word about now, not "an electric current".
        "(?<!\\b(?:an|a|electric|ocean|alternating|direct|rip|air|the)\\s)\\bcurrent(?:ly)?\\b(?!\\s*[?.!]*$)" +
            "|\\bthe current\\s+(?!(?:in|of|through|flow)\\b)\\p{L}" +
            "|\\b(?:latest|newest)\\b|\\btoday\\b|\\bthis year\\b|\\brelease date\\b|\\bstandings\\b|\\bin stock\\b" +
            "|^(?:is|are|does|do|was|has|have)\\s+.{1,60}?\\sstill\\b" +
            "|\\bwhen\\s+(?:is|was|will|did|does)\\s+.{1,60}?\\s(?:be\\s+)?released\\b|\\b(?:has|have)\\s+.{1,60}?\\sbeen\\s+released\\b" +
            "|\\bwhat(?:'s|\\s+is|\\s+was)\\s+the\\s+score\\b|\\bfinal\\s+score\\b|\\b(?:game|match)\\s+score\\b|\\bscores?\\s+(?:today|tonight|yesterday|last night)\\b" +
            "|(?<!\\b(?:a|an|my|your|our)\\s)\\bschedules?\\b(?!\\s+(?:a|an|my|the|it|this|that|me|us)\\b)" +
            "|\\bopen\\s+(?:now|right now|today|tonight)\\b" +
            "|\\b(?:what|which|newest|latest|current)\\s+version\\b" +
            "|\\bwho(?:'s|\\s+is)\\s+(?:the\\s+)?(?:current\\s+)?(?:president|prime minister|ceo|mayor|governor|king|queen|pope|chancellor)\\b" +
            "|^how much (?:is|are)\\s|^how much (?:does|do)\\s+.{1,60}?\\s(?:cost|charge|pay|earn|make|sell for|go for)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "What day is it today": the app gives the model the date; no lookup. */
    private val NOT_TIMELY = Regex(
        "\\bwhat(?:'s|\\s+is)?\\s+(?:the\\s+)?(?:day|date)\\b|\\btoday'?s date\\b|\\bwhat day\\b|\\bcurrent (?:time|date|day)\\b" +
            // A sum: "how much is 2 plus 2", "how much is 15% of 80".
            "|^how much (?:is|are)\\s+[-\\d.,\\s]*\\d\\s*(?:plus|minus|times|divided|multiplied|over|squared|cubed|to the power|percent|%|x(?=\\s*\\d)|[-+*/×÷^])",
        RegexOption.IGNORE_CASE,
    )

    /** Asking about a fact (not a task): a question word first, a question mark, or a few loose words. */
    private val FACT_QUESTION = Regex(
        "^(?:what|what's|whats|which|who|who's|whos|when|where|how|is|are|was|were|did|does|do|has|have|will)\\b|\\?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** A task for Mímir, not a question about the world. */
    private val TASK = Regex(
        "\\b(?:write|draft|compose|draw|create|translate|summari[sz]e|rewrite|poem|story|joke|essay)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "Can you tell me …", "do you know …": the asking words around the question, not part of it. */
    private val ASK_PREFIX = Regex(
        "^(?:please\\s+)?(?:(?:can|could|would) you\\s+(?:please\\s+)?(?:tell me|find(?: out)?|check|let me know)\\s+|do you know\\s+|tell me\\s+)",
        RegexOption.IGNORE_CASE,
    )

    /** A time-bound question, as search words, or null. Never with personal pronouns (PHE-05). */
    private fun timely(text: String): Need.Search? {
        val t = ASK_PREFIX.replace(text.trim(), "")
        if (!TIMELY.containsMatchIn(t) || NOT_TIMELY.containsMatchIn(t) || TASK.containsMatchIn(t)) return null
        if (!FACT_QUESTION.containsMatchIn(t) && t.split(Regex("\\s+")).size > 6) return null
        if (PERSONAL_PRONOUNS.containsMatchIn(t)) return null
        return words(t)?.let { Need.Search(it) }
    }

    private val WEATHER = Regex(
        "\\b(weather|forecast)\\b" +
            "|\\b(temperature|humidity)\\s+(outside|today|tonight|tomorrow|right now|now)\\b" +
            // "temperature in Portland", but not "temperature in the oven": a place starts with a capital.
            "|\\b(temperature|humidity)\\s+(in|at)\\s+(?-i:[A-Z])" +
            "|\\b(will|is) it (going to )?(rain|snow|storm|be (cold|hot|warm|sunny|windy|freezing|chilly|nice|cloudy))\\b" +
            "|\\bis it (raining|snowing|(cold|hot|warm|sunny|windy|freezing|chilly) (outside|out|today|in|at))\\b" +
            "|\\bhow (hot|cold|warm|windy|chilly|humid) (is it|will it be|is it going to be)\\b" +
            "|\\b(rain|snow|sunny|sunshine)\\s+(today|tonight|tomorrow|this weekend)\\s*[?.!]*$" +
            "|\\b(do i|should i|will i) (need|take|bring) (an umbrella|a coat|a jacket)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Sayings and other uses of the words that aren't about today's weather. */
    private val NOT_WEATHER = Regex("\\bunder the weather\\b|\\bweather (the|a|this) storm\\b|\\bfair[- ]weather\\b|\\bweather(ed|ing)?\\s+(it|out|through)\\b", RegexOption.IGNORE_CASE)

    private val PLACE = Regex(
        "\\b(?:in|for|at|near|around)\\s+([\\p{L}][\\p{L}'. -]{0,40}?)" +
            "(?=\\s+(?:today|tonight|tomorrow|this|next|on|over|right|now|later|please)\\b|\\s*[?.!,;]|\\s*$)",
        RegexOption.IGNORE_CASE,
    )

    /** Words after "in"/"for" that aren't a place. */
    private val NOT_PLACE = Regex(
        "^(today|tonight|tomorrow|now|later|here|me|us|it|my|our|the|this|that|a|an|weekend|the weekend|my area|our area|" +
            "this week|this weekend|next week|the next.*|the coming.*|the week|the morning|the afternoon|the evening|" +
            "my location|where i am|where i live|the area|outside)$",
        RegexOption.IGNORE_CASE,
    )

    /** "What's running?" and its kin: a question to the PC about its own work, never a lookup on the web. */
    private val ABOUT_THE_PC = Regex("^what(?:'s|\u2019s| is)\\s+(?:running|going on)(?:\\s+(?:on\\s+)?(?:my|your|the)\\s+pc)?\\s*[?!.]*$", RegexOption.IGNORE_CASE)

    /** What [text] needs looked up first, or null for none. */
    fun need(text: String): Need? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 300) return null
        if (ABOUT_THE_PC.matches(t)) return null
        // PHE-15: "Grokipedia only: X" → a forced search using Grokipedia as the source.
        GROKIPEDIA_ONLY.find(t)?.let { m ->
            val subject = m.groupValues[1].trim()
            return words(subject)?.let { Need.Search(it, source = SearchSource.GrokipediaOnly) }
        }
        // PHE-06: weather must appear in a question or request context — a past-tense statement
        // or a clause where "weather" is the object of a verb ("check the weather myself",
        // "talking about the weather") is not a lookup.  The heuristic: the sentence must end
        // with a question mark or contain a present/future weather inquiry verb pattern, OR it
        // must be short and contain only weather vocabulary.
        if (WEATHER.containsMatchIn(t) && !NOT_WEATHER.containsMatchIn(t)) {
            if (isWeatherQuery(t)) return Need.Weather(placeIn(t))
        }
        SEARCH.find(t)?.let { m ->
            val raw = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
            // PHE-05: a news/search query that contains personal pronouns is too personal to look up.
            if (PERSONAL_PRONOUNS.containsMatchIn(raw)) return null
            return words(raw)?.let { Need.Search(it) }
        }
        // Product, rating, price, review, buy and recommend questions: the web search first.
        // Still never about a private person or thing ("my", "his"...): that stays unsearched, as for news.
        if (SHOPPING.containsMatchIn(t) && ASKING.containsMatchIn(t) && !aboutPerson(t)) return words(t)?.let { Need.Search(it) }
        // Anything current ("who is the prime minister of Canada", "Lakers schedule"): the web, not memory.
        timely(t)?.let { return it }
        val e = ENCYCLOPEDIA.find(t) ?: return null
        val subject = e.groupValues[1].trim()
        if (NOT_ENCYCLOPEDIA.containsMatchIn(subject) || subject.split(Regex("\\s+")).size > 8) return null
        return words(subject)?.let { Need.Encyclopedia(it) }
    }

    /**
     * Whether [text] is actually asking for today's weather (PHE-06). Rules out statements
     * like "the weather has been nice lately" or "I'll check the weather myself".
     */
    private fun isWeatherQuery(text: String): Boolean {
        val t = text.trim()
        // A question mark always qualifies.
        if (t.endsWith('?')) return true
        // Very short messages that are only about weather ("weather", "weather today") qualify.
        if (t.split(Regex("\\s+")).size <= 3) return true
        // Future or present-tense weather inquiry: "will it rain", "is it going to snow", etc.
        if (Regex("\\b(will|is it|going to|should i|do i need)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        // "how hot/cold is it", "how's the weather", "how's the weather today"
        if (Regex("\\b(how('s| is)? (the )?weather|how (hot|cold|warm|windy|chilly|humid))\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        // "what's the weather", "what is the weather forecast", etc.
        if (Regex("\\bwhat('s| is) (the )?(weather|forecast)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        // "weather for/in X" or "forecast for/in X" when weather/forecast leads the sentence (PHE-06 safe).
        if (Regex("^(?:(?:what(?:'s| is)|how(?:'s| is)?)\\s+(?:the\\s+)?)?(weather|forecast)(?:\\s+(?:for\\s+)?(?:today|tonight|tomorrow|this weekend|the weekend))?\\s+(in|for|at|near|around)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        // "<place> weather/forecast [tomorrow]" (group 1) or "weather <ProperNoun>" (group 2 uppercase) → lookup.
        BARE_PLACE.find(t)?.let { m ->
            val g1 = m.groupValues.getOrNull(1) ?: ""
            val g2 = m.groupValues.getOrNull(2) ?: ""
            // Group 1 = place before weather word (always a lookup).
            // Group 2 = place after weather word — only a lookup when it starts with a capital letter
            //   (a proper noun like "Redding"), not when it's a clause ("has been nice lately").
            if (g1.isNotBlank() || (g2.isNotBlank() && g2.first().isUpperCase())) return true
        }
        // Otherwise treat it as a statement, not a query.
        return false
    }

    private val BARE_SEARCH = Regex(
        "^(?:please\\s+)?(?:search|search\\s+(?:it|that|this|for it|for that|the web|online)|look\\s+(?:it|that|this)\\s+up(?:\\s+online)?|google\\s+(?:it|that))(?:\\s+please)?[.!? ]*$",
        RegexOption.IGNORE_CASE,
    )
    private val WHAT_ABOUT = Regex("^(?:what|how)\\s+about\\s+(?:in\\s+)?(.+?)[?.! ]*$|^and\\s+(?:in\\s+)?(.+?)\\?+\\s*$", RegexOption.IGNORE_CASE)

    /**
     * A message that only makes sense with the one before it (owner-found 2026-11-25): "search"
     * alone searches the owner's previous question; "what about Fresno California" after a weather
     * question asks the weather there. Null when [text] is not such a follow-up.
     */
    fun followUp(text: String, previous: String?): Need? {
        if (previous.isNullOrBlank()) return null
        val t = text.trim()
        if (BARE_SEARCH.matches(t)) return lookUp(previous)
        val place = WHAT_ABOUT.find(t)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (place.split(Regex("\\s+")).size > 5) return null
        return if (need(previous) is Need.Weather) Need.Weather(Weather.placeName(place)) else null
    }

    /** "Look it up" on an answer: the owner's own message, as search words. */
    fun lookUp(text: String): Need.Search? {
        val t = text.trim()
        val m = SEARCH.find(t)
        return words(m?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: t)?.let { Need.Search(it) }
    }

    /** At most 12 words, without the question's punctuation at the end. */
    fun words(text: String): String? = text.trim().trimEnd('?', '.', '!').split(Regex("\\s+")).filter { it.isNotEmpty() }.take(12)
        .joinToString(" ").takeIf { it.isNotBlank() }

    private const val WHEN = "(?:\\s+(?:today|tonight|tomorrow|now|like|(?:this|the|on the|for the|next) week-?(?:end)?|on the week-?end))"

    /**
     * "Portland weather", "weather Portland", "Portland, Oregon weather", "Portland forecast tomorrow":
     * a place without "in". PHE-13: allow "City, State" with an optional comma-separated
     * region (e.g. "Portland, Oregon").
     */
    private val BARE_PLACE = Regex(
        "^(?:(?:what's|whats|what is|how's|hows|how is)\\s+(?:the\\s+)?)?([\\p{L}][\\p{L}'. -]{1,30}?(?:,\\s*[\\p{L}][\\p{L}'. -]{1,30}?)?)\\s+(?:weather|forecast)\\b$WHEN?\\s*[?.!]*$" +
            "|^(?:the\\s+)?(?:weather|forecast)\\s+([\\p{L}][\\p{L}'. -]{1,30}?(?:,\\s*[\\p{L}][\\p{L}'. -]{1,30}?)?)$WHEN?\\s*[?.!]*$",
        RegexOption.IGNORE_CASE,
    )

    /** Words that are never a place on their own. */
    private val NOT_PLACE_WORD = setOf(
        "what", "what's", "whats", "the", "is", "how", "how's", "today", "today's", "tomorrow", "tomorrow's", "tonight", "tonight's",
        "now", "like", "current", "local", "my", "our", "your", "this", "that", "a", "an", "good", "bad", "nice", "weekly", "daily",
        "week", "weekend", "morning", "evening", "afternoon", "update", "report", "please", "check", "give", "me", "tell", "get",
    )

    /**
     * A place named in the message ("weather in Oslo tomorrow" → "Oslo", "weather for tomorrow in
     * Paris" → "Paris"), a postcode ("weather 90210"), or null.
     */
    fun placeIn(text: String): String? = (
        PLACE.findAll(text).map { Weather.placeName(it.groupValues[1].trim().trimEnd('.')) }
            .firstOrNull { it.isNotEmpty() && !NOT_PLACE.matches(it) && !it.lowercase().startsWith("the next") }
            ?: BARE_PLACE.find(text.trim())?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()?.trimEnd('.')
                ?.takeIf { p -> p.split(Regex("\\s+")).none { it.lowercase() in NOT_PLACE_WORD } }
        )?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        ?: Weather.postcodeIn(text)
}
