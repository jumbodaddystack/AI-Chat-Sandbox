package com.aichat.sandbox.data.tools

import com.aichat.sandbox.data.model.FunctionDefinition
import com.aichat.sandbox.data.model.ToolDefinition

class CalculatorTool : ToolExecutor {
    override val name = "calculator"

    override val definition = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = "Evaluate a mathematical expression. Supports +, -, *, /, ^, parentheses, and common functions (sqrt, abs, sin, cos, tan, log, ln).",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "expression" to mapOf(
                        "type" to "string",
                        "description" to "The mathematical expression to evaluate, e.g. '(2 + 3) * 4' or 'sqrt(144)'"
                    )
                ),
                "required" to listOf("expression")
            )
        )
    )

    override suspend fun execute(arguments: String): String {
        return try {
            val parsed = com.google.gson.JsonParser.parseString(arguments).asJsonObject
            val expression = parsed.get("expression")?.asString
                ?: return "Error: missing 'expression' argument"
            val result = evaluate(expression)
            result.toBigDecimal().stripTrailingZeros().toPlainString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Simple recursive-descent expression evaluator.
     * Supports: +, -, *, /, ^, parentheses, and functions (sqrt, abs, sin, cos, tan, log, ln).
     */
    private fun evaluate(expr: String): Double {
        val tokens = tokenize(expr)
        val parser = Parser(tokens)
        val result = parser.parseExpression()
        if (parser.pos < tokens.size) {
            throw IllegalArgumentException("Unexpected token: ${tokens[parser.pos]}")
        }
        return result
    }

    private sealed class Token {
        data class Number(val value: Double) : Token()
        data class Op(val op: Char) : Token()
        data class Func(val name: String) : Token()
        data object LParen : Token()
        data object RParen : Token()
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val s = expr.replace(" ", "")
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    tokens.add(Token.Number(s.substring(start, i).toDouble()))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && s[i].isLetter()) i++
                    tokens.add(Token.Func(s.substring(start, i).lowercase()))
                }
                c == '(' -> { tokens.add(Token.LParen); i++ }
                c == ')' -> { tokens.add(Token.RParen); i++ }
                c in "+-*/^" -> { tokens.add(Token.Op(c)); i++ }
                else -> i++ // skip unknown chars
            }
        }
        return tokens
    }

    private class Parser(val tokens: List<Token>) {
        var pos = 0

        fun parseExpression(): Double {
            var left = parseTerm()
            while (pos < tokens.size) {
                val t = tokens[pos]
                if (t is Token.Op && (t.op == '+' || t.op == '-')) {
                    pos++
                    val right = parseTerm()
                    left = if (t.op == '+') left + right else left - right
                } else break
            }
            return left
        }

        fun parseTerm(): Double {
            var left = parsePower()
            while (pos < tokens.size) {
                val t = tokens[pos]
                if (t is Token.Op && (t.op == '*' || t.op == '/')) {
                    pos++
                    val right = parsePower()
                    left = if (t.op == '*') left * right else left / right
                } else break
            }
            return left
        }

        fun parsePower(): Double {
            var left = parseUnary()
            while (pos < tokens.size) {
                val t = tokens[pos]
                if (t is Token.Op && t.op == '^') {
                    pos++
                    val right = parseUnary()
                    left = Math.pow(left, right)
                } else break
            }
            return left
        }

        fun parseUnary(): Double {
            if (pos < tokens.size && tokens[pos] is Token.Op) {
                val op = (tokens[pos] as Token.Op).op
                if (op == '-') { pos++; return -parseAtom() }
                if (op == '+') { pos++; return parseAtom() }
            }
            return parseAtom()
        }

        fun parseAtom(): Double {
            if (pos >= tokens.size) throw IllegalArgumentException("Unexpected end of expression")
            return when (val t = tokens[pos]) {
                is Token.Number -> { pos++; t.value }
                is Token.LParen -> {
                    pos++ // consume '('
                    val value = parseExpression()
                    if (pos < tokens.size && tokens[pos] is Token.RParen) pos++ // consume ')'
                    value
                }
                is Token.Func -> {
                    pos++ // consume function name
                    if (pos < tokens.size && tokens[pos] is Token.LParen) {
                        pos++ // consume '('
                        val arg = parseExpression()
                        if (pos < tokens.size && tokens[pos] is Token.RParen) pos++
                        applyFunction(t.name, arg)
                    } else {
                        throw IllegalArgumentException("Expected '(' after function '${t.name}'")
                    }
                }
                else -> throw IllegalArgumentException("Unexpected token: $t")
            }
        }

        private fun applyFunction(name: String, arg: Double): Double = when (name) {
            "sqrt" -> Math.sqrt(arg)
            "abs" -> Math.abs(arg)
            "sin" -> Math.sin(arg)
            "cos" -> Math.cos(arg)
            "tan" -> Math.tan(arg)
            "log" -> Math.log10(arg)
            "ln" -> Math.log(arg)
            "ceil" -> Math.ceil(arg)
            "floor" -> Math.floor(arg)
            "round" -> Math.round(arg).toDouble()
            "pi" -> Math.PI
            "e" -> Math.E
            else -> throw IllegalArgumentException("Unknown function: $name")
        }
    }
}
