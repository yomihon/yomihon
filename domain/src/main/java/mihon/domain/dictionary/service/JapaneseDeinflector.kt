package mihon.domain.dictionary.service

/**
 * Japanese deinflector (deconjugator) for Japanese verbs and adjectives.
 * Most of the deinflection rules are sourced from Jiten (https://github.com/Sirush/Jiten).
 */

// Primary word classes (dictionary forms)
const val FORM_NONE = 0L

// Verb type tags from JMdict/EDICT
const val V5K = 1L shl 0 // 五段 く verbs
const val V5G = 1L shl 1 // 五段 ぐ verbs
const val V5S = 1L shl 2 // 五段 す verbs
const val V5T = 1L shl 3 // 五段 つ verbs
const val V5N = 1L shl 4 // 五段 ぬ verbs
const val V5B = 1L shl 5 // 五段 ぶ verbs
const val V5M = 1L shl 6 // 五段 む verbs
const val V5R = 1L shl 7 // 五段 る verbs
const val V5U = 1L shl 8 // 五段 う verbs
const val V5RI = 1L shl 9 // 五段 る irregular (ある)
const val V5US = 1L shl 10 // 五段 う special (問う)
const val V5KS = 1L shl 11 // 五段 く special (行く)
const val V5ARU = 1L shl 12 // 五段 ある conjugation
const val V1 = 1L shl 13 // 一段 verbs
const val V1S = 1L shl 14 // 一段 くれる special
const val VK = 1L shl 15 // くる verb
const val VSI = 1L shl 16 // する verb (irregular)
const val VSS = 1L shl 17 // する verb (special)
const val VSC = 1L shl 18 // す verb (classical)
const val VZ = 1L shl 19 // ずる verb
const val V4R = 1L shl 20 // 四段 る (classical)

// Adjective types
const val ADJ_I = 1L shl 21 // い-adjective
const val ADJ_NA = 1L shl 22 // な-adjective
const val ADJ_PN = 1L shl 23 // pre-noun adjective

// Special/intermediate forms
const val STEM_REN = 1L shl 24 // 連用形 (continuative/masu stem)
const val STEM_MIZENKEI = 1L shl 25 // 未然形 (irrealis/negative stem)
const val STEM_E = 1L shl 26 // え stem (izenkei)
const val STEM_A = 1L shl 27 // あ stem
const val STEM_KU = 1L shl 28 // く adverbial stem
const val STEM_TE = 1L shl 29 // て form
const val STEM_PAST = 1L shl 30 // た form (past base)
const val STEM_ADJ_BASE = 1L shl 31
const val STEM_REN_V: Long = STEM_REN

// Composite masks
const val V5 = V5K or V5G or V5S or V5T or V5N or V5B or V5M or V5R or V5U or V5RI or V5US or V5KS or V5ARU
const val VERB = V5 or V1 or V1S or VK or VSI or VSS or VSC or VZ or V4R
const val ALL_STEMS = STEM_REN or STEM_MIZENKEI or STEM_E or STEM_A or STEM_KU or STEM_TE or STEM_PAST or STEM_ADJ_BASE

/**
 * Represents a single deinflection rule.
 */
private data class Rule(
    val fromEnding: String,
    val toEnding: String,
    val fromTags: Long,
    val toTags: Long,
    val detail: String,
    val type: RuleType,
)

private enum class RuleType {
    STANDARD, // Normal rule, can chain
    NEVER_FINAL, // Result cannot be a dictionary form
    ONLY_FINAL, // Can only apply to original input
    CONTEXT, // Requires special context handling
    REWRITE, // Only applies when the entire term matches (Deconjugator.cs rewriterule)
}

/**
 * Represents a deinflection candidate.
 */
data class Candidate(
    val term: String,
    val conditions: Long,
    val reasons: ArrayDeque<String> = ArrayDeque(),
    val alternateReasonChains: Set<List<String>> = emptySet(),
    val canBeFinal: Boolean = true,
    val hasAppliedRule: Boolean = false, // Tracks whether any rule has been applied to reach this candidate
)

/**
 * Object containing word class constants and utility functions.
 */
object InflectionType {
    const val ALL = 0L
    const val UNSPECIFIED = FORM_NONE

    fun conditionsMatch(candidateConditions: Long, expectedConditions: Long): Boolean {
        if (candidateConditions == 0L || expectedConditions == 0L) return true
        return (candidateConditions and expectedConditions) != 0L
    }

