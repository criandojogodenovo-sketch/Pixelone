package com.pixelone.studio.osp

/**
 * Parser e escritor JSON minimalistas, usados APENAS para o manifest.json
 * do formato .osp (e metadados de spritesheet/atlas) — formatos próprios,
 * pequenos e versionados, escritos pelo próprio Pixel One.
 *
 * DECISÃO DE ARQUITETURA (Fases 1+2):
 * - ZERO dependências novas: a especificação pede evitar dependências
 *   desnecessárias; org.json não existe em testes JVM puros e a
 *   kotlinx-serialization exigiria plugin + runtime só para dois ficheiros
 *   pequenos e controlados.
 * - O parser suporta o subconjunto JSON necessário: objetos, arrays,
 *   strings (com escapes \" \\ \/ \b \f \n \r \t \uXXXX), números
 *   (long/double), true/false/null. Suficiente e rigoroso para os nossos
 *   manifestos; erros de sintaxe lançam [JsonParseException] com posição.
 * - O escritor produz JSON compacto e corretamente escapado.
 *
 * SEGURANÇA: o parser é rejeitante (sem arrays/objetos com profundidade
 * excessiva: máximo [MAX_DEPTH] = 32), impedindo estouros de pilha com
 * ficheiros maliciosos.
 */
object MiniJson {

    /** Erro de sintaxe JSON com a posição no texto. */
    class JsonParseException(message: String, val position: Int) :
        RuntimeException("$message (posição $position)")

    /** Representação genérica: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null. */
    const val MAX_DEPTH: Int = 32

    // ------------------------------------------------------------------
    // Leitura
    // ------------------------------------------------------------------

    /** Analisa [text] JSON e devolve Map/List/primitivas. Lança [JsonParseException]. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.parseValue(0)
        p.skipWhitespace()
        if (!p.atEnd()) throw p.error("Conteúdo extra após o fim do JSON")
        return value
    }

    private class Parser(val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun error(msg: String): JsonParseException = JsonParseException(msg, pos)

        fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }

        fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw error("Profundidade JSON excessiva")
            skipWhitespace()
            if (atEnd()) throw error("JSON terminado inesperadamente")
            return when (val c = text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> parseKeyword("true", true)
                'f' -> parseKeyword("false", false)
                'n' -> parseKeyword("null", null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else throw error("Caráter inesperado '$c'")
            }
        }

        fun parseObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw error("Esperada chave de objeto")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') throw error("Esperado ':' após chave")
                pos++
                map[key] = parseValue(depth + 1)
                skipWhitespace()
                if (atEnd()) throw error("Objeto não terminado")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw error("Esperado ',' ou '}' em objeto")
                }
            }
        }

        fun parseArray(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue(depth + 1))
                skipWhitespace()
                if (atEnd()) throw error("Array não terminado")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw error("Esperado ',' ou ']' em array")
                }
            }
        }

        fun parseString(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw error("String não terminada")
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return sb.toString()
                    }
                    '\\' -> {
                        pos++
                        if (atEnd()) throw error("Escape incompleto")
                        when (val e = text[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) throw error("Escape \\u incompleto")
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw error("Escape \\u inválido: $hex")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw error("Escape inválido '\\$e'")
                        }
                        pos++
                    }
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        fun parseKeyword(word: String, value: Any?): Any? {
            if (pos + word.length > text.length ||
                text.substring(pos, pos + word.length) != word
            ) {
                throw error("Palavra-chave inválida (esperado '$word')")
            }
            pos += word.length
            return value
        }

        fun parseNumber(): Any {
            val start = pos
            if (!atEnd() && text[pos] == '-') pos++
            var isDouble = false
            while (!atEnd()) {
                val c = text[pos]
                when {
                    c in '0'..'9' -> pos++
                    c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' -> {
                        isDouble = isDouble || c == '.' || c == 'e' || c == 'E'
                        pos++
                    }
                    else -> break
                }
            }
            val token = text.substring(start, pos)
            if (token.isEmpty() || token == "-") throw error("Número inválido")
            return if (isDouble) {
                token.toDoubleOrNull() ?: throw error("Número inválido: $token")
            } else {
                token.toLongOrNull() ?: throw error("Número inválido: $token")
            }
        }
    }

    // ------------------------------------------------------------------
    // Escrita
    // ------------------------------------------------------------------

    /** Serializa um Map/List/primitiva em JSON compacto. */
    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Double -> {
                if (value.isNaN() || value.isInfinite()) sb.append("null")
                else sb.append(value.toString())
            }
            is Float -> writeValue(sb, value.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, v)
                }
                sb.append(']')
            }
            is IntArray -> writeValue(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ------------------------------------------------------------------
    // Auxiliares de acesso seguro
    // ------------------------------------------------------------------

    /** Lê um campo obrigatório de um objeto JSON como String (erro controlado se ausente/tipo errado). */
    fun requireString(obj: Map<String, Any?>, key: String): String =
        when (val v = obj[key]) {
            is String -> v
            null -> throw JsonParseException("Campo obrigatório ausente: '$key'", -1)
            else -> throw JsonParseException("Campo '$key' deveria ser string", -1)
        }

    fun requireInt(obj: Map<String, Any?>, key: String): Int =
        when (val v = obj[key]) {
            is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt()
            else throw JsonParseException("Campo '$key' fora do intervalo de Int", -1)
            is Int -> v
            null -> throw JsonParseException("Campo obrigatório ausente: '$key'", -1)
            else -> throw JsonParseException("Campo '$key' deveria ser número inteiro", -1)
        }

    fun requireLong(obj: Map<String, Any?>, key: String): Long =
        when (val v = obj[key]) {
            is Long -> v
            is Int -> v.toLong()
            null -> throw JsonParseException("Campo obrigatório ausente: '$key'", -1)
            else -> throw JsonParseException("Campo '$key' deveria ser número", -1)
        }

    /** Lê um campo opcional de strings; devolve [default] se ausente. */
    fun optionalString(obj: Map<String, Any?>, key: String, default: String): String =
        (obj[key] as? String) ?: default

    /** Lê um campo opcional de lista de strings; devolve null se ausente. */
    fun optionalStringList(obj: Map<String, Any?>, key: String): List<String>? {
        val raw = obj[key] ?: return null
        if (raw !is List<*>) throw JsonParseException("Campo '$key' deveria ser uma lista de strings", -1)
        return raw.map { item ->
            item as? String
                ?: throw JsonParseException("Campo '$key' só pode conter strings", -1)
        }
    }
}
