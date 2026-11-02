package io.github.murphy955.rustreader.extract

/**
 * Brace matching helpers that operate on [RustTextMasker] output so braces inside comments,
 * strings and character literals are never counted.
 */
object BraceScanner {
    fun findMatchingBrace(text: String, openIndex: Int, open: Char = '{', close: Char = '}'): Int? {
        if (openIndex !in text.indices || text[openIndex] != open) return null
        var depth = 0
        var index = openIndex
        while (index < text.length) {
            when (text[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    /**
     * Finds the next occurrence of one of [targets] at nesting depth zero for parentheses and
     * brackets. Used to locate an item body or signature terminator.
     */
    fun findTopLevel(text: String, start: Int, targets: Set<Char>): Int? {
        var parens = 0
        var brackets = 0
        var index = start
        while (index < text.length) {
            val char = text[index]
            when (char) {
                '(' -> parens++
                ')' -> if (parens > 0) parens--
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                else -> if (parens == 0 && brackets == 0 && char in targets) return index
            }
            index++
        }
        return null
    }

    /**
     * Finds the `{` that opens an item body, skipping angle-bracketed generics, parentheses and
     * brackets in the header. Returns null when the item ends with `;` instead.
     */
    fun findBodyOpen(text: String, start: Int): Int? {
        var angle = 0
        var parens = 0
        var brackets = 0
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> parens++
                ')' -> if (parens > 0) parens--
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '{' -> if (angle == 0 && parens == 0 && brackets == 0) return index
                ';' -> if (angle == 0 && parens == 0 && brackets == 0) return null
            }
            index++
        }
        return null
    }

    fun findStatementEnd(text: String, start: Int): Int? {
        var angle = 0
        var parens = 0
        var brackets = 0
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> parens++
                ')' -> if (parens > 0) parens--
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                ';' -> if (angle == 0 && parens == 0 && brackets == 0) return index
                '{' -> if (angle == 0 && parens == 0 && brackets == 0) return index
            }
            index++
        }
        return null
    }

    fun extractBlock(text: String, maskedText: String, openIndex: Int): String? {
        val close = findMatchingBrace(maskedText, openIndex) ?: return null
        return text.substring(openIndex, close + 1)
    }
}