    fun parseRules(rules: String?): Long {
        if (rules.isNullOrBlank()) return UNSPECIFIED

        var mask = 0L
        for (rule in rules.split(" ", ",")) {
            mask = mask or tagToMask(rule.trim())
        }
        return mask
    }
}

/**
 * Maps JMdict/EDICT tags to bitmasks.
 */
private fun tagToMask(tag: String): Long = when (tag) {
    "v5k" -> V5K
    "v5g" -> V5G
    "v5s" -> V5S
    "v5t" -> V5T
    "v5n" -> V5N
    "v5b" -> V5B
    "v5m" -> V5M
    "v5r" -> V5R
    "v5u" -> V5U
    "v5r-i" -> V5RI
    "v5u-s" -> V5US
    "v5k-s" -> V5KS
    "v5aru" -> V5ARU
    "v1" -> V1
    "v1-s" -> V1S
    "vk" -> VK
    "vs-i" -> VSI
    "vs-s" -> VSS
    "vs-c" -> VSC
    "vz" -> VZ
    "v4r" -> V4R
    "adj-i" -> ADJ_I
    "adj-na" -> ADJ_NA
    "adj-pn" -> ADJ_PN
    "stem-ren" -> STEM_REN
    "stem-mizenkei" -> STEM_MIZENKEI
    "stem-e", "stem-izenkei" -> STEM_E
    "stem-a" -> STEM_A
    "stem-ku" -> STEM_KU
    "stem-te", "stem-te-verbal", "stem-te-defective" -> STEM_TE
    "stem-past" -> STEM_PAST
    "stem-ren-less", "stem-ren-less-v" -> STEM_REN // Map to continuative
    "stem-ka", "stem-ke", "stem-adj-base" -> STEM_ADJ_BASE
    "uninflectable", "exp", "n" -> FORM_NONE // Uninflectable forms
    "topic-condition" -> STEM_TE // Map to te form for chaining
    "form-volition" -> FORM_NONE // Volitional is uninflectable
    else -> FORM_NONE
}

/**
 * Japanese verb and adjective deinflector.
 * Converts inflected forms back to potential dictionary forms.
 */
object JapaneseDeinflector {

