package com.aura.launcher.data

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.pow

/**
 * Answers simple sums typed into search ("12*7", "15% of 80", "(3+4)/2", "2^10") right in the
 * drawer. Only arithmetic: anything else returns null and search carries on as normal.
 */
object Calculator {
    fun evaluate(input: String): String? {
        val expr = normalize(input) ?: return null
        // Must contain an operator: "42" alone isn't a sum.
        if (expr.none { it in "+-*/^%" } || expr.count { it.isDigit() } == 0) return null
        val value = runCatching { Parser(expr).parse() }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return format(value)
    }

    private fun normalize(input: String): String? {
        var s = input.trim().lowercase().removePrefix("=").removeSuffix("=").trim()
        // "15% of 80" → "15/100*80"
        s = s.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*of\\s*(\\d+(?:\\.\\d+)?)"), "($1/100*$2)")
        s = s.replace('×', '*').replace('÷', '/').replace('−', '-').replace(Regex("(?<=\\d)\\s*x\\s*(?=[\\d(])"), "*")
            .replace(",", "").replace(" ", "")
        return s.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c in "+-*/^%.()" } }
    }

    private fun format(v: Double): String {
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return BigDecimal(v).toBigInteger().toString().let(::group)
        val bd = BigDecimal(v).round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        val plain = bd.toPlainString()
        val (whole, frac) = plain.split('.').let { it[0] to it.getOrNull(1) }
        return group(whole) + (frac?.let { ".$it" } ?: "")
    }

    private fun group(digits: String): String {
        val neg = digits.startsWith("-")
        val d = digits.removePrefix("-")
        return (if (neg) "-" else "") + d.reversed().chunked(3).joinToString(",").reversed()
    }

    /** expr := term (('+'|'-') term)* ; term := factor (('*'|'/'|'%') factor)* ; factor := unary ('^' factor)? */
    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double = expr().also { if (i != s.length) error("trailing") }
        private fun peek() = s.getOrNull(i)
        private fun expr(): Double {
            var v = term()
            while (true) v = when (peek()) {
                '+' -> { i++; v + term() }
                '-' -> { i++; v - term() }
                else -> return v
            }
        }
        private fun term(): Double {
            var v = factor()
            while (true) v = when (peek()) {
                '*' -> { i++; v * factor() }
                '/' -> { i++; factor().let { d -> if (d == 0.0) error("div0") else v / d } }
                '%' -> { i++; val next = peek(); if (next == null || next in "+-*/)") v / 100 else v % factor() }
                else -> return v
            }
        }
        private fun factor(): Double {
            val base = unary()
            return if (peek() == '^') { i++; base.pow(factor()) } else base
        }
        private fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            '(' -> { i++; expr().also { if (peek() != ')') error("paren"); i++ } }
            else -> number()
        }
        private fun number(): Double {
            val start = i
            while (peek()?.let { it.isDigit() || it == '.' } == true) i++
            return s.substring(start, i).toDoubleOrNull() ?: error("number")
        }
    }
}
