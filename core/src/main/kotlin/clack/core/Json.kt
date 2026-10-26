package clack.core

/**
 * Minimal JSON parser for small configuration files (layouts, alt layers).
 * Produces Map<String, Any?>, List<Any?>, String, Double, Boolean or null.
 * Not meant for huge inputs: the Telegram importer uses a streaming reader instead.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.readValue()
        p.skipWs()
        if (!p.atEnd()) p.fail("Unexpected trailing content")
        return value
    }

    class ParseException(message: String) : IllegalArgumentException(message)

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(msg: String): Nothing = throw ParseException("$msg at position $i")

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t' || s[i] == '﻿')) i++
        }

        fun readValue(): Any? {
            if (atEnd()) fail("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Expected $word")
            i += word.length
            return value
        }

        private fun readNumber(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("Bad number")
        }

        private fun readString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[i] == ']') { i++; return out }
            while (true) {
                skipWs()
                out.add(readValue())
                skipWs()
                if (atEnd()) fail("Unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> { i--; fail("Expected , or ]") }
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') fail("Expected key")
                val key = readString()
                skipWs()
                if (atEnd() || s[i] != ':') fail("Expected :")
                i++
                skipWs()
                out[key] = readValue()
                skipWs()
                if (atEnd()) fail("Unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> { i--; fail("Expected , or }") }
                }
            }
        }
    }
}
