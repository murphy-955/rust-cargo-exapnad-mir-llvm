package io.github.murphy955.rustreader.extract

/**
 * Replaces comments, string literals and character literals with spaces while keeping the
 * original offsets and newlines intact. Parsing over the masked text lets callers ignore braces
 * that appear inside literals or comments.
 */
object RustTextMasker {
    fun mask(text: String): String {
        val masked = text.toCharArray()
        var index = 0

        fun blank(from: Int, to: Int) {
            for (position in from until to.coerceAtMost(masked.size)) {
                if (masked[position] != '\n' && masked[position] != '\r') {
                    masked[position] = ' '
                }
            }
        }

        while (index < text.length) {
            when {
                text.startsWith("//", index) -> {
                    val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                    blank(index, end)
                    index = end
                }

                text.startsWith("/*", index) -> {
                    var depth = 1
                    var cursor = index + 2
                    while (cursor < text.length && depth > 0) {
                        when {
                            text.startsWith("/*", cursor) -> {
                                depth++
                                cursor += 2
                            }

                            text.startsWith("*/", cursor) -> {
                                depth--
                                cursor += 2
                            }

                            else -> cursor++
                        }
                    }
                    blank(index, cursor)
                    index = cursor
                }

                isRawStringStart(text, index) -> {
                    val end = rawStringEnd(text, index)
                    blank(index, end)
                    index = end
                }

                text[index] == '"' -> {
                    val end = normalStringEnd(text, index)
                    blank(index, end)
                    index = end
                }

                text[index] == '\'' -> {
                    val end = charLiteralEnd(text, index)
                    if (end > index) {
                        blank(index, end)
                        index = end
                    } else {
                        index++
                    }
                }

                else -> index++
            }
        }
        return String(masked)
    }

    private fun isRawStringStart(text: String, index: Int): Boolean {
        var cursor = index
        if (cursor < text.length && (text[cursor] == 'b' || text[cursor] == 'c')) {
            cursor++
        }
        if (cursor >= text.length || text[cursor] != 'r') return false
        cursor++
        while (cursor < text.length && text[cursor] == '#') cursor++
        return cursor < text.length && text[cursor] == '"'
    }

    private fun rawStringEnd(text: String, start: Int): Int {
        var cursor = start
        if (text[cursor] == 'b' || text[cursor] == 'c') cursor++
        cursor++ // 'r'
        var hashes = 0
        while (cursor < text.length && text[cursor] == '#') {
            hashes++
            cursor++
        }
        cursor++ // opening quote
        val terminator = "\"" + "#".repeat(hashes)
        val end = text.indexOf(terminator, cursor)
        return if (end < 0) text.length else end + terminator.length
    }

    private fun normalStringEnd(text: String, start: Int): Int {
        var cursor = start + 1
        while (cursor < text.length) {
            when (text[cursor]) {
                '\\' -> cursor += 2
                '"' -> return cursor + 1
                else -> cursor++
            }
        }
        return text.length
    }

    private fun charLiteralEnd(text: String, start: Int): Int {
        // `'a` is a lifetime unless a closing quote follows immediately.
        if (start + 1 >= text.length) return -1
        val next = text[start + 1]
        if (next == '\\') {
            var cursor = start + 2
            while (cursor < text.length) {
                if (text[cursor] == '\'') return cursor + 1
                if (text[cursor] == '\n') return -1
                cursor++
            }
            return -1
        }
        if (start + 2 < text.length && text[start + 2] == '\'') {
            return start + 3
        }
        return -1
    }
}
