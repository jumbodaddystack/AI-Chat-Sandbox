package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.VectorStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FillRuleResolverTest {

    @Test
    fun ruleOf_mapsEvenOddNonZeroAndNullDefault() {
        assertEquals(FillRule.EVENODD, FillRuleResolver.ruleOf(VectorStyle(fillType = "evenOdd")))
        assertEquals(FillRule.EVENODD, FillRuleResolver.ruleOf(VectorStyle(fillType = "EVENODD")))
        assertEquals(FillRule.NONZERO, FillRuleResolver.ruleOf(VectorStyle(fillType = "nonZero")))
        assertEquals(FillRule.NONZERO, FillRuleResolver.ruleOf(VectorStyle(fillType = null)))
        assertEquals(FillRule.NONZERO, FillRuleResolver.ruleOf(VectorStyle(fillType = "weird")))
    }

    @Test
    fun fillTypeFor_roundTripsWithStyleFillType() {
        assertEquals("evenOdd", FillRuleResolver.fillTypeFor(FillRule.EVENODD))
        assertNull(FillRuleResolver.fillTypeFor(FillRule.NONZERO))
        // round-trip
        assertEquals(
            FillRule.EVENODD,
            FillRuleResolver.ruleOf(VectorStyle(fillType = FillRuleResolver.fillTypeFor(FillRule.EVENODD))),
        )
    }
}
