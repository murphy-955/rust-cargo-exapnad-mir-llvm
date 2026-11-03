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

class MirExtractorTest {
    private val mir = Files.readString(Path.of("src/test/testData/fixtures/sample.mir"))
    private val source = Files.readString(Path.of("src/test/testData/sample-crate/src/lib.rs"))

    private fun itemAt(token: String): ItemRef {
        val offset = source.indexOf(token)
        assertTrue("token not found: $token", offset >= 0)
        return ItemLocator.locate(source, offset, listOf())!!
    }

    @Test
    fun extractsFreeFunctionAndClosure() {
        val result = MirExtractor.extract(mir, itemAt("pub fn run"), includeClosures = true, sourceFileHint = "src/lib.rs")
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.content.contains("fn run(_1: &Point, _2: u64) -> u64"))
        assertTrue(result.content.contains("fn run::{closure#0}"))
        assertFalse(result.content.contains("fn helper("))
    }

    @Test
    fun canExcludeClosures() {
        val result = MirExtractor.extract(mir, itemAt("pub fn run"), includeClosures = false, sourceFileHint = "src/lib.rs")
        assertTrue(result.content.contains("fn run("))
        assertFalse(result.content.contains("{closure#0}"))
    }

    @Test
    fun extractsInherentImplMethodBySpan() {
        val item = itemAt("pub fn get")
        val result = MirExtractor.extract(mir, item, includeClosures = true, sourceFileHint = "src/lib.rs")
        assertTrue(result.content.contains("fn <impl at src/lib.rs:9:1: 9:11>::get"))
        assertFalse(result.content.contains("fn run("))
    }

    @Test
    fun disambiguatesTraitImplMethodsByHeaderSpan() {
        val item = itemAt("fn fmt")
        val result = MirExtractor.extract(mir, item, includeClosures = true, sourceFileHint = "src/lib.rs")
        assertTrue(result.content.contains("fn <impl at src/lib.rs:15:1: 15:23>::fmt"))
        assertFalse(result.content.contains("fn <impl at src/lib.rs:3:10"))
    }

    @Test
    fun fallsBackToWholeFileWhenMissing() {
        val dummy = ItemRef(
            kind = ItemKind.FN,
            name = "missing_function",
            modulePath = emptyList(),
            sourceSpan = spanOf(source, 0, 1),
        )
        val result = MirExtractor.extract(mir, dummy, includeClosures = true, sourceFileHint = "src/lib.rs")
        assertTrue(result.warnings.any { it.contains("not found") })
        assertTrue(result.content.contains("fn run("))
        assertTrue(result.content.contains("fn helper("))
    }

    @Test
    fun handlesBracesInsidePanicMessages() {
        val result = MirExtractor.extract(mir, itemAt("pub fn run"), includeClosures = true, sourceFileHint = "src/lib.rs")
        // The closure comes after run in the file; overrunning the brace balance would swallow it.
        assertTrue(result.content.contains("fn run::{closure#0}"))
        assertFalse(result.content.contains("fn generic("))
    }

    @Test
    fun extractsModulePrefixedImplMethod() {
        val mirInModule = """
            fn api::<impl at src/lib.rs:9:1: 9:11>::get(_1: &Point) -> u64 {
                _0 = const 0_u64;
                return;
            }
        """.trimIndent()
        val result = MirExtractor.extract(
            mirInModule,
            itemAt("pub fn get"),
            includeClosures = false,
            sourceFileHint = "src/lib.rs",
        )
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.content.contains("fn api::<impl at src/lib.rs:9:1"))
    }

    @Test
    fun extractsModulePrefixedImplMethodClosure() {
        val mirInModule = """
            fn api::<impl at src/lib.rs:9:1: 9:11>::get(_1: &Point) -> u64 {
                _0 = const 0_u64;
                return;
            }

            fn api::<impl at src/lib.rs:9:1: 9:11>::get::{closure#0}(_1: &i32) -> u64 {
                _0 = const 0_u64;
                return;
            }
        """.trimIndent()
        val result = MirExtractor.extract(
            mirInModule,
            itemAt("pub fn get"),
            includeClosures = true,
            sourceFileHint = "src/lib.rs",
        )
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.matchedItems.size == 2)
        assertTrue(result.content.contains("::{closure#0}"))
    }

    @Test
    fun reportsAmbiguousFreeFunctions() {
        val ambiguous = """
            fn foo(_1: u64) -> u64 {
                _0 = _1;
                return;
            }

            fn foo(_1: u64) -> u64 {
                _0 = _1;
                return;
            }
        """.trimIndent()
        val item = ItemRef(
            kind = ItemKind.FN,
            name = "foo",
            modulePath = listOf("a"),
            sourceSpan = spanOf("fn foo() {}", 0, 1),
        )
        val result = MirExtractor.extract(ambiguous, item, includeClosures = false)
        assertTrue(result.warnings.any { it.contains("Multiple") })
        assertTrue(result.matchedItems.size == 2)
    }
}
