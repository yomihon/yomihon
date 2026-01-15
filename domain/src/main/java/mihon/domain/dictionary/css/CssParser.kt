/**
 * CSS parsing utilities for dictionary styling.
 * Parses CSS text into structured data for easier styling application.
 */
package mihon.domain.dictionary.css

/**
 * Parsed CSS data from a dictionary's styles.css file.
 */
data class ParsedCss(
    val boxSelectors: Set<String>,
    val selectorStyles: Map<String, Map<String, String>>,
) {
    companion object {
        val EMPTY = ParsedCss(emptySet(), emptyMap())
    }
}

/**
 * Parses CSS text from a dictionary's styles.css into a structured format.
 * Uses a simple tokenizer approach instead of regex for cleaner parsing.
 *
 * Extracts:
 * - Box selectors: data-sc-content/class values that have background/border properties
 * - Selector styles: All CSS properties mapped by data-sc-content/class selector values
 */
fun parseDictionaryCss(cssText: String?): ParsedCss {
    if (cssText.isNullOrBlank()) return ParsedCss.EMPTY

    val cleanedCss = stripCssComments(cssText)
    if (cleanedCss.isBlank()) return ParsedCss.EMPTY

    val boxSelectors = mutableSetOf<String>()
    val selectorStyles = mutableMapOf<String, MutableMap<String, String>>()

    // Parse CSS rules by finding { } blocks
    var i = 0
    while (i < cleanedCss.length) {
        // Find the start of a rule block
        val braceStart = cleanedCss.indexOf('{', i)
        if (braceStart == -1) break

        // Extract selector part (before '{')
        val selectorPart = cleanedCss.substring(i, braceStart).trim()

        // Find the end of the rule block
        val braceEnd = cleanedCss.indexOf('}', braceStart)
        if (braceEnd == -1) break

        // Extract properties part (between '{' and '}')
        val propertiesPart = cleanedCss.substring(braceStart + 1, braceEnd)

        // Parse properties into key-value pairs
        val properties = parseProperties(propertiesPart)

        // Extract data-sc-content and data-sc-class selector values
        val dataSelectors = extractDataSelectors(selectorPart)

        // Check if this rule has box-related properties
        val hasBoxProperty = properties.keys.any { key ->
            key.startsWith("background") ||
                key.startsWith("border") ||
                key.startsWith("padding") ||
                key.startsWith("margin") ||
                key == "clip-path"
        }

        // Store results for each selector
        for (selector in dataSelectors) {
            if (hasBoxProperty) {
                boxSelectors.add(selector)
            }
            // Store all properties for this selector
            val existingStyles = selectorStyles.getOrPut(selector) { mutableMapOf() }
            // Convert CSS property names to camelCase for consistency
            properties.forEach { (key, value) ->
                existingStyles[toCamelCase(key)] = value
            }
        }

        i = braceEnd + 1
    }

    return ParsedCss(boxSelectors, selectorStyles)
}

/**
 * Strips CSS block comments (/* ... */) from the input string.
 */
private fun stripCssComments(css: String): String {
    val result = StringBuilder()
    var i = 0
    while (i < css.length) {
        if (i + 1 < css.length && css[i] == '/' && css[i + 1] == '*') {
            // Find end of comment
            val endIndex = css.indexOf("*/", i + 2)
            if (endIndex == -1) {
                // Unclosed comment, skip the rest
                break
            }
            i = endIndex + 2
        } else {
            result.append(css[i])
            i++
        }
    }
    return result.toString()
}

/**
 * Parses CSS property declarations from a properties block.
 * E.g., "font-size: 0.8em; color: red" -> {"font-size" to "0.8em", "color" to "red"}
 */
private fun parseProperties(propertiesPart: String): Map<String, String> {
    val result = mutableMapOf<String, String>()

    propertiesPart.split(';').forEach { declaration ->
        val colonIndex = declaration.indexOf(':')
        if (colonIndex != -1) {
            val key = declaration.take(colonIndex).trim().lowercase()
            val value = declaration.substring(colonIndex + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                result[key] = value
            }
        }
    }

    return result
}

/**
 * Extracts data-sc-content and data-sc-class selector values from a CSS selector string.
 * E.g., "[data-sc-content='example']" -> ["example"]
 */
private fun extractDataSelectors(selectorPart: String): List<String> {
    val result = mutableListOf<String>()
    var i = 0

    while (i < selectorPart.length) {
        // Look for [data-sc-content= or [data-sc-class=
        val attrStart = selectorPart.indexOf("[data-sc-", i)
        if (attrStart == -1) break

        // Find the attribute name end (=)
        val equalsIndex = selectorPart.indexOf('=', attrStart)
        if (equalsIndex == -1) {
            i = attrStart + 1
            continue
        }

        // Only content and class attribute names are supported
        val attrName = selectorPart.substring(attrStart + 1, equalsIndex)
        if (attrName != "data-sc-content" && attrName != "data-sc-class") {
            i = equalsIndex + 1
            continue
        }

        val valueStart = equalsIndex + 1
        if (valueStart >= selectorPart.length) break

        val quote = selectorPart[valueStart]
        if (quote != '\'' && quote != '"') {
            i = valueStart + 1
            continue
        }

        val valueEnd = selectorPart.indexOf(quote, valueStart + 1)
        if (valueEnd == -1) break

        val value = selectorPart.substring(valueStart + 1, valueEnd)
        if (value.isNotEmpty()) {
            result.add(value)
        }

        i = valueEnd + 1
    }

    return result
}

