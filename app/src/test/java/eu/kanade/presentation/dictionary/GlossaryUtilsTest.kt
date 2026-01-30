package eu.kanade.presentation.dictionary

import eu.kanade.presentation.dictionary.components.buildAnnotatedText
import mihon.domain.dictionary.css.ParsedCss
import mihon.domain.dictionary.css.parseDictionaryCss
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import mihon.domain.dictionary.model.GlossaryElementAttributes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlossaryUtilsTest {

    @Test
    fun `buildAnnotatedText collects plain text`() {
        val nodes = listOf(
            GlossaryNode.Text("Hello "),
            GlossaryNode.Text("World")
        )

        val result = buildAnnotatedText(nodes)
        assertEquals("Hello World", result.text)
        assertTrue(result.linkRanges.isEmpty())
    }

    @Test
    fun `buildAnnotatedText formats ruby text correctly`() {
        val nodes = listOf(
            GlossaryNode.Element(
                tag = GlossaryTag.Ruby,
                children = listOf(
                    GlossaryNode.Text("漢字"),
                    GlossaryNode.Element(
                        tag = GlossaryTag.Rt,
                        children = listOf(GlossaryNode.Text("かんじ")),
                        attributes = GlossaryElementAttributes()
                    )
                ),
                attributes = GlossaryElementAttributes()
            )
        )

        val result = buildAnnotatedText(nodes)
        assertEquals("[漢字[かんじ]]", result.text)
        assertTrue(result.linkRanges.isEmpty())
    }

    @Test
    fun `buildAnnotatedText tracks link ranges`() {
        val nodes = listOf(
            GlossaryNode.Text("See "),
            GlossaryNode.Element(
                tag = GlossaryTag.Link,
                children = listOf(GlossaryNode.Text("other")),
                attributes = GlossaryElementAttributes(
                    properties = mapOf("href" to "?query=other")
                )
            ),
            GlossaryNode.Text(" entry")
        )

        val result = buildAnnotatedText(nodes)
        assertEquals("See other entry", result.text)
        assertEquals(1, result.linkRanges.size)
        assertEquals(4, result.linkRanges[0].start)
        assertEquals(9, result.linkRanges[0].end)
        assertEquals("other", result.linkRanges[0].target)
    }

    @Test
    fun `buildAnnotatedText handles nested spans`() {
        val nodes = listOf(
            GlossaryNode.Element(
                tag = GlossaryTag.Span,
                children = listOf(
                    GlossaryNode.Text("「First」"),
                    GlossaryNode.Element(
                        tag = GlossaryTag.Span,
                        children = listOf(GlossaryNode.Text("「Second」")),
                        attributes = GlossaryElementAttributes()
                    )
                ),
                attributes = GlossaryElementAttributes()
            )
        )

        val result = buildAnnotatedText(nodes)
        assertEquals("「First」「Second」", result.text)
    }

    @Test
    fun `parseDictionaryCss returns EMPTY for blank input`() {
        // Consolidated test: null, empty string, and whitespace-only input
        assertEquals(ParsedCss.EMPTY, parseDictionaryCss(null))
        assertEquals(ParsedCss.EMPTY, parseDictionaryCss(""))
        assertEquals(ParsedCss.EMPTY, parseDictionaryCss("   "))
    }

    @Test
    fun `parseDictionaryCss parses single property`() {
        val css = "[data-sc-content='example'] { font-style: italic; }"
        val result = parseDictionaryCss(css)

        assertEquals("italic", result.selectorStyles["example"]?.get("fontStyle"))
    }

    @Test
    fun `parseDictionaryCss parses multiple properties`() {
        val css = "[data-sc-content='spaced'] { margin-top: 5px; margin-left: 2em; margin-right: 0; margin-bottom: auto; }"
        val result = parseDictionaryCss(css)

        assertEquals("5px", result.selectorStyles["spaced"]?.get("marginTop"))
        assertEquals("2em", result.selectorStyles["spaced"]?.get("marginLeft"))
        assertEquals("0", result.selectorStyles["spaced"]?.get("marginRight"))
        assertEquals("auto", result.selectorStyles["spaced"]?.get("marginBottom"))
    }

    @Test
    fun `parseDictionaryCss parses box model properties and marks as box selectors`() {
        val css = """
            [data-sc-content='box'] { background: #f0f0f0; }
            [data-sc-content='bg-color'] { background-color: rgba(0, 0, 0, 0.1); }
            [data-sc-content='bordered'] { border-color: #000; border-style: solid; border-radius: 4px; border-width: 1px; }
        """
        val result = parseDictionaryCss(css)

        // Background property
        assertTrue(result.boxSelectors.contains("box"))
        assertEquals("#f0f0f0", result.selectorStyles["box"]?.get("background"))

        // Background-color property
        assertTrue(result.boxSelectors.contains("bg-color"))
        assertEquals("rgba(0, 0, 0, 0.1)", result.selectorStyles["bg-color"]?.get("backgroundColor"))

        // Border properties
        assertTrue(result.boxSelectors.contains("bordered"))
        assertEquals("#000", result.selectorStyles["bordered"]?.get("borderColor"))
        assertEquals("solid", result.selectorStyles["bordered"]?.get("borderStyle"))
        assertEquals("4px", result.selectorStyles["bordered"]?.get("borderRadius"))
        assertEquals("1px", result.selectorStyles["bordered"]?.get("borderWidth"))
    }

    @Test
    fun `parseDictionaryCss handles double quote selectors`() {
        val css = """[data-sc-content="double-quoted"] { font-weight: bold; }"""
        val result = parseDictionaryCss(css)

        assertEquals("bold", result.selectorStyles["double-quoted"]?.get("fontWeight"))
    }

    @Test
    fun `parseDictionaryCss handles multiple rules`() {
        val css = """
            [data-sc-content='first'] { font-style: italic; }
            [data-sc-content='second'] { font-weight: bold; }
        """
        val result = parseDictionaryCss(css)

        assertEquals("italic", result.selectorStyles["first"]?.get("fontStyle"))
        assertEquals("bold", result.selectorStyles["second"]?.get("fontWeight"))
    }

    @Test
    fun `parseDictionaryCss merges properties from multiple rules for same selector`() {
        val css = """
            [data-sc-content='merged'] { font-style: italic; }
            [data-sc-content='merged'] { font-weight: bold; }
        """
        val result = parseDictionaryCss(css)

        assertEquals("italic", result.selectorStyles["merged"]?.get("fontStyle"))
        assertEquals("bold", result.selectorStyles["merged"]?.get("fontWeight"))
    }

    @Test
    fun `parseDictionaryCss handles mixed box and non-box selectors`() {
        val css = """
            [data-sc-content='with-bg'] { background: #eee; font-style: italic; }
            [data-sc-content='no-bg'] { font-weight: bold; }
        """
        val result = parseDictionaryCss(css)

        assertTrue(result.boxSelectors.contains("with-bg"))
        assertTrue(!result.boxSelectors.contains("no-bg"))
    }

    @Test
    fun `parseDictionaryCss handles whitespace variations`() {
        val css = """
            [data-sc-content='spaces']    {
                font-style    :    italic   ;
                font-weight:bold;
            }
        """
        val result = parseDictionaryCss(css)

        assertEquals("italic", result.selectorStyles["spaces"]?.get("fontStyle"))
        assertEquals("bold", result.selectorStyles["spaces"]?.get("fontWeight"))
    }

    @Test
    fun `parseDictionaryCss handles selectors with complex values`() {
        val css = "[data-sc-content='complex'] { background: linear-gradient(to right, #fff, #000); }"
        val result = parseDictionaryCss(css)

        assertTrue(result.boxSelectors.contains("complex"))
        assertEquals("linear-gradient(to right, #fff, #000)", result.selectorStyles["complex"]?.get("background"))
    }

    @Test
    fun `parseDictionaryCss ignores non-data-sc selectors`() {
        val css = ".regular-class { font-style: italic; }"
        val result = parseDictionaryCss(css)

        assertTrue(result.selectorStyles.isEmpty())
        assertTrue(result.boxSelectors.isEmpty())
    }

    @Test
    fun `parseDictionaryCss ignores invalid selectors gracefully`() {
        val css = "[data-sc- { font-style: italic; }"
        val result = parseDictionaryCss(css)

        assertTrue(result.selectorStyles.isEmpty())
    }

    @Test
    fun `parseDictionaryCss handles empty property value`() {
        val css = "[data-sc-content='empty'] { font-style: ; }"
        val result = parseDictionaryCss(css)

        // Empty values should be ignored - selector exists but no styles
        assertTrue(result.selectorStyles["empty"]?.isEmpty() != false)
    }

    @Test
    fun `parseDictionaryCss handles CSS comments correctly`() {
        // Single comment before rule
        val css1 = """
            /* This is a comment */
            [data-sc-content='test'] { font-style: italic; }
        """
        assertEquals("italic", parseDictionaryCss(css1).selectorStyles["test"]?.get("fontStyle"))

        // Multi-line comment
        val css2 = """
            /* This is a
               multi-line
               comment */
            [data-sc-content='test'] { font-weight: bold; }
        """
        assertEquals("bold", parseDictionaryCss(css2).selectorStyles["test"]?.get("fontWeight"))

        // Comment between rules
        val css3 = """
            [data-sc-content='first'] { font-style: italic; }
            /* Comment between rules */
            [data-sc-content='second'] { font-weight: bold; }
        """
        val result3 = parseDictionaryCss(css3)
        assertEquals("italic", result3.selectorStyles["first"]?.get("fontStyle"))
        assertEquals("bold", result3.selectorStyles["second"]?.get("fontWeight"))

        // Comment-only CSS
        val css4 = "/* only comment, no rules */"
        val result4 = parseDictionaryCss(css4)
        assertTrue(result4.selectorStyles.isEmpty())
        assertTrue(result4.boxSelectors.isEmpty())

        // Inline comment in property block
        val css5 = """
            [data-sc-content='inline'] { 
                font-style: italic; /* inline comment */
                font-weight: bold;
            }
        """
        val result5 = parseDictionaryCss(css5)
        assertEquals("italic", result5.selectorStyles["inline"]?.get("fontStyle"))
        assertEquals("bold", result5.selectorStyles["inline"]?.get("fontWeight"))
    }

    @Test
    fun `parseDictionaryCss handles malformed CSS gracefully`() {
        // Unclosed brace - should skip the rule
        val css1 = "[data-sc-content='test'] { font-style: italic"
        assertTrue(parseDictionaryCss(css1).selectorStyles.isEmpty())

        // Unclosed selector bracket still parses correctly since value quotes are matched
        val css2 = "[data-sc-content='test' { font-style: italic; }"
        assertEquals("italic", parseDictionaryCss(css2).selectorStyles["test"]?.get("fontStyle"))

        // Missing equals in attribute selector - should skip
        val css3 = "[data-sc-content] { font-style: italic; }"
        assertTrue(parseDictionaryCss(css3).selectorStyles.isEmpty())

        // Empty selector value - should skip
        val css4 = "[data-sc-content=''] { font-style: italic; }"
        assertTrue(parseDictionaryCss(css4).selectorStyles.isEmpty())

        // Unclosed quote in selector - should skip
        val css5 = "[data-sc-content='test] { font-style: italic; }"
        assertTrue(parseDictionaryCss(css5).selectorStyles.isEmpty())

        // Missing opening brace - should skip
        val css6 = "[data-sc-content='test'] font-style: italic; }"
        assertTrue(parseDictionaryCss(css6).selectorStyles.isEmpty())
    }

    @Test
    fun `parseDictionaryCss handles nested braces in value`() {
        val css = "[data-sc-content='test'] { background: url(data:image/svg+xml;utf8,<svg></svg>); }"
        val result = parseDictionaryCss(css)

        assertTrue(result.boxSelectors.contains("test"))
    }

    @Test
    fun `parseDictionaryCss ignores unknown data-sc attributes`() {
        val css = "[data-sc-unknown='test'] { font-style: italic; }"
        val result = parseDictionaryCss(css)

        assertTrue(result.selectorStyles.isEmpty())
    }

    @Test
    fun `parseDictionaryCss handles multiple selectors in one rule`() {
        val css = "[data-sc-content='first'], [data-sc-content='second'] { font-style: italic; }"
        val result = parseDictionaryCss(css)

        assertEquals("italic", result.selectorStyles["first"]?.get("fontStyle"))
        assertEquals("italic", result.selectorStyles["second"]?.get("fontStyle"))
    }

    @Test
    fun `parseDictionaryCss handles malformed properties gracefully`() {
        // Property without colon - should skip that property
        val css1 = "[data-sc-content='test'] { font-style; font-weight: bold; }"
        assertEquals("bold", parseDictionaryCss(css1).selectorStyles["test"]?.get("fontWeight"))

        // Property with only colon - should skip that property
        val css2 = "[data-sc-content='test'] { : italic; font-weight: bold; }"
        assertEquals("bold", parseDictionaryCss(css2).selectorStyles["test"]?.get("fontWeight"))

        // Extra semicolons - should continue parsing
        val css3 = "[data-sc-content='test'] { font-style: italic;; font-weight: bold; }"
        val result3 = parseDictionaryCss(css3)
        assertEquals("italic", result3.selectorStyles["test"]?.get("fontStyle"))
        assertEquals("bold", result3.selectorStyles["test"]?.get("fontWeight"))
    }

    @Test
    fun `parseDictionaryCss handles CSS with BOM`() {
        val css = "\uFEFF[data-sc-content='test'] { font-style: italic; }"
        val result = parseDictionaryCss(css)

        assertEquals("italic", result.selectorStyles["test"]?.get("fontStyle"))
    }

    @Test
    fun `parseDictionaryCss handles mixed valid and invalid rules`() {
        val css = """
            [data-sc-content='valid'] { font-style: italic; }
            .invalid-selector { font-weight: bold; }
            [data-sc-content='also-valid'] { font-size: 1.2em; }
        """
        val result = parseDictionaryCss(css)

        assertEquals("italic", result.selectorStyles["valid"]?.get("fontStyle"))
        assertEquals("1.2em", result.selectorStyles["also-valid"]?.get("fontSize"))
        assertEquals(2, result.selectorStyles.size)
    }
    @Test
    fun `parseDictionaryCss recognizes schema box model properties as box selectors`() {
        // Schema defines padding and margin which require box rendering
        val css = """
            [data-sc-content='pad'] { padding: 10px; }
            [data-sc-content='pad-top'] { padding-top: 5px; }
            [data-sc-content='marg'] { margin: 1em; }
            [data-sc-content='marg-btm'] { margin-bottom: 2px; }
        """
        val result = parseDictionaryCss(css)

        assertTrue(result.boxSelectors.contains("pad"))
        assertTrue(result.boxSelectors.contains("pad-top"))
        assertTrue(result.boxSelectors.contains("marg"))
        assertTrue(result.boxSelectors.contains("marg-btm"))
    }

    @Test
    fun `parseDictionaryCss recognizes schema visual properties as box selectors`() {
        // Schema defines borderRadius and clipPath which require box rendering
        val css = """
            [data-sc-content='radius'] { border-radius: 4px; }
            [data-sc-content='clip'] { clip-path: circle(50%); }
        """
        val result = parseDictionaryCss(css)

        assertTrue(result.boxSelectors.contains("radius"))
        assertTrue(result.boxSelectors.contains("clip"))
    }

    @Test
    fun `parseDictionaryCss handles data-sc-class selectors`() {
        // Schema allows data attributes which map to class selectors
        val css = "[data-sc-class='highlight'] { background-color: yellow; }"
        val result = parseDictionaryCss(css)

        assertTrue(result.boxSelectors.contains("highlight"))
        assertEquals("yellow", result.selectorStyles["highlight"]?.get("backgroundColor"))
    }

    @Test
    fun `parseDictionaryCss parses complex selector structures`() {
        // Test grouping and mixed attributes
        val css = """
            [data-sc-content='group1'], [data-sc-class='group2'] { font-weight: bold; }
            [data-sc-content='both'][data-sc-class='both'] { font-style: italic; }
        """
        val result = parseDictionaryCss(css)

        assertEquals("bold", result.selectorStyles["group1"]?.get("fontWeight"))
        assertEquals("bold", result.selectorStyles["group2"]?.get("fontWeight"))
        assertEquals("italic", result.selectorStyles["both"]?.get("fontStyle"))
    }
}
