package xyz.mederi.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SkillFrontmatterParser 各语法分支单测。
 */
class SkillFrontmatterParserTest {

    @Test
    fun parsesSingleLineFields() {
        val parsed = SkillFrontmatterParser.parse(
            "---\nname: demo\ndescription: Do stuff\nlicense: MIT\n---\n# Demo\n"
        )
        assertEquals("demo", parsed.name)
        assertEquals("Do stuff", parsed.description)
        assertEquals("MIT", parsed.license)
        assertTrue(parsed.isValid)
    }

    @Test
    fun parsesQuotedValues() {
        assertEquals(
            "Do stuff",
            SkillFrontmatterParser.parse("---\ndescription: \"Do stuff\"\n---\n").description
        )
        assertEquals(
            "Do other",
            SkillFrontmatterParser.parse("---\ndescription: 'Do other'\n---\n").description
        )
    }

    @Test
    fun parsesFoldedBlock() {
        val parsed = SkillFrontmatterParser.parse(
            "---\ndescription: >\n  Jetpack Compose skill.\n  Only use when asked.\n---\n"
        )
        assertEquals("Jetpack Compose skill. Only use when asked.", parsed.description)
    }

    @Test
    fun parsesFoldedBlockWithBlankLine() {
        val parsed = SkillFrontmatterParser.parse(
            "---\ndescription: >\n  a\n\n  b\n---\n"
        )
        // 空行处折叠为单个空格
        assertEquals("a b", parsed.description)
    }

    @Test
    fun parsesLiteralBlock() {
        val parsed = SkillFrontmatterParser.parse(
            "---\ndescription: |\n  line1\n  line2\n---\n"
        )
        assertEquals("line1\nline2", parsed.description)
    }

    @Test
    fun parsesStripChomping() {
        val parsed = SkillFrontmatterParser.parse(
            "---\ndescription: >-\n  a\n  b\n\n\n---\n"
        )
        // strip chomping：去掉全部尾空白
        assertEquals("a b", parsed.description)
    }

    @Test
    fun parsesEmptyValueIndentedBlock() {
        val parsed = SkillFrontmatterParser.parse(
            "---\ndescription:\n  first line\n  second line\n---\n"
        )
        assertEquals("first line second line", parsed.description)
    }

    @Test
    fun parsesMetadataSubKeys() {
        val parsed = SkillFrontmatterParser.parse(
            "---\nmetadata:\n  version: 1.0\n  author: x\n---\n"
        )
        assertEquals(mapOf("version" to "1.0", "author" to "x"), parsed.metadata)
    }

    @Test
    fun invalidWithoutFrontmatter() {
        val parsed = SkillFrontmatterParser.parse("# no frontmatter here\nname: demo\n")
        assertFalse(parsed.isValid)
        assertNull(parsed.name)
        assertNull(parsed.description)
    }

    @Test
    fun nameOrDescriptionBlankMeansNull() {
        val parsed = SkillFrontmatterParser.parse("---\nname: demo\ndescription:\n---\n")
        assertTrue(parsed.isValid)
        assertEquals("demo", parsed.name)
        assertNull(parsed.description)
    }

    @Test
    fun ignoresCommentAndBlankLines() {
        val parsed = SkillFrontmatterParser.parse(
            "---\n# comment\nname: demo\n\ndescription: Do stuff\n# another comment\nlicense: MIT\n---\n"
        )
        assertTrue(parsed.isValid)
        assertEquals("demo", parsed.name)
        assertEquals("Do stuff", parsed.description)
        assertEquals("MIT", parsed.license)
    }
}
