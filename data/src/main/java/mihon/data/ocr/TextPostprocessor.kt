package mihon.data.ocr

// Half-width to full-width conversion mappings for Japanese text
private val HALF_TO_FULL_TABLE = CharArray(127) { it.toChar() }.apply {
    this['!'.code] = '！'
    this['"'.code] = '"'
    this['#'.code] = '＃'
    this['$'.code] = '＄'
    this['%'.code] = '％'
    this['&'.code] = '＆'
    this['\''.code] = '\''
    this['('.code] = '（'
    this[')'.code] = '）'
    this['*'.code] = '＊'
    this['+'.code] = '＋'
    this[','.code] = '，'
    this['-'.code] = '－'
    this['.'.code] = '．'
    this['/'.code] = '／'
    this['0'.code] = '０'
    this['1'.code] = '１'
    this['2'.code] = '２'
    this['3'.code] = '３'
    this['4'.code] = '４'
    this['5'.code] = '５'
    this['6'.code] = '６'
    this['7'.code] = '７'
    this['8'.code] = '８'
    this['9'.code] = '９'
    this[':'.code] = '：'
    this[';'.code] = '；'
    this['<'.code] = '＜'
    this['='.code] = '＝'
    this['>'.code] = '＞'
    this['?'.code] = '？'
    this['@'.code] = '＠'
    this['A'.code] = 'Ａ'
    this['B'.code] = 'Ｂ'
    this['C'.code] = 'Ｃ'
    this['D'.code] = 'Ｄ'
    this['E'.code] = 'Ｅ'
    this['F'.code] = 'Ｆ'
    this['G'.code] = 'Ｇ'
    this['H'.code] = 'Ｈ'
    this['I'.code] = 'Ｉ'
    this['J'.code] = 'Ｊ'
    this['K'.code] = 'Ｋ'
    this['L'.code] = 'Ｌ'
    this['M'.code] = 'Ｍ'
    this['N'.code] = 'Ｎ'
    this['O'.code] = 'Ｏ'
    this['P'.code] = 'Ｐ'
    this['Q'.code] = 'Ｑ'
    this['R'.code] = 'Ｒ'
    this['S'.code] = 'Ｓ'
    this['T'.code] = 'Ｔ'
    this['U'.code] = 'Ｕ'
    this['V'.code] = 'Ｖ'
    this['W'.code] = 'Ｗ'
    this['X'.code] = 'Ｘ'
    this['Y'.code] = 'Ｙ'
    this['Z'.code] = 'Ｚ'
    this['['.code] = '［'
    this['\\'.code] = '＼'
    this[']'.code] = '］'
    this['^'.code] = '＾'
    this['_'.code] = '＿'
    this['`'.code] = '\''
    this['a'.code] = 'ａ'
    this['b'.code] = 'ｂ'
    this['c'.code] = 'ｃ'
    this['d'.code] = 'ｄ'
    this['e'.code] = 'ｅ'
    this['f'.code] = 'ｆ'
    this['g'.code] = 'ｇ'
    this['h'.code] = 'ｈ'
    this['i'.code] = 'ｉ'
    this['j'.code] = 'ｊ'
    this['k'.code] = 'ｋ'
    this['l'.code] = 'ｌ'
    this['m'.code] = 'ｍ'
    this['n'.code] = 'ｎ'
    this['o'.code] = 'ｏ'
    this['p'.code] = 'ｐ'
    this['q'.code] = 'ｑ'
    this['r'.code] = 'ｒ'
    this['s'.code] = 'ｓ'
    this['t'.code] = 'ｔ'
    this['u'.code] = 'ｕ'
    this['v'.code] = 'ｖ'
    this['w'.code] = 'ｗ'
    this['x'.code] = 'ｘ'
    this['y'.code] = 'ｙ'
    this['z'.code] = 'ｚ'
    this['{'.code] = '｛'
    this['|'.code] = '｜'
    this['}'.code] = '｝'
    this['~'.code] = '～'
}

class TextPostprocessor {
    // Reusable StringBuilder to avoid allocations
    private val stringBuilder = StringBuilder(512)

    fun postprocess(text: String): String {
        if (text.isEmpty()) return text

        val hasJapaneseText = text.any { it.isJapaneseScript() }

        stringBuilder.setLength(0)
        stringBuilder.ensureCapacity(text.length)

        // Single pass: normalize whitespace, replace ellipsis, and optionally convert to full-width
        var i = 0
        val len = text.length
        var previousNonWhitespace: Char? = null

        while (i < len) {
            val char = text[i]

            if (char.isWhitespace()) {
                var nextIndex = i + 1
                while (nextIndex < len && text[nextIndex].isWhitespace()) {
                    nextIndex++
                }

                val nextNonWhitespace = text.getOrNull(nextIndex)
                val shouldKeepSpace = previousNonWhitespace != null &&
                    nextNonWhitespace != null &&
                    !previousNonWhitespace.isJapaneseScript() &&
                    !nextNonWhitespace.isJapaneseScript()

                if (shouldKeepSpace && (stringBuilder.isEmpty() || stringBuilder.last() != ' ')) {
                    stringBuilder.append(' ')
                }

                i = nextIndex
                continue
            }

            if (char == '…') {
                stringBuilder.append("...")
                previousNonWhitespace = char
                i++
                continue
            }

            if (char == '.' || char == '・') {
                var dotCount = 1
                var laterCharIndex = i + 1
                while (laterCharIndex < len && (text[laterCharIndex] == '.' || text[laterCharIndex] == '・')) {
                    dotCount++
                    laterCharIndex++
                }

                if (dotCount >= 2) {
                    // Replace with periods
                    repeat(dotCount) { stringBuilder.append('.') }
                    previousNonWhitespace = text[laterCharIndex - 1]
                    i = laterCharIndex
                    continue
                }
            }

            if (hasJapaneseText) {
                // Convert half-width to full-width only when the sentence contains Japanese text.
                val code = char.code
                if (code < HALF_TO_FULL_TABLE.size) {
                    stringBuilder.append(HALF_TO_FULL_TABLE[code])
                } else {
                    stringBuilder.append(char)
                }
            } else {
                stringBuilder.append(char)
            }
            previousNonWhitespace = char

            i++
        }

        return stringBuilder.toString()
    }

    private fun Char.isJapaneseScript(): Boolean {
        val codePoint = code
        return codePoint in 0x3040..0x30FF || // Hiragana + Katakana
            codePoint in 0x4E00..0x9FFF || // CJK Unified Ideographs (common kanji)
            codePoint in 0x3400..0x4DBF || // CJK Extension A
            codePoint in 0xF900..0xFAFF // CJK Compatibility Ideographs
    }
}