    /**
     * Deinflects a word and returns all potential dictionary form candidates.
     */
    fun deinflect(source: String): List<Candidate> {
        if (source.isBlank()) return emptyList()

        val results = mutableMapOf<String, Candidate>()

        // Add the source itself as a candidate
        results[source] = Candidate(
            term = source,
            conditions = 0L,
            reasons = ArrayDeque(),
            canBeFinal = true,
            hasAppliedRule = false,
        )

        val queue = ArrayDeque<Candidate>()
        queue.add(results[source]!!)

        val processed = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            // This avoids runaway expansions.
            if (current.term.length > source.length + 10) continue

            val processKey = "${current.term}:${current.conditions}"
            if (processKey in processed) continue
            processed.add(processKey)

            val term = current.term
            val termLen = term.length

            // Process rules by checking suffixes of the current term
            for (suffixLen in 0..minOf(termLen, MAX_SUFFIX_LENGTH)) {
                val suffix = if (suffixLen == 0) "" else term.takeLast(suffixLen)
                val rulesForSuffix = RULES_BY_SUFFIX[suffix] ?: continue

                for (rule in rulesForSuffix) {
                    if (rule.type == RuleType.ONLY_FINAL && current.hasAppliedRule) {
                        continue
                    }

                    // never_final rules only apply once we have started tagging.
                    // This prevents untagged inputs from being incorrectly interpreted as intermediate stems.
                    if (rule.type == RuleType.NEVER_FINAL && !current.hasAppliedRule) {
                        continue
                    }

                    // Rewrite rules are only applicable when the entire term matches the pattern.
                    if (rule.type == RuleType.REWRITE && term != rule.fromEnding) {
                        continue
                    }

                    if (!current.hasAppliedRule && current.conditions == 0L && rule.type == RuleType.STANDARD &&
                        rule.detail.isBlank()
                    ) {
                        continue
                    }

                    if (rule.type == RuleType.CONTEXT && !passesContextRule(rule, current, term, suffixLen)) {
                        continue
                    }

                    // Check if input conditions match
                    if (rule.toTags != 0L && current.conditions != 0L &&
                        (current.conditions and rule.toTags) == 0L
                    ) {
                        continue
                    }

                    val stem = term.dropLast(suffixLen)
                    val newTerm = stem + rule.toEnding

                    if (newTerm.isEmpty()) continue

                    val newReasons = if (rule.detail.isNotBlank()) {
                        ArrayDeque(current.reasons).also { it.addLast(rule.detail) }
                    } else {
                        ArrayDeque(current.reasons)
                    }
                    val newConditions = rule.fromTags

                    val outputIsAnyStem = (newConditions and ALL_STEMS) != 0L
                    val outputIsDictionaryForm = !outputIsAnyStem && newConditions != 0L
                    val outputIsRenyouStem = (newConditions and STEM_REN) != 0L
                    val canBeFinal = when (rule.type) {
                        RuleType.NEVER_FINAL -> outputIsDictionaryForm
                        // For standard/context rules, intermediate stems should not be returned as final candidates.
                        // Ren'you (masu) stems are kept as they can function as nouns.
                        RuleType.ONLY_FINAL -> outputIsDictionaryForm || outputIsRenyouStem
                        RuleType.STANDARD, RuleType.CONTEXT, RuleType.REWRITE -> !outputIsAnyStem || outputIsRenyouStem
                    }

                    val newProcessKey = "$newTerm:$newConditions"
                    if (newProcessKey in processed) continue

                    val newCandidate = Candidate(
                        term = newTerm,
                        conditions = newConditions,
                        reasons = newReasons,
                        canBeFinal = canBeFinal,
                        hasAppliedRule = true, // Mark that a rule has been applied
                    )
                    queue.add(newCandidate)

                    // Update results
                    val existing = results[newTerm]
                    if (existing == null) {
                        results[newTerm] = newCandidate
                    } else if (existing.reasons.size > newReasons.size) {
                        results[newTerm] = newCandidate
                    } else if (existing.reasons.size == newReasons.size) {
                        // Combine conditions and prefer final-eligible
                        val combinedConditions = existing.conditions or newConditions
                        val preferFinal = canBeFinal || existing.canBeFinal

                        if (combinedConditions != existing.conditions || preferFinal != existing.canBeFinal) {
                            val allChains = LinkedHashSet<List<String>>()
                            allChains.add(existing.reasons.toList())
                            allChains.addAll(existing.alternateReasonChains)
                            allChains.add(newReasons.toList())

                            val primaryChain = allChains.minWithOrNull(
                                compareBy(
                                    { it.size },
                                    { it.joinToString("\u0000") },
                                ),
                            ) ?: existing.reasons.toList()

                            val alternateChains = allChains
                                .asSequence()
                                .filterNot { it == primaryChain }
                                .toSet()

                            val mergedCandidate = existing.copy(
                                conditions = combinedConditions,
                                canBeFinal = preferFinal,
                                reasons = ArrayDeque(primaryChain),
                                alternateReasonChains = alternateChains,
                            )
                            results[newTerm] = mergedCandidate
                        }
                    }
                }
            }
        }

