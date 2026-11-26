package io.github.odnwks.mimir.online

/**
 * Search words the model wrote, checked before they leave the phone (P21 stage 2, plan section 5).
 * Pure: no Android, no network. Anything doubtful falls back to the owner's own words.
 */
object QueryGuard {
    /**
     * The words to search: [modelWords], or the owner's own [ownerText] (as Router.lookUp makes it)
     * when the model's words hold text found only in an attached file or picture ([attached]), an
     * email address, 7 or more digits the owner didn't type in this chat ([typed]: every message the
     * owner typed), or the saved place ([savedPlace]) when the message didn't say "near me" or "here".
     */
    fun safe(modelWords: String, ownerText: String, typed: List<String>, attached: String?, savedPlace: String?): String {
        val words = modelWords.trim()
        val fallback = Router.lookUp(ownerText)?.words ?: ownerText
        if (words.isEmpty()) return fallback
        if (EMAIL.containsMatchIn(words)) return fallback
        if (longNumberNotTyped(words, typed)) return fallback
        if (!attached.isNullOrBlank() && onlyInAttached(words, typed, attached)) return fallback
        if (!savedPlace.isNullOrBlank() && namesSavedPlace(words, savedPlace) && typed.none { NEAR.containsMatchIn(it) }) return fallback
        return words
    }

    /** Any x@y.z. */
    private val EMAIL = Regex("[\\p{L}\\d._%+-]+@[\\p{L}\\d.-]+\\.[\\p{L}]{2,}")

    /** Digits in a row, with spaces or dashes between them allowed. */
    private val DIGIT_RUN = Regex("\\d(?:[\\s-]*\\d)*")

    /** The owner saying where they are, so the saved place may go with the words. */
    private val NEAR = Regex("\\b(?:near me|here|nearby|around me)\\b", RegexOption.IGNORE_CASE)

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private fun tokens(text: String) = WORD.findAll(text.lowercase()).map { it.value }.toList()

    /** A year on its own ("2024 2025 season") is not part of a long number. */
    private val YEAR = Regex("\\b(?:19|20)\\d\\d\\b")

    private fun digitRuns(text: String) = DIGIT_RUN.findAll(text.replace(YEAR, " ; ")).map { m -> m.value.filter { it.isDigit() } }.toList()

    /** 7 or more digits in a row (ignoring spaces and dashes) that no typed message holds. */
    private fun longNumberNotTyped(words: String, typed: List<String>): Boolean {
        val typedRuns = typed.flatMap { digitRuns(it) }
        return digitRuns(words).any { run -> run.length >= 7 && typedRuns.none { run in it } }
    }

    private fun runsOf3(t: List<String>): Set<String> = (0..t.size - 3).map { t.subList(it, it + 3).joinToString(" ") }.toSet()

    /**
     * Text found only in the attached file or picture: a run of 3 or more words, or a word of 6 or
     * more letters that isn't a common English word, that is in [attached] but in no typed message.
     */
    private fun onlyInAttached(words: String, typed: List<String>, attached: String): Boolean {
        val w = tokens(words)
        val file = tokens(attached)
        val typedTokens = typed.map { tokens(it) }
        val fileRuns = runsOf3(file)
        val typedRuns = typedTokens.flatMap { runsOf3(it) }.toSet()
        if (runsOf3(w).any { it in fileRuns && it !in typedRuns }) return true
        val fileWords = file.toSet()
        val typedWords = typedTokens.flatten().toSet()
        return w.any { it.length >= 6 && it.all { c -> c.isLetter() } && it !in COMMON && it in fileWords && it !in typedWords }
    }

    /** The saved place's name, or its first part before a comma, as words in [words]. */
    private fun namesSavedPlace(words: String, savedPlace: String): Boolean {
        val w = " " + tokens(words).joinToString(" ") + " "
        return listOf(savedPlace, savedPlace.substringBefore(','))
            .map { tokens(it).joinToString(" ") }
            .filter { it.isNotEmpty() }
            .any { " $it " in w }
    }

    /** Common English words of 6 or more letters: in a file, they say nothing private on their own. */
    private val COMMON: Set<String> = """
        about above across action actually address advice affect afraid after afternoon again against agency almost
        already always amount animal answer anyone anything appear around arrive article artist attack attention august
        author autumn available average balance basket battle beautiful became because become before begin behind
        believe benefit better between beyond billion bottle bottom branch breakfast bridge bright british brother
        budget building business button camera campaign cancer candidate capital career center central centre century
        certain chance change chapter character charge check cheese chicken children choice choose church circle
        citizen client climate closed coffee collection college colour color column coming commercial common community
        company compare complete computer concern condition consider contact contain content continue contract control
        corner correct cosmetic costly cotton council counter country county couple course cousin create credit crisis
        culture current customer damage danger daughter debate december decide decision defense degree deliver demand
        department depend design detail develop device dinner direct director discover discuss disease doctor dollar
        double during easily eastern editor education effect effort eight either election electric energy engine
        english enough entire environment equipment especially estate evening event every everyone everything evidence
        exactly example expect expert explain family famous farmer father feature february federal figure final finally
        finance financial finger finish flight flower follow football foreign forest forget format former forward
        france freedom french friday friend friendly further future garden gather general german germany global golden
        government ground growth guitar health hearing heavy height history holiday hospital hotel however hundred
        husband identify images impact important include income increase indeed indian industry inside instead interest
        international internet interview island itself january journey judge july june junior kitchen knowledge
        language large later latest leader league learning leather letter likely limited listen little living local
        london longer machine magazine mainly manage manager market marriage master matter maybe meaning measure media
        medical medium meeting member memory mention message method middle million minister minute mirror mobile modern
        moment monday money month morning mother motion moving murder museum music myself nation national natural nature
        nearly necessary network never newspaper nobody normal northern nothing notice november number object october
        office officer official online only option orange order original others outside owner pacific packet painting
        parent particular partner party patient pattern people percent perfect perhaps period person personal phone
        photo picture pieces planet plastic player please plenty pocket police policy political popular position
        positive possible potato power practice prepare present president pretty prevent price prince print prison
        private probably problem process produce product professor profit program project property protect provide
        public purpose quality question quickly rather reader reading ready really reason recent recently record
        region release remain remember report research resource result return review rights rising river saturday
        school science screen season second secret section security select senior series service settle seven shopping
        should signal silver simple simply single sister social society soldier something sometimes source southern
        special speech spring square stadium standard station status steady street strength strong student studio
        subject summer sunday supply support surface system teacher technology telephone television temperature tennis
        theatre theater therefore things thinking thirty thought thousand through thursday ticket together tomorrow
        tonight toward towards traffic training travel treatment trouble tuesday twelve twenty understand united
        university unless update useful usually valley various vehicle version victory village violence visitor
        volume wealth weather wednesday weekend weight western whatever whether window winter within without woman
        wonder wooden worker working world writer writing yellow yesterday yourself
    """.trim().split(Regex("\\s+")).toSet()
}
