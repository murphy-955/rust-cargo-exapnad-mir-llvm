package io.github.murphy955.rustreader.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolMatcherTest {
    @Test
    fun prefersFreeFunctionOverClosure() {
        val free = SymbolMatcher.score("_RNvCsd3_12sample_crate3run", "sample_crate", emptyList(), "run")
        val closure = SymbolMatcher.score("_RNCNvCsd3_12sample_crate3run0B3_", "sample_crate", emptyList(), "run")
        assertTrue(free > closure)
        assertTrue(closure < 0)
    }

    @Test
    fun rewardsModuleTokensInOrder() {
        val plain = SymbolMatcher.score("_RNvNtC1_1a6helper", "a", emptyList(), "helper")
        val nested = SymbolMatcher.score("_RNvNtC1_1a6nested6helper", "a", listOf("nested"), "helper")
        assertTrue(nested > plain)
    }

    @Test
    fun rewardsImplSelfType() {
        val plain = SymbolMatcher.score("_RNvMC1_1a3get", "a", emptyList(), "get")
        val impl = SymbolMatcher.score("_RNvMC1_1aNtB2_5Point3get", "a", emptyList(), "get", implSelfType = "Point")
        assertTrue(impl > plain)
    }

    @Test
    fun returnsZeroWhenNameDoesNotAppear() {
        assertEquals(0, SymbolMatcher.score("_RNvC1_1a5other", "a", emptyList(), "run"))
    }

    @Test
    fun prefersOwnImplOverGenericInstantiation() {
        val own = "_RNvMC1_1aNtB2_8AppState3new"
        val generic = "_RNvMs1_NtC2_6routerINtB5_6RouterNtNtC1_1a8AppStateE3newBP_"
        assertFalse(SymbolMatcher.isInsideGenericArgs(own, own.indexOf("8AppState")))
        assertTrue(SymbolMatcher.isInsideGenericArgs(generic, generic.indexOf("8AppState")))
        assertTrue(
            SymbolMatcher.score(own, "a", emptyList(), "new", implSelfType = "AppState") >
                SymbolMatcher.score(generic, "a", emptyList(), "new", implSelfType = "AppState"),
        )
    }

    @Test
    fun simpleTypeNameStripsReferencesAndGenerics() {
        assertEquals("Point", SymbolMatcher.simpleTypeName("&mut Point<u64>"))
        assertEquals("Point", SymbolMatcher.simpleTypeName("std::point::Point"))
    }
}