        // Only return candidates that can be final dictionary forms
        return results.values.filter { it.canBeFinal }.toList()
    }

    private fun passesContextRule(rule: Rule, current: Candidate, term: String, suffixLen: Int): Boolean {
        // Block "teru (teiru)" only when the current tag is exactly stem-ren.
        // Don't treat "さす" as "(a-stem)+す".
        return when {
            rule.detail == "teru (teiru)" && rule.fromEnding == "る" -> {
                current.conditions != STEM_REN
            }
            rule.detail == "short causative" && rule.fromEnding == "す" -> {
                val prefixLength = term.length - suffixLen
                prefixLength <= 0 || term.getOrNull(prefixLength - 1) != 'さ'
            }
            else -> true
        }
    }

    private const val MAX_SUFFIX_LENGTH = 9 // Longest suffix in rules

    private val RULES_BY_SUFFIX: Map<String, List<Rule>> by lazy {
        ALL_RULES.groupBy { it.fromEnding }
    }

    private val ALL_RULES: List<Rule> = buildList {
        // Izenkei (realis/conditional stem) - neverfinalrule
        add(Rule("け", "く", V5K, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("せ", "す", V5S, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("て", "つ", V5T, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("え", "う", V5U, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("れ", "る", V5R or V5RI or V5ARU or V4R, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("げ", "ぐ", V5G, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("べ", "ぶ", V5B, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("ね", "ぬ", V5N, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("め", "む", V5M, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("れ", "る", V1 or V1S, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("え", "う", V5US, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("け", "く", V5KS, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("すれ", "する", VSS or VSI, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("せ", "する", VSS or VSI, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("くれ", "くる", VK, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("来れ", "来る", VK, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("來れ", "來る", VK, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))
        add(Rule("ずれ", "ずる", VZ, STEM_E, "(izenkei)", RuleType.NEVER_FINAL))

        // Mizenkei (irrealis/negative stem) - neverfinalrule
        add(Rule("", "", STEM_A, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))
        add(Rule("", "る", V1 or V1S, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))
        add(Rule("こ", "くる", VK, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))
        add(Rule("来", "来る", VK, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))
        add(Rule("來", "來る", VK, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))
        add(Rule("し", "する", VSI, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))
        add(Rule("じ", "ずる", VZ, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))
        add(Rule("せ", "す", VSC, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))
        add(Rule("し", "する", VSS, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))
        add(Rule("せ", "する", VSS, STEM_MIZENKEI, "", RuleType.NEVER_FINAL))

        // A-stem (mizenkei base) - neverfinalrule
        add(Rule("か", "く", V5K, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("さ", "す", V5S, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("た", "つ", V5T, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("わ", "う", V5U, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("ら", "る", V5R or V5RI or V5ARU or V4R, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("が", "ぐ", V5G, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("ば", "ぶ", V5B, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("な", "ぬ", V5N, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("ま", "む", V5M, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("わ", "う", V5US, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("か", "く", V5KS, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("しさ", "する", VSS, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))
        add(Rule("さ", "する", VSS or VSI, STEM_A, "('a' stem)", RuleType.NEVER_FINAL))

        // Ren'youkei (continuative/masu stem) - stdrule (can be final as noun)
        add(Rule("き", "く", V5K, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("し", "す", V5S, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("ち", "つ", V5T, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("い", "う", V5U, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("り", "る", V5R or V5RI or V5ARU or V4R, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("ぎ", "ぐ", V5G, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("び", "ぶ", V5B, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("に", "ぬ", V5N, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("み", "む", V5M, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("い", "う", V5US, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("き", "く", V5KS, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("き", "くる", VK, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("来", "来る", VK, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("來", "來る", VK, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("し", "する", VSI or VSS, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("", "る", V1 or V1S, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("い", "る", V5ARU, STEM_REN, "(infinitive)", RuleType.STANDARD))
        add(Rule("じ", "ずる", VZ, STEM_REN, "(infinitive)", RuleType.STANDARD))

        // Ren'youkei (unstressed/geminate stem) - neverfinalrule
        add(Rule("い", "く", V5K, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("し", "す", V5S, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("っ", "つ", V5T, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("っ", "う", V5U, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("っ", "る", V5R or V5RI, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("い", "ぐ", V5G, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("ん", "ぶ", V5B, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("ん", "ぬ", V5N, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("ん", "む", V5M, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("う", "う", V5US, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("っ", "く", V5KS, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("し", "する", VSI or VSS, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))
        add(Rule("っ", "る", V4R, STEM_REN, "(unstressed infinitive)", RuleType.NEVER_FINAL))

        // Adjective stems
        add(Rule("く", "い", ADJ_I, STEM_KU, "(adverbial stem)", RuleType.STANDARD))
        add(Rule("か", "い", ADJ_I, ADJ_I or STEM_ADJ_BASE, "(ka stem)", RuleType.STANDARD))
        add(Rule("け", "い", ADJ_I, ADJ_I or STEM_ADJ_BASE, "(ke stem)", RuleType.STANDARD))
        add(Rule("", "い", ADJ_I, STEM_ADJ_BASE, "(stem)", RuleType.STANDARD))

        // Volitional
        add(Rule("こう", "く", V5K, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("そう", "す", V5S, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("とう", "つ", V5T, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("おう", "う", V5U, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("ろう", "る", V5R or V5RI or V5ARU or V4R, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("ごう", "ぐ", V5G, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("ぼう", "ぶ", V5B, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("のう", "ぬ", V5N, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("もう", "む", V5M, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("よう", "る", V1 or V1S, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("おう", "う", V5US, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("こう", "く", V5KS, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("こよう", "くる", VK, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("来よう", "来る", VK, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("來よう", "來る", VK, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("しよう", "する", VSI or VSS, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("そう", "する", VSI or VSS, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("しよ", "する", VSI, FORM_NONE, "shortened volitional", RuleType.STANDARD))
        add(Rule("せむ", "す", VSC, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("じよう", "ずる", VZ, FORM_NONE, "volitional", RuleType.STANDARD))
        add(Rule("かろう", "い", ADJ_I, FORM_NONE, "presumptive", RuleType.STANDARD))

        // Imperative - onlyfinalrule
        add(Rule("け", "く", V5K, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("せ", "す", V5S, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("て", "つ", V5T, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("え", "う", V5U or V5US, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("れ", "る", V5R or V5RI or V4R, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("げ", "ぐ", V5G, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("べ", "ぶ", V5B, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("ね", "ぬ", V5N, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("め", "む", V5M, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("ろ", "る", V1, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("よ", "る", V1, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("れ", "れる", V1S, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("け", "く", V5KS, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("こい", "くる", VK, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("来い", "来る", VK, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("來い", "來る", VK, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("しろ", "する", VSI, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("せよ", "する", VSI or VSS or VSC, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("せ", "する", VSI or VSS, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("い", "る", V5ARU, FORM_NONE, "imperative", RuleType.ONLY_FINAL))
        add(Rule("ぜよ", "ずる", VZ, FORM_NONE, "imperative", RuleType.ONLY_FINAL))

        // Te-form (through stem)
        add(Rule("て", "", STEM_REN, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("って", "", STEM_REN, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("で", "", STEM_REN, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("いて", "っ", STEM_REN, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("くて", "い", ADJ_I, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("で", "", STEM_KU, STEM_TE, "(te form)", RuleType.STANDARD))

        // Direct te-form rules for godan verbs (bypasses intermediate stems)
        add(Rule("いて", "く", V5K, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("いで", "ぐ", V5G, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("して", "す", V5S, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("って", "つ", V5T, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("って", "う", V5U, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("って", "る", V5R, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("んで", "ぶ", V5B, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("んで", "ぬ", V5N, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("んで", "む", V5M, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("うて", "う", V5US, STEM_TE, "(te form)", RuleType.STANDARD))
        add(Rule("って", "く", V5KS, STEM_TE, "(te form)", RuleType.STANDARD))

        // Ta-form (through stem)
        add(Rule("た", "", STEM_REN, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("った", "", STEM_REN, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("だ", "", STEM_REN, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("かった", "", STEM_ADJ_BASE, FORM_NONE, "past", RuleType.STANDARD))

        // Direct ta-form rules for godan verbs (bypasses intermediate stems)
        add(Rule("いた", "く", V5K, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("いだ", "ぐ", V5G, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("した", "す", V5S, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("った", "つ", V5T, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("った", "う", V5U, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("った", "る", V5R, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("んだ", "ぶ", V5B, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("んだ", "ぬ", V5N, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("んだ", "む", V5M, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("うた", "う", V5US, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("った", "く", V5KS, STEM_PAST, "past", RuleType.STANDARD))
        add(Rule("いた", "く", V5KS, STEM_PAST, "past", RuleType.STANDARD))

        // Negative
        add(Rule("ない", "", STEM_MIZENKEI, ADJ_I, "negative", RuleType.STANDARD))
        add(Rule("ねえ", "", STEM_MIZENKEI, ADJ_I, "slang negative", RuleType.STANDARD))
        add(Rule("ん", "", STEM_MIZENKEI, ADJ_I, "slurred negative", RuleType.STANDARD))
        add(Rule("ない", "", STEM_KU, ADJ_I, "negative", RuleType.STANDARD))
        add(Rule("ざる", "", STEM_MIZENKEI, ADJ_PN, "negative", RuleType.STANDARD))
        add(Rule("せざる", "する", VSI, ADJ_PN, "negative", RuleType.REWRITE))

        // Adverbial negative
        add(Rule("ず", "", STEM_MIZENKEI, FORM_NONE, "adverbial negative", RuleType.ONLY_FINAL))
        add(Rule("ずに", "", STEM_MIZENKEI, FORM_NONE, "without doing so", RuleType.ONLY_FINAL))
        add(Rule("ぬ", "", STEM_MIZENKEI, FORM_NONE, "archaic negative", RuleType.ONLY_FINAL))
        add(Rule("せず", "する", VSI, FORM_NONE, "adverbial negative", RuleType.REWRITE))
        add(Rule("せずに", "する", VSI, FORM_NONE, "without doing so", RuleType.REWRITE))
        add(Rule("せぬ", "する", VSI, FORM_NONE, "archaic negative", RuleType.REWRITE))

        // Kansai-ben negative
        add(Rule("へん", "", STEM_MIZENKEI, FORM_NONE, "negative (kansaiben)", RuleType.STANDARD))
        add(Rule("へんかった", "", STEM_MIZENKEI, FORM_NONE, "negative past (kansaiben)", RuleType.STANDARD))

        // Polite forms - onlyfinalrule
        add(Rule("ます", "", STEM_REN, FORM_NONE, "polite", RuleType.ONLY_FINAL))
        add(Rule("ません", "", STEM_REN, FORM_NONE, "negative polite", RuleType.ONLY_FINAL))
        add(Rule("ました", "", STEM_REN, FORM_NONE, "past polite", RuleType.ONLY_FINAL))
        add(Rule("まして", "", STEM_REN, FORM_NONE, "te polite", RuleType.ONLY_FINAL))
        add(Rule("ませんでした", "", STEM_REN, FORM_NONE, "past negative polite", RuleType.ONLY_FINAL))
        add(Rule("ましょう", "", STEM_REN, FORM_NONE, "polite volitional", RuleType.ONLY_FINAL))
        add(Rule("ましゅ", "", STEM_REN, FORM_NONE, "polite (childish)", RuleType.ONLY_FINAL))

        // Formal adjective negative
        add(Rule("ありません", "", STEM_KU, FORM_NONE, "formal negative", RuleType.STANDARD))
        add(Rule("ありませんでした", "", STEM_KU, FORM_NONE, "formal negative past", RuleType.STANDARD))

        // Conditional -たら - onlyfinalrule
        add(Rule("たら", "", STEM_REN, FORM_NONE, "conditional", RuleType.ONLY_FINAL))
        add(Rule("だら", "", STEM_REN, FORM_NONE, "conditional", RuleType.ONLY_FINAL))
        add(Rule("かったら", "", STEM_ADJ_BASE, FORM_NONE, "conditional", RuleType.ONLY_FINAL))
        add(Rule("たらば", "", STEM_REN, FORM_NONE, "formal conditional", RuleType.ONLY_FINAL))
        add(Rule("だらば", "", STEM_REN, FORM_NONE, "formal conditional", RuleType.ONLY_FINAL))
        add(Rule("かったらば", "", STEM_ADJ_BASE, FORM_NONE, "formal conditional", RuleType.ONLY_FINAL))

        // Conditional -ば
        add(Rule("ば", "", STEM_E, FORM_NONE, "provisional conditional", RuleType.STANDARD))
        add(Rule("れば", "", ADJ_I, FORM_NONE, "provisional conditional", RuleType.STANDARD))

        // Potential
        add(Rule("る", "", STEM_E, V1, "potential", RuleType.STANDARD))
        add(Rule("りえる", "る", V5RI, V1, "potential", RuleType.STANDARD))
        add(Rule("これる", "くる", VK, V1, "potential", RuleType.STANDARD))
        add(Rule("来れる", "来る", VK, V1, "potential", RuleType.STANDARD))
        add(Rule("來れる", "來る", VK, V1, "potential", RuleType.STANDARD))
        add(Rule("できる", "する", VSI, FORM_NONE, "potential", RuleType.STANDARD))
        add(Rule("出来る", "する", VSI, FORM_NONE, "potential", RuleType.STANDARD))
        add(Rule("出きる", "する", VSI, FORM_NONE, "potential", RuleType.STANDARD))
        add(Rule("出來る", "する", VSI, FORM_NONE, "potential", RuleType.STANDARD))

        // Passive
        add(Rule("れる", "", STEM_A, V1, "passive", RuleType.STANDARD))
        add(Rule("られる", "る", V1 or V1S, V1, "passive/potential", RuleType.STANDARD))
        add(Rule("ぜられる", "ずる", VZ, V1, "passive/potential", RuleType.STANDARD))
        add(Rule("ざれる", "ずる", VZ, V1, "passive/potential", RuleType.STANDARD))
        add(Rule("せられる", "する", VSS, V1, "passive/potential", RuleType.STANDARD))
        add(Rule("しられる", "する", VSS, V1, "passive/potential", RuleType.STANDARD))
        add(Rule("こられる", "くる", VK, V1, "passive/potential", RuleType.STANDARD))
        add(Rule("来られる", "来る", VK, V1, "passive/potential", RuleType.STANDARD))
        add(Rule("來られる", "來る", VK, V1, "passive/potential", RuleType.STANDARD))

        // Causative
        add(Rule("せる", "", STEM_A, V1, "causative", RuleType.STANDARD))
        add(Rule("させる", "る", V1 or V1S, V1, "causative", RuleType.STANDARD))
        add(Rule("こさせる", "くる", VK, V1, "causative", RuleType.STANDARD))
        add(Rule("来させる", "来る", VK, V1, "causative", RuleType.STANDARD))
        add(Rule("來させる", "來る", VK, V1, "causative", RuleType.STANDARD))

        // Short causative
        add(Rule("す", "", STEM_A, V5S, "short causative", RuleType.CONTEXT))
        add(Rule("さす", "る", V1 or V1S, V1, "short causative", RuleType.STANDARD))
        add(Rule("さす", "する", VSS or VSI, V1, "short causative", RuleType.STANDARD))
        add(Rule("しさす", "する", VSS, V1, "short causative", RuleType.STANDARD))
        add(Rule("さす", "", VK, V1, "short causative", RuleType.STANDARD))
        add(Rule("さす", "す", V5S, V1, "short causative", RuleType.STANDARD))

        // -たい (want to)
        add(Rule("たい", "", STEM_REN, ADJ_I, "want", RuleType.STANDARD))

        // -すぎる (too much)
        add(Rule("すぎる", "", STEM_REN, V1, "too much", RuleType.STANDARD))
        add(Rule("すぎる", "", STEM_ADJ_BASE, V1, "excess", RuleType.STANDARD))

        // -そう (seemingness)
        add(Rule("そう", "", STEM_ADJ_BASE, ADJ_NA, "seemingness", RuleType.STANDARD))
        add(Rule("そう", "", STEM_REN, V1, "seemingness", RuleType.STANDARD))

        // -がる
        add(Rule("がる", "", STEM_ADJ_BASE, V5R, "garu", RuleType.STANDARD))

        // -さ (noun form)
        add(Rule("さ", "", STEM_ADJ_BASE, FORM_NONE, "noun form", RuleType.STANDARD))

        // Te-form auxiliaries
        add(Rule("しまう", "", STEM_TE, V5U, "finish/completely/end up", RuleType.STANDARD))
        add(Rule("ください", "", STEM_TE, ADJ_I, "polite request", RuleType.STANDARD))
        add(Rule("なさい", "", STEM_REN, FORM_NONE, "polite command", RuleType.STANDARD))
        add(Rule("あげる", "", STEM_TE, V5R, "do for someone", RuleType.STANDARD))
        add(Rule("いる", "", STEM_TE, V1, "teiru", RuleType.STANDARD))
        add(Rule("る", "", STEM_TE, V1, "teru (teiru)", RuleType.CONTEXT))
        add(Rule("おる", "", STEM_TE, V5R, "teoru", RuleType.STANDARD))
        add(Rule("とる", "て", STEM_TE, V5R, "toru (teoru)", RuleType.STANDARD))
        add(Rule("どる", "で", STEM_TE, V5R, "toru (teoru)", RuleType.STANDARD))
        add(Rule("ある", "", STEM_TE, V5RI, "tearu", RuleType.STANDARD))
        add(Rule("いく", "", STEM_TE, V5KS, "teiku", RuleType.STANDARD))
        add(Rule("く", "", STEM_TE, V5KS, "teku (teiku)", RuleType.STANDARD))
        add(Rule("くる", "", STEM_TE, VK, "tekuru", RuleType.STANDARD))
        add(Rule("おく", "", STEM_TE, V5K, "for now", RuleType.STANDARD))
        add(Rule("とく", "て", STEM_TE, V5K, "toku (for now)", RuleType.STANDARD))
        add(Rule("どく", "で", STEM_TE, V5K, "toku (for now)", RuleType.STANDARD))
        add(Rule("は", "", STEM_TE, STEM_TE, "topic/condition", RuleType.STANDARD))

        // -まい (negative volition)
        add(Rule("まい", "", V1, FORM_NONE, "mai", RuleType.STANDARD))
        add(Rule("しまい", "する", VSI, FORM_NONE, "mai", RuleType.STANDARD))
        add(Rule("すまい", "する", VSI, FORM_NONE, "mai", RuleType.STANDARD))
        add(
            Rule(
                "まい",
                "",
                V5 or V1 or VK or VSI or VSS or VZ,
                FORM_NONE,
                "negative volition/conjecture",
                RuleType.ONLY_FINAL,
            ),
        )

        // -な (command/request)
        add(Rule("な", "", STEM_REN, FORM_NONE, "casual kind request", RuleType.ONLY_FINAL))

        // -ながら (while)
        add(Rule("ながら", "", STEM_REN, FORM_NONE, "while", RuleType.ONLY_FINAL))

        // -たり
        add(Rule("たり", "", STEM_REN, FORM_NONE, "tari", RuleType.ONLY_FINAL))
        add(Rule("だり", "", STEM_REN, FORM_NONE, "tari", RuleType.ONLY_FINAL))
        add(Rule("かったり", "", STEM_ADJ_BASE, FORM_NONE, "tari", RuleType.ONLY_FINAL))

        // Contractions
        add(Rule("ちゃう", "てしまう", V5U, V5U, "contracted", RuleType.STANDARD))
        add(Rule("じゃう", "でしまう", V5U, V5U, "contracted", RuleType.STANDARD))
        add(Rule("ちまう", "てしまう", V5U, V5U, "contracted", RuleType.STANDARD))
        add(Rule("じまう", "でしまう", V5U, V5U, "contracted", RuleType.STANDARD))
        add(Rule("ちゃ", "ては", STEM_TE, FORM_NONE, "contracted", RuleType.STANDARD))
        add(Rule("じゃ", "では", STEM_TE, FORM_NONE, "contracted", RuleType.STANDARD))
        add(Rule("けりゃ", "ければ", FORM_NONE, FORM_NONE, "contracted", RuleType.ONLY_FINAL))
        add(Rule("きゃ", "ければ", FORM_NONE, FORM_NONE, "contracted", RuleType.ONLY_FINAL))
        add(Rule("てりゃ", "ていれば", FORM_NONE, FORM_NONE, "contracted conditional (te-ireba)", RuleType.STANDARD))
        add(Rule("でりゃ", "でいれば", FORM_NONE, FORM_NONE, "contracted conditional (te-ireba)", RuleType.STANDARD))

        // Slurred forms
        add(Rule("ん", "る", V1 or V1S or V5R or V5ARU or VK, FORM_NONE, "slurred", RuleType.ONLY_FINAL))
        add(Rule("んない", "らない", ADJ_I, ADJ_I, "slang negative", RuleType.STANDARD))
        add(Rule("んなければ", "らなければ", FORM_NONE, FORM_NONE, "slurred negative conditional", RuleType.STANDARD))
        add(Rule("らんない", "られない", ADJ_I, ADJ_I, "slang negative", RuleType.STANDARD))
        add(Rule("んなきゃ", "らなきゃ", FORM_NONE, FORM_NONE, "slang negative conditional", RuleType.STANDARD))

        // Slang vowel shifts (い → え/ぇ patterns)
        add(Rule("ねえ", "ない", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("ねぇ", "ない", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("てえ", "たい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("てぇ", "たい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("けえ", "かい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("けぇ", "かい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("げえ", "がい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("げぇ", "がい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("せえ", "さい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("せぇ", "さい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("めえ", "まい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("めぇ", "まい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("べえ", "ばい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("べぇ", "ばい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("れえ", "らい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("れぇ", "らい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("でえ", "どい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("でぇ", "どい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("ちぇえ", "ちゃい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("みい", "むい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("みぃ", "むい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("ちい", "つい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("ちぃ", "つい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("ぜえ", "ずい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("ぜぇ", "ずい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("っぜえ", "ずい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))
        add(Rule("っぜぇ", "ずい", ADJ_I, ADJ_I, "slang (ee/ii)", RuleType.STANDARD))

        // いい/よい irregular
        add(Rule("ええ", "いい", ADJ_I, ADJ_I, "slang (irregular)", RuleType.STANDARD))
        add(Rule("ええ", "よい", ADJ_I, ADJ_I, "slang (irregular)", RuleType.STANDARD))
        add(Rule("いぇえ", "よい", ADJ_I, ADJ_I, "slang (irregular)", RuleType.STANDARD))
        add(Rule("うぇえ", "わい", ADJ_I, ADJ_I, "slang (irregular)", RuleType.STANDARD))

        // Kansai-ben contracted volitional
        add(Rule("したろ", "する", VSI, FORM_NONE, "contracted -te yarou (kansaiben)", RuleType.STANDARD))
        add(Rule("したろう", "する", VSI, FORM_NONE, "contracted -te yarou (kansaiben)", RuleType.STANDARD))

        // desu forms
        add(Rule("でした", "です", FORM_NONE, STEM_PAST, "past", RuleType.REWRITE))
        add(Rule("でして", "です", FORM_NONE, STEM_TE, "(te form)", RuleType.REWRITE))

        // Noun + suru
        add(Rule("する", "", FORM_NONE, VSI or VSS, "(suru verb noun stem)", RuleType.STANDARD))
    }
}