private fun toCamelCase(cssProperty: String): String {
    val parts = cssProperty.split('-')
    if (parts.size == 1) return parts[0]

    return buildString {
        append(parts[0])
        for (j in 1 until parts.size) {
            val part = parts[j]
            if (part.isNotEmpty()) {
                append(part[0].uppercaseChar())
                append(part.substring(1))
            }
        }
    }
}

/**
 * Gets merged CSS styles for an element based on its data attributes.
 *
 * @param dataAttributes Data attributes from the element
 * @param parsedCss Parsed CSS from the dictionary's styles.css
 * @return Merged style map from all matching selectors
 */
fun getCssStyles(
    dataAttributes: Map<String, String>,
    parsedCss: ParsedCss,
): Map<String, String> {
    return dataAttributes.values
        .mapNotNull { parsedCss.selectorStyles[it] }
        .fold(emptyMap()) { acc, map -> acc + map }
}

/**
 * Represents parsed box styling properties from CSS.
 * Background color uses theme-based values for display compatibility.
 */
data class BoxStyle(
    val hasBackground: Boolean = false,
    val hasBorder: Boolean = false,
    val borderRadius: Float? = null,
    val paddingStart: Float? = null,
    val paddingEnd: Float? = null,
    val paddingTop: Float? = null,
    val paddingBottom: Float? = null,
    val marginStart: Float? = null,
    val marginEnd: Float? = null,
    val marginTop: Float? = null,
    val marginBottom: Float? = null,
) {
    val hasPadding: Boolean
        get() = paddingStart != null || paddingEnd != null || paddingTop != null || paddingBottom != null

    val hasMargin: Boolean
        get() = marginStart != null || marginEnd != null || marginTop != null || marginBottom != null

    val hasAnyStyle: Boolean
        get() = hasBackground || hasBorder || hasPadding || hasMargin
}

/**
 * Parses a CSS style map into a BoxStyle.
 * Checks for background, border, padding, and margin properties.
 *
 * @param styleMap CSS style properties to parse
 * @param baseFontSizeSp Base font size in sp for em/rem unit conversion
 */
fun parseBoxStyle(styleMap: Map<String, String>, baseFontSizeSp: Float = 14f): BoxStyle {
    var hasBackground = false
    var hasBorder = false
    var borderRadius: Float? = null
    var paddingStart: Float? = null
    var paddingEnd: Float? = null
    var paddingTop: Float? = null
    var paddingBottom: Float? = null
    var marginStart: Float? = null
    var marginEnd: Float? = null
    var marginTop: Float? = null
    var marginBottom: Float? = null

    for ((key, value) in styleMap) {
        when (key) {
            "backgroundColor", "background" -> {
                // Check if it's a meaningful background (not transparent/inherit)
                if (value.isNotBlank() && value != "transparent" && value != "inherit" && value != "none") {
                    hasBackground = true
                }
            }
            "border", "borderColor", "borderWidth" -> {
                if (value.isNotBlank() && value != "none" && value != "0" && value != "0px") {
                    hasBorder = true
                }
            }
            "borderRadius" -> {
                borderRadius = parseDpValue(value, baseFontSizeSp)
            }
            "padding" -> {
                val dp = parseDpValue(value, baseFontSizeSp)
                if (dp != null) {
                    paddingStart = dp
                    paddingEnd = dp
                    paddingTop = dp
                    paddingBottom = dp
                }
            }
            "paddingLeft", "paddingInlineStart" -> {
                paddingStart = parseDpValue(value, baseFontSizeSp)
            }
            "paddingRight", "paddingInlineEnd" -> {
                paddingEnd = parseDpValue(value, baseFontSizeSp)
            }
            "paddingTop" -> {
                paddingTop = parseDpValue(value, baseFontSizeSp)
            }
            "paddingBottom" -> {
                paddingBottom = parseDpValue(value, baseFontSizeSp)
            }
            "margin" -> {
                val dp = parseDpValue(value, baseFontSizeSp)
                if (dp != null) {
                    marginStart = dp
                    marginEnd = dp
                    marginTop = dp
                    marginBottom = dp
                }
            }
            "marginLeft", "marginInlineStart" -> {
                marginStart = parseDpValue(value, baseFontSizeSp)
            }
            "marginRight", "marginInlineEnd" -> {
                marginEnd = parseDpValue(value, baseFontSizeSp)
            }
            "marginTop" -> {
                marginTop = parseDpValue(value, baseFontSizeSp)
            }
            "marginBottom" -> {
                marginBottom = parseDpValue(value, baseFontSizeSp)
            }
        }
    }

    return BoxStyle(
        hasBackground = hasBackground,
        hasBorder = hasBorder,
        borderRadius = borderRadius,
        paddingStart = paddingStart,
        paddingEnd = paddingEnd,
        paddingTop = paddingTop,
        paddingBottom = paddingBottom,
        marginStart = marginStart,
        marginEnd = marginEnd,
        marginTop = marginTop,
        marginBottom = marginBottom,
    )
}

/**
 * Parses a CSS dimension value to a Float representing dp.
 * Supports: px, em, rem, dp, and unitless numbers.
 */
fun parseDpValue(value: String, baseFontSizeSp: Float): Float? {
    val trimmed = value.trim().lowercase()
    return when {
        trimmed.endsWith("px") -> trimmed.removeSuffix("px").toFloatOrNull()
        trimmed.endsWith("em") -> trimmed.removeSuffix("em").toFloatOrNull()?.times(baseFontSizeSp)
        trimmed.endsWith("rem") -> trimmed.removeSuffix("rem").toFloatOrNull()?.times(baseFontSizeSp)
        trimmed.endsWith("dp") -> trimmed.removeSuffix("dp").toFloatOrNull()
        else -> trimmed.toFloatOrNull()
    }
}
