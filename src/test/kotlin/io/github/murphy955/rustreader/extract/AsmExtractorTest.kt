package io.github.murphy955.rustreader.extract

import io.github.murphy955.rustreader.context.ItemKind
import io.github.murphy955.rustreader.context.ItemLocator
import io.github.murphy955.rustreader.context.ItemRef
import io.github.murphy955.rustreader.context.spanOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AsmExtractorTest {
    private val asm = Files.readString(Path.of("src/test/testData/fixtures/sample.s"))
    private val source = Files.readString(Path.of("src/test/testData/sample-crate/src/lib.rs"))

    private fun itemAt(token: String): ItemRef {
        val offset = source.indexOf(token)
        assertTrue("token not found: $token", offset >= 0)
        return ItemLocator.locate(source, offset, listOf())!!
    }

    @Test
    fun extractsFreeFunctionSymbol() {
        val result = AsmExtractor.extract(asm, "sample_crate", emptyList(), itemAt("pub fn run"))
        assertFalse(result.empty)
        assertTrue(result.content.contains("_12sample_crate3run"))
        assertTrue(result.content.contains(".size"))
        assertFalse(result.content.contains("_12sample_crate3run0B3_:"))
    }

    @Test
    fun extractsImplMethodSymbol() {
        val result = AsmExtractor.extract(asm, "sample_crate", emptyList(), itemAt("pub fn get"))
        assertFalse(result.empty)
        assertTrue(result.content.contains("5Point3get"))
        assertFalse(result.content.contains("3use_macro"))
    }

    @Test
    fun matchesModulePathTokens() {
        val item = itemAt("pub fn helper")
        val result = AsmExtractor.extract(asm, "sample_crate", listOf("nested"), item)
        assertFalse(result.empty)
        assertTrue(result.content.contains("6nested6helper"))
    }

    @Test
    fun returnsMissingMessageWhenSymbolAbsent() {
        val item = ItemRef(
            kind = ItemKind.FN,
            name = "definitely_missing",
            modulePath = emptyList(),
            sourceSpan = spanOf(source, 0, 1),
        )
        val result = AsmExtractor.extract(asm, "sample_crate", emptyList(), item)
        assertTrue(result.empty)
        assertTrue(result.content.isEmpty())
        assertTrue(result.warnings.any { it.contains("inlined") })
    }

    @Test
    fun wholeFileOptionReturnsEverything() {
        val result = AsmExtractor.extract(asm, "sample_crate", emptyList(), null, wholeFile = true)
        assertFalse(result.empty)
        assertTrue(result.content.contains("_12sample_crate3run"))
        assertTrue(result.content.contains("6nested6helper"))
    }

    @Test
    fun handlesLegacySymbols() {
        val legacy = """
            .text
            .globl _ZN4demo3run17h1234567890abcdefE
            .type _ZN4demo3run17h1234567890abcdefE,@function
        _ZN4demo3run17h1234567890abcdefE:
            ret
            .size _ZN4demo3run17h1234567890abcdefE, .-_ZN4demo3run17h1234567890abcdefE
        """.trimIndent()
        val item = ItemRef(
            kind = ItemKind.FN,
            name = "run",
            modulePath = emptyList(),
            sourceSpan = spanOf("fn run() {}", 0, 1),
        )
        val result = AsmExtractor.extract(legacy, "demo", emptyList(), item)
        assertFalse(result.empty)
        assertTrue(result.content.contains("_ZN4demo3run17h"))
    }

    @Test
    fun reportsMultipleEquallyRankedSymbols() {
        val ambiguous = """
            .text
            .type _RNvC1a3foo,@function
        _RNvC1a3foo:
            ret
            .size _RNvC1a3foo, .-_RNvC1a3foo

            .text
            .type _RNvC2b3foo,@function
        _RNvC2b3foo:
            ret
            .size _RNvC2b3foo, .-_RNvC2b3foo
        """.trimIndent()
        val item = ItemRef(
            kind = ItemKind.FN,
            name = "foo",
            modulePath = emptyList(),
            sourceSpan = spanOf("fn foo() {}", 0, 1),
        )
        val result = AsmExtractor.extract(ambiguous, "unrelated", emptyList(), item)
        assertFalse(result.empty)
        assertTrue(result.warnings.any { it.contains("Multiple") })
        assertTrue(result.candidates.size == 2)
    }
}
