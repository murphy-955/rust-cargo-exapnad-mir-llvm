package io.github.murphy955.rustreader.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemLocatorTest {
    private val source = """
        use std::fmt::Display;

        #[derive(Debug)]
        pub struct Point {
            pub x: u64,
        }

        impl Point {
            pub fn get(&self) -> u64 {
                self.x
            }
        }

        impl Display for Point {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                Ok(())
            }
        }

        pub fn run(n: u64) -> u64 {
            let f = |v: u64| -> u64 { v + n };
            f(n)
        }

        pub fn make_iter() -> impl Iterator<Item = u64> {
            0..3
        }

        pub mod nested {
            pub fn helper() -> u64 {
                1
            }
        }

        macro_rules! twice {
            (${'$'}e:expr) => { ${'$'}e + ${'$'}e };
        }
    """.trimIndent()

    private fun caretAt(token: String): Int {
        val index = source.indexOf(token)
        assertTrue("token not found: $token", index >= 0)
        return index
    }

    @Test
    fun locatesInherentImplMethod() {
        val item = ItemLocator.locate(source, caretAt("pub fn get"), listOf())
        assertNotNull(item)
        assertEquals(ItemKind.IMPL_METHOD, item!!.kind)
        assertEquals("get", item.name)
        assertEquals("Point", item.implSelfType)
        assertNull(item.implTraitName)
        assertEquals(8, item.implHeaderSpan!!.start.line)
        assertEquals("get", item.qualifiedName)
    }

    @Test
    fun locatesTraitImplMethodWithTraitName() {
        val item = ItemLocator.locate(source, caretAt("fn fmt"), listOf())
        assertNotNull(item)
        assertEquals(ItemKind.IMPL_METHOD, item!!.kind)
        assertEquals("fmt", item.name)
        assertEquals("Point", item.implSelfType)
        assertEquals("Display", item.implTraitName)
        assertEquals(14, item.implHeaderSpan!!.start.line)
    }

    @Test
    fun locatesFreeFunction() {
        val item = ItemLocator.locate(source, caretAt("pub fn run"), listOf())
        assertNotNull(item)
        assertEquals(ItemKind.FN, item!!.kind)
        assertEquals("run", item.name)
        assertNull(item.implHeaderSpan)
    }

    @Test
    fun locatesFunctionInsideClosure() {
        val item = ItemLocator.locate(source, caretAt("v + n"), listOf())
        assertNotNull(item)
        assertEquals(ItemKind.FN, item!!.kind)
        assertEquals("run", item.name)
    }

    @Test
    fun doesNotTreatImplTraitReturnTypeAsImplItem() {
        val item = ItemLocator.locate(source, caretAt("0..3"), listOf())
        assertNotNull(item)
        assertEquals(ItemKind.FN, item!!.kind)
        assertEquals("make_iter", item.name)
    }

    @Test
    fun locatesModuleFunction() {
        val item = ItemLocator.locate(source, caretAt("pub fn helper"), listOf("nested"))
        assertNotNull(item)
        assertEquals(ItemKind.FN, item!!.kind)
        assertEquals("nested::helper", item!!.qualifiedName)
    }

    @Test
    fun locatesStruct() {
        val item = ItemLocator.locate(source, caretAt("pub struct Point"), listOf())
        assertNotNull(item)
        assertEquals(ItemKind.STRUCT, item!!.kind)
        assertEquals("Point", item.name)
    }

    @Test
    fun locatesMacroRules() {
        val item = ItemLocator.locate(source, caretAt("macro_rules! twice"), listOf())
        assertNotNull(item)
        assertEquals(ItemKind.MACRO_RULES, item!!.kind)
        assertEquals("twice", item.name)
    }

    @Test
    fun returnsNullOutsideItems() {
        val item = ItemLocator.locate(source, 0, listOf())
        assertNull(item)
    }

    @Test
    fun ignoresBracesInsideStringsAndComments() {
        val tricky = """
            fn f() -> &'static str {
                // } } }
                let s = "{ } }";
                s
            }
            fn g() {}
        """.trimIndent()
        val item = ItemLocator.locate(tricky, tricky.indexOf("let s"), listOf())
        assertNotNull(item)
        assertEquals("f", item!!.name)
    }
}
