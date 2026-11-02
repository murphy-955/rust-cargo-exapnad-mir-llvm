package io.github.murphy955.rustreader.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RustTextMaskerTest {
    @Test
    fun masksLineComments() {
        val text = "let x = 1; // }}} {\nlet y = 2;"
        val masked = RustTextMasker.mask(text)
        assertEquals(text.length, masked.length)
        assertEquals(-1, masked.indexOf('}'))
    }

    @Test
    fun masksNestedBlockComments() {
        val text = "/* outer /* inner } */ still } */ let x = 1;"
        val masked = RustTextMasker.mask(text)
        assertEquals(-1, masked.indexOf('}'))
        assertEquals(1, masked.count { it == ';' })
    }

    @Test
    fun masksStringsAndEscapes() {
        val text = """let s = "a \" } b"; let c = 1;"""
        val masked = RustTextMasker.mask(text)
        assertEquals(2, masked.count { it == ';' })
        assertEquals(-1, masked.indexOf('}'))
    }

    @Test
    fun masksRawStringsWithHashes() {
        val text = "let s = r##\"raw } # \" still }\"##; let x = 1;"
        val masked = RustTextMasker.mask(text)
        assertEquals(2, masked.count { it == ';' })
        assertEquals(-1, masked.indexOf('}'))
    }

    @Test
    fun keepsLifetimesButMasksCharLiterals() {
        val text = "fn f<'a>(x: &'a str) -> char { '{' }"
        val masked = RustTextMasker.mask(text)
        assertEquals(1, masked.count { it == '{' })
        assertEquals(1, masked.count { it == '}' })
        assertNotNull(masked.indexOf("'a"))
    }

    @Test
    fun findsMatchingBraceOutsideLiterals() {
        val text = "fn f() { let s = \"}\"; if true { } } tail"
        val masked = RustTextMasker.mask(text)
        val open = masked.indexOf('{')
        val close = BraceScanner.findMatchingBrace(masked, open)
        assertNotNull(close)
        assertEquals("tail", text.substring(close!! + 1).trim())
    }

    @Test
    fun returnsNullForUnbalancedBrace() {
        val text = "fn f() {"
        assertNull(BraceScanner.findMatchingBrace(RustTextMasker.mask(text), text.indexOf('{')))
    }
}
