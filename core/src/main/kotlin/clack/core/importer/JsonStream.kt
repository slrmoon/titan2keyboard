package clack.core.importer

import java.io.Reader

/**
 * Small pull-style JSON reader for large files (a Telegram export can be hundreds of MB):
 * only the current token is held in memory. API modelled on android.util.JsonReader.
 */
class JsonStream(reader: Reader) : AutoCloseable {
    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

    private val r = if (reader is java.io.BufferedReader) reader else java.io.BufferedReader(reader, 1 shl 16)
    private var la = -2 // look-ahead char; -2 = none read yet
    private var peeked: Token? = null

    // per open container: is it an object, does a comma come before the next element,
    // and (objects only) is the next token a name
    private val isObject = ArrayList<Boolean>()
    private val needComma = ArrayList<Boolean>()
    private val expectName = ArrayList<Boolean>()

    private fun read(): Int { val c = if (la != -2) la else r.read(); la = -2; return c }
    private fun peekChar(): Int { if (la == -2) la = r.read(); return la }

    private fun skipWs() {
        while (true) {
            val c = peekChar()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == 0xFEFF) read() else return
        }
    }

    private fun fail(msg: String): Nothing = throw IllegalStateException("JSON: $msg")

    fun peek(): Token {
        peeked?.let { return it }
        skipWs()
        val inObject = isObject.lastOrNull()
        if (inObject != null) {
            val c = peekChar()
            if (c == (if (inObject) '}' else ']').code) {
                return (if (inObject) Token.END_OBJECT else Token.END_ARRAY).also { peeked = it }
            }
            if (needComma.last()) {
                if (read() != ','.code) fail("expected ,")
                needComma[needComma.size - 1] = false
                skipWs()
            }
        }
        val c = peekChar()
        val t = when {
            c < 0 -> Token.END
            inObject == true && expectName.last() -> if (c == '"'.code) Token.NAME else fail("expected name")
            c == '{'.code -> Token.BEGIN_OBJECT
            c == '['.code -> Token.BEGIN_ARRAY
            c == '"'.code -> Token.STRING
            c == 't'.code || c == 'f'.code -> Token.BOOLEAN
            c == 'n'.code -> Token.NULL
            c == '-'.code || (c >= '0'.code && c <= '9'.code) -> Token.NUMBER
            else -> fail("unexpected '${c.toChar()}'")
        }
        peeked = t
        return t
    }

    /** A complete value was read in the current container. */
    private fun valueDone() {
        peeked = null
        if (isObject.isEmpty()) return
        needComma[needComma.size - 1] = true
        if (isObject.last()) expectName[expectName.size - 1] = true
    }

    fun beginObject() { expect(Token.BEGIN_OBJECT); read(); open(true) }
    fun beginArray() { expect(Token.BEGIN_ARRAY); read(); open(false) }

    private fun open(obj: Boolean) {
        valueDone() // the container itself is a value of its parent
        isObject.add(obj); needComma.add(false); expectName.add(obj)
    }

    fun endObject() { expect(Token.END_OBJECT); read(); closeContainer() }
    fun endArray() { expect(Token.END_ARRAY); read(); closeContainer() }

    private fun closeContainer() {
        isObject.removeAt(isObject.size - 1); needComma.removeAt(needComma.size - 1); expectName.removeAt(expectName.size - 1)
        peeked = null
    }

    fun hasNext(): Boolean { val t = peek(); return t != Token.END_OBJECT && t != Token.END_ARRAY && t != Token.END }

    fun nextName(): String {
        expect(Token.NAME)
        val s = readStringLiteral()
        skipWs()
        if (read() != ':'.code) fail("expected :")
        peeked = null
        expectName[expectName.size - 1] = false
        return s
    }

    /** Strings, numbers and booleans as text. */
    fun nextString(): String {
        val t = peek()
        val s = when (t) {
            Token.STRING -> readStringLiteral()
            Token.NUMBER, Token.BOOLEAN -> readBare()
            else -> fail("expected string, got $t")
        }
        valueDone()
        return s
    }

    fun nextNull() { expect(Token.NULL); readBare(); valueDone() }

    fun skipValue() {
        when (peek()) {
            Token.BEGIN_OBJECT -> { beginObject(); while (hasNext()) { nextName(); skipValue() }; endObject() }
            Token.BEGIN_ARRAY -> { beginArray(); while (hasNext()) skipValue(); endArray() }
            Token.STRING, Token.NUMBER, Token.BOOLEAN -> nextString()
            Token.NULL -> nextNull()
            else -> fail("nothing to skip")
        }
    }

    private fun expect(t: Token) { val p = peek(); if (p != t) fail("expected $t, got $p") }

    private fun readBare(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peekChar()
            if (c < 0 || c == ','.code || c == '}'.code || c == ']'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
            sb.append(read().toChar())
        }
        return sb.toString()
    }

    private fun readStringLiteral(): String {
        if (read() != '"'.code) fail("expected \"")
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) fail("unterminated string")
            when (c) {
                '"'.code -> return sb.toString()
                '\\'.code -> when (val e = read()) {
                    '"'.code -> sb.append('"'); '\\'.code -> sb.append('\\'); '/'.code -> sb.append('/')
                    'b'.code -> sb.append('\b'); 'f'.code -> sb.append('\u000C'); 'n'.code -> sb.append('\n')
                    'r'.code -> sb.append('\r'); 't'.code -> sb.append('\t')
                    'u'.code -> {
                        val hex = CharArray(4) { read().toChar() }
                        sb.append(String(hex).toInt(16).toChar())
                    }
                    else -> fail("bad escape \\${e.toChar()}")
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    override fun close() = r.close()
}
