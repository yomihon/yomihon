package mihon.domain.dictionary.interactor

import mihon.domain.dictionary.model.Dictionary

/**
 * Sorts dictionaries into a recommended order based on dictionary name patterns.
 * Adapted from https://github.com/MarvNC/yomichan-dictionaries/blob/master/sort-dictionaries.js
 *
 * Dictionaries are matched against regex patterns organized into groups.
 * Groups are ordered so that frequency dictionaries come first, then pitch accent,
 * then monolingual, bilingual, grammar, and kanji info dictionaries.
 * Unmatched dictionaries are appended at the end in their original relative order.
 */
object DictionaryAutoSorter {

    /**
     * Each entry is a regex pattern that matches a dictionary title.
     * The order of entries defines the final sort order (first = highest priority).
     */
    private val sortOrder: List<Regex> = listOf(
        // ── JA Frequency ──
        Regex("^JPDB$"),
        Regex("^Innocent Ranked$"),
        Regex("^Novels$"),
        Regex("^Youtube$"),
        Regex("^BCCWJ.*$"),
        Regex("^CC100$"),
        Regex("^青空文庫熟語$"),
        Regex("^Wikipedia$"),

        // ── JA Pitch ──
        Regex("^NHK$"),
        Regex("^大辞泉$"),
        Regex("^新明解第八版$"),
        Regex("^大辞林第四版$"),
        Regex("^三省堂国語辞典第八番$"),

        // ── Useful JA-JA ──
        Regex("^使い方の分かる 類語例解辞典$"),
        Regex("^数え方辞典オンライン$"),

        // ── JA-JA Monolingual ──
        Regex("^三省堂国語辞典.*$"),
        Regex("^新明解国語辞典.*$"),
        Regex("^漢検漢字辞典.*$"),
        Regex("^現代国語例解辞典.*$"),
        Regex("^岩波国語辞典.*$"),
        Regex("^広辞苑.*$"),
        Regex("^例解学習国語辞典.*$"),
        Regex("^デジタル大辞泉$"),
        Regex("^旺文社国語辞典.*$"),
        Regex("^国語辞典オンライン$"),
        Regex("^明鏡国語辞典.*$"),
        Regex("^大辞林.*$"),
        Regex("^新選国語辞典.*$"),
        Regex("^精選版.*日本国語大辞典$"),

        // ── Misc JA-JA ──
        Regex("^漢字源$"),
        Regex("^故事・ことわざ・慣用句オンライン$"),
        Regex("^四字熟語辞典オンライン$"),
        Regex("^類語辞典オンライン$"),
        Regex("^対義語辞典オンライン$"),
        Regex("^新明解四字熟語辞典$"),
        Regex("^学研 四字熟語辞典$"),
        Regex("^実用日本語表現辞典$"),
        Regex("^Pixiv.*$"),
        Regex("^JA Wikipedia.*$"),
        Regex("^日本語俗語辞書$"),
        Regex("^故事ことわざの辞典$"),
        Regex("^複合語起源$"),
        Regex("^surasura 擬声語$"),
        Regex("^語源由来辞典$"),
        Regex("^weblio古語辞典$"),
        Regex("^全国方言辞典$"),
        Regex("^新語時事用語辞典$"),

        // ── JA (Rare) ──
        Regex("^漢字林$"),
        Regex("^福日木健二字熟語$"),
        Regex("^全訳漢辞海$"),
        Regex("^KO字源$"),
        Regex("^YOJI-JUKUGO$"),
        Regex("^漢字でGo!.+$"),

        // ── JA-EN Bilingual ──
        Regex("^Jitendex.*$"),
        Regex("^NEW斎藤和英大辞典$"),
        Regex("^新和英$"),

        // ── JA Names ──
        Regex("^JMnedict$"),

        // ── JA Grammar ──
        Regex("^日本語文法辞典.*$"),
        Regex("^絵でわかる日本語$"),
        Regex("^JLPT文法解説まとめ$"),
        Regex("^どんなときどう使う 日本語表現文型辞典$"),
        Regex("^毎日のんびり日本語教師$"),

        // ── JA Kanji Frequency ──
        Regex("^Innocent Corpus Kanji$"),
        Regex("^Wikipedia Kanji$"),
        Regex("^青空文庫漢字$"),
        Regex("^JPDB Kanji Freq$"),

        // ── JA Kanji Info ──
        Regex("^漢字辞典オンライン$"),
        Regex("^KANJIDIC.*$"),
        Regex("^JPDB Kanji$"),
        Regex("^mozc Kanji Variants$"),
        Regex("^jitai$"),
        Regex("^TheKanjiMap Kanji Radicals.*$"),
        Regex("^Wiktionary漢字$"),

        // ── YUE Frequency ──
        Regex("^Words\\.hk Frequency$"),
        Regex("^Cifu Spoken$"),
        Regex("^Cifu Written$"),

        // ── YUE ──
        Regex("^Words\\.hk 粵典 \\[.*\\]$"),
        Regex("^CantoDict$"),
        Regex("^Canto CEDICT$"),
        Regex("^CE Wiktionary$"),
        Regex("^CC-Canto$"),
        Regex("^Words\\.hk 粵典 漢字.*$"),

        // ── ZH Frequency ──
        Regex("^HSK$"),
        Regex("^BLCUlit$"),
        Regex("^SUBTLEX-CH$"),
        Regex("^BLCUcoll$"),
        Regex("^BLCUmixed$"),
        Regex("^BLCUnews$"),
        Regex("^BLCUsci$"),

        // ── ZH-EN ──
        Regex("^CC-CEDICT(?! Hanzi).*$"),
        Regex("^Wenlin ABC$"),

        // ── ZH-JA ──
        Regex("^中日大辞典.*$"),
        Regex("^白水社中国語辞典$"),

        // ── ZH-ZH ──
        Regex("^漢語大詞典$"),
        Regex("^萌典国语辞典$"),
        Regex("^兩岸詞典$"),
        Regex("^牛津英汉汉英词典$"),
        Regex("^五南國語活用辭典$"),
        Regex("^萌典$"),
        Regex("^譯典通英漢雙向字典$"),
        Regex("^现代汉语规范词典$"),
        Regex("^康熙字典$"),
        Regex("^辭源$"),
        Regex("^ZH Wikipedia.*$"),

        // ── ZH Hanzi Info ──
        Regex("^CC-CEDICT Hanzi.*$"),
        Regex("^ZH Wiktionary Hanzi$"),
    )

    /**
     * Returns a new list of dictionaries sorted according to the recommended order.
     * Each dictionary is assigned a new priority value (1-based, ascending).
     * Dictionaries that don't match any pattern keep their relative order and are placed at the end.
     */
    fun sort(dictionaries: List<Dictionary>): List<Dictionary> {
        val remaining = dictionaries.toMutableList()
        val sorted = mutableListOf<Dictionary>()

        for (pattern in sortOrder) {
            val matched = remaining.filter { pattern.matches(it.title) }
            sorted.addAll(matched)
            remaining.removeAll(matched)
        }

        // Append unmatched dictionaries at the end
        sorted.addAll(remaining)

        // Assign new priorities (1 = first/highest priority)
        return sorted.mapIndexed { index, dict ->
            dict.copy(priority = index + 1)
        }
    }
}
