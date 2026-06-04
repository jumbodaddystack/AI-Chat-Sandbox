package com.aichat.sandbox.data.vector.edit.boolean

import com.aichat.sandbox.data.vector.VectorStyle

/**
 * Phase 2 — maps between the document's nullable `fillType` string convention and
 * the module's [FillRule] enum.
 *
 * `VectorStyle.fillType` is a nullable `String` ("evenOdd"/"nonZero"/null); the
 * writers treat anything that isn't `"evenOdd"` (case-insensitive) as non-zero and
 * only emit the attribute for even-odd. We mirror that exactly: unknown/null ⇒
 * [FillRule.NONZERO], and the canonical even-odd token is `"evenOdd"`.
 */
internal object FillRuleResolver {

    fun ruleOf(style: VectorStyle): FillRule =
        if (style.fillType?.equals("evenOdd", ignoreCase = true) == true) {
            FillRule.EVENODD
        } else {
            FillRule.NONZERO
        }

    /** The `fillType` string to write back for [rule] (null ⇒ default non-zero). */
    fun fillTypeFor(rule: FillRule): String? =
        if (rule == FillRule.EVENODD) "evenOdd" else null
}
