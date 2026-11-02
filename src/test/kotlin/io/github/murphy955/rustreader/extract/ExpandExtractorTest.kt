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

class ExpandExtractorTest {
    private val expanded = Files.readString(Path.of("src/test/testData/fixtures/sample-expanded.rs"))
    private val source = Files.readString(Path.of("src/test/testData/sample-crate/src/lib.rs"))

    private fun itemAt(token: String, modules: List<String> = emptyList()): ItemRef {
        val offset = source.indexOf(token)
        assertTrue("token not found: $token", offset >= 0)
        return ItemLocator.locate(source, offset, modules)!!
    }

    @Test
    fun extractsStructAndAutoDerivedImpls() {
        val result = ExpandExtractor.extract(expanded, itemAt("pub struct Point"))
        assertTrue(result.content.contains("pub struct Point"))
        assertTrue(result.content.contains("#[automatically_derived]"))
        assertTrue(result.content.contains("::core::fmt::Debug for Point"))
        assertTrue(result.content.contains("::core::clone::Clone for Point"))
        assertFalse(result.content.contains("pub fn run"))
    }

    @Test
    fun extractsInherentMethod() {
        val result = ExpandExtractor.extract(expanded, itemAt("pub fn get"))
        assertTrue(result.content.contains("pub fn get"))
        assertTrue(result.content.contains("self.x + self.y"))
        assertFalse(result.content.contains("debug_struct_field2_finish"))
    }

    @Test
    fun picksTheMatchingTraitImpl() {
        val item = itemAt("fn fmt")
        val result = ExpandExtractor.extract(expanded, item)
        assertTrue(result.content.contains("fn fmt"))
        assertTrue(result.content.contains("format_args!"))
        assertFalse(result.content.contains("debug_struct_field2_finish"))
    }

    @Test
    fun extractsFreeFunction() {
        val result = ExpandExtractor.extract(expanded, itemAt("pub fn run"))
        assertTrue(result.content.contains("pub fn run"))
        assertFalse(result.content.contains("pub fn helper"))
    }

    @Test
    fun extractsFunctionInModule() {
        val item = itemAt("pub fn helper", listOf("nested"))
        val result = ExpandExtractor.extract(expanded, item)
        assertTrue(result.content.contains("pub fn helper"))
        assertFalse(result.content.contains("pub fn run"))
    }

    @Test
    fun extractsMacroRulesDefinition() {
        val result = ExpandExtractor.extract(expanded, itemAt("macro_rules! twice"))
        assertTrue(result.content.contains("macro_rules! twice"))
        assertFalse(result.content.contains("pub fn use_macro"))
    }

    @Test
    fun wholeCrateReturnsEverything() {
        val result = ExpandExtractor.extract(expanded, itemAt("pub fn run"), wholeCrate = true)
        assertTrue(result.content.contains("#![feature(prelude_import)]"))
        assertTrue(result.content.contains("pub fn use_macro"))
    }

    @Test
    fun fallsBackToWholeCrateWhenMissing() {
        val item = ItemRef(
            kind = ItemKind.FN,
            name = "missing_function",
            modulePath = emptyList(),
            sourceSpan = spanOf(source, 0, 1),
        )
        val result = ExpandExtractor.extract(expanded, item)
        assertTrue(result.warnings.any { it.contains("Could not isolate") })
        assertTrue(result.content.contains("pub fn run"))
    }
}
