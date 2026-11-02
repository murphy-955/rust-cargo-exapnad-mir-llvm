package io.github.murphy955.rustreader.extract

import io.github.murphy955.rustreader.context.ItemKind
import io.github.murphy955.rustreader.context.ItemRef

/**
 * Extracts the current item (and auto-derived impls for types) from `-Zunpretty=expanded` output.
 */
object ExpandExtractor {
    private data class ImplBlock(
        val keywordStart: Int,
        val spanStart: Int,
        val bodyOpen: Int,
        val bodyClose: Int,
        val selfType: String,
        val traitName: String?,
    )

    fun extract(expanded: String, item: ItemRef?, wholeCrate: Boolean = false): ExtractResult {
        if (wholeCrate) {
            return ExtractResult(content = expanded)
        }
        if (item == null) {
            return ExtractResult(
                content = expanded,
                warnings = listOf("No enclosing item was found; showing the whole crate expansion."),
            )
        }
        if (item.kind == ItemKind.MACRO_CALL) {
            return ExtractResult(
                content = expanded,
                warnings = listOf(
                    "Macro expansion has no source mapping for the invocation; showing the whole crate expansion.",
                ),
            )
        }

        val masked = RustTextMasker.mask(expanded)
        val moduleRange = findModuleBody(masked, 0, expanded.length, item.modulePath)
        val searchStart = moduleRange?.first ?: 0
        val searchEnd = moduleRange?.last ?: expanded.length

        val excerpt = when (item.kind) {
            ItemKind.IMPL_METHOD -> findMethodBlock(expanded, masked, searchStart, searchEnd, item)
            ItemKind.IMPL -> findImplBlock(expanded, masked, searchStart, searchEnd, item)
            ItemKind.FN -> findFreeFunction(expanded, masked, searchStart, searchEnd, item.name)
                ?: findFunctionAnywhere(expanded, masked, searchStart, searchEnd, item.name)
            ItemKind.STRUCT, ItemKind.ENUM, ItemKind.UNION, ItemKind.TRAIT ->
                findTypeBlock(expanded, masked, searchStart, searchEnd, item)
            ItemKind.TYPE_ALIAS -> findKeywordBlock(expanded, masked, searchStart, searchEnd, "type", item.name)
            ItemKind.MODULE -> findKeywordBlock(expanded, masked, searchStart, searchEnd, "mod", item.name)
            ItemKind.MACRO_RULES -> findMacroRulesBlock(expanded, masked, searchStart, searchEnd, item.name)
            ItemKind.CONST -> findKeywordBlock(expanded, masked, searchStart, searchEnd, "const", item.name)
            ItemKind.STATIC -> findKeywordBlock(expanded, masked, searchStart, searchEnd, "static", item.name)
            ItemKind.MACRO_CALL -> null
        }

        if (excerpt == null) {
            return ExtractResult(
                content = expanded,
                warnings = listOf(
                    "Could not isolate '${item.name}' from the expanded crate; showing the whole expansion.",
                ),
            )
        }

        val derived = if (item.kind in setOf(ItemKind.STRUCT, ItemKind.ENUM, ItemKind.UNION, ItemKind.TRAIT)) {
            findAutoDerivedImpls(expanded, masked, item.name)
                .filterNot { it.text in excerpt }
                .joinToString("\n\n") { it.text }
        } else {
            ""
        }

        val content = if (derived.isEmpty()) excerpt else "$excerpt\n\n$derived"
        return ExtractResult(
            content = content,
            matchedItems = listOf(item.name) + if (derived.isEmpty()) emptyList() else listOf("auto-derived impls"),
        )
    }

    private fun findFreeFunction(
        text: String,
        masked: String,
        start: Int,
        end: Int,
        name: String,
    ): String? {
        val impls = implBlocks(text, masked, start, end)
        val regex = Regex("""\bfn\s+${Regex.escape(name)}\b""")
        return regex.findAll(masked, start)
            .filter { it.range.first < end }
            .filter { match -> impls.none { match.range.first in (it.bodyOpen + 1)..it.bodyClose } }
            .mapNotNull { match -> extractItemBlock(text, masked, match.range.first, ItemKind.FN) }
            .firstOrNull()
    }

    private fun findFunctionAnywhere(
        text: String,
        masked: String,
        start: Int,
        end: Int,
        name: String,
    ): String? {
        val regex = Regex("""\bfn\s+${Regex.escape(name)}\b""")
        return regex.findAll(masked, start)
            .filter { it.range.first < end }
            .mapNotNull { match -> extractItemBlock(text, masked, match.range.first, ItemKind.FN) }
            .firstOrNull()
    }

    private fun findMethodBlock(
        text: String,
        masked: String,
        start: Int,
        end: Int,
        item: ItemRef,
    ): String? {
        val impls = implBlocks(text, masked, start, end)
        val expectedType = item.implSelfType ?: item.name
        val expectedTrait = item.implTraitName
        val owning = impls.filter { impl ->
            sameType(impl.selfType, expectedType) &&
                (expectedTrait == null || sameSimpleName(impl.traitName, expectedTrait))
        }.ifEmpty {
            impls.filter { sameType(it.selfType, expectedType) }
        }
        for (impl in owning) {
            val found = findFunctionAnywhere(text, masked, impl.bodyOpen + 1, impl.bodyClose, item.name)
            if (found != null) return found
        }
        return findFunctionAnywhere(text, masked, start, end, item.name)
    }

    private fun findImplBlock(
        text: String,
        masked: String,
        start: Int,
        end: Int,
        item: ItemRef,
    ): String? {
        val impls = implBlocks(text, masked, start, end)
        val expectedType = item.implSelfType ?: item.name
        val expectedTrait = item.implTraitName
        val match = impls.firstOrNull {
            sameType(it.selfType, expectedType) &&
                (expectedTrait == null || sameSimpleName(it.traitName, expectedTrait))
        } ?: impls.firstOrNull { sameType(it.selfType, expectedType) }
            ?: impls.firstOrNull()
            ?: return null
        return text.substring(match.spanStart, match.bodyClose + 1)
    }

    private fun findTypeBlock(
        text: String,
        masked: String,
        start: Int,
        end: Int,
        item: ItemRef,
    ): String? {
        val keyword = when (item.kind) {
            ItemKind.STRUCT -> "struct"
            ItemKind.ENUM -> "enum"
            ItemKind.UNION -> "union"
            else -> "trait"
        }
        return findKeywordBlock(text, masked, start, end, keyword, item.name)
    }

    private fun findKeywordBlock(
        text: String,
        masked: String,
        start: Int,
        end: Int,
        keyword: String,
        name: String,
    ): String? {
        val regex = Regex("""\b${Regex.escape(keyword)}\s+${Regex.escape(name)}\b""")
        val match = regex.findAll(masked, start).firstOrNull { it.range.first < end } ?: return null
        val kind = when (keyword) {
            "struct" -> ItemKind.STRUCT
            "enum" -> ItemKind.ENUM
            "union" -> ItemKind.UNION
            "trait" -> ItemKind.TRAIT
            "mod" -> ItemKind.MODULE
            "type" -> ItemKind.TYPE_ALIAS
            "const" -> ItemKind.CONST
            else -> ItemKind.STATIC
        }
        return extractItemBlock(text, masked, match.range.first, kind)
    }

    private fun findMacroRulesBlock(
        text: String,
        masked: String,
        start: Int,
        end: Int,
        name: String,
    ): String? {
        val regex = Regex("""\bmacro_rules!\s*${Regex.escape(name)}\b""")
        val match = regex.findAll(masked, start).firstOrNull { it.range.first < end } ?: return null
        return extractItemBlock(text, masked, match.range.first, ItemKind.MACRO_RULES)
    }

    private fun extractItemBlock(text: String, masked: String, keywordStart: Int, kind: ItemKind): String? {
        val searchFrom = keywordStart + when (kind) {
            ItemKind.MACRO_RULES -> 1
            else -> 2
        }
        val bodyOpen = when (kind) {
            ItemKind.MACRO_RULES -> BraceScanner.findTopLevel(masked, searchFrom, setOf('{', '(', '['))
            ItemKind.TYPE_ALIAS, ItemKind.CONST, ItemKind.STATIC -> null
            else -> BraceScanner.findBodyOpen(masked, searchFrom)
        }

        val endOffset = if (bodyOpen != null) {
            val close = findMatchingDelimiter(masked, bodyOpen) ?: return null
            close + 1
        } else {
            val statementEnd = BraceScanner.findStatementEnd(masked, searchFrom) ?: return null
            statementEnd + 1
        }

        val startOffset = extendedStart(text, keywordStart)
        return text.substring(startOffset, endOffset)
    }

    private fun findMatchingDelimiter(masked: String, openIndex: Int): Int? {
        val open = masked.getOrNull(openIndex) ?: return null
        val close = when (open) {
            '{' -> '}'
            '(' -> ')'
            '[' -> ']'
            else -> return null
        }
        return BraceScanner.findMatchingBrace(masked, openIndex, open, close)
    }

    private fun findModuleBody(masked: String, start: Int, end: Int, modulePath: List<String>): IntRange? {
        if (modulePath.isEmpty()) return start until end
        var bodyStart = start
        var bodyEnd = end
        for (segment in modulePath) {
            val regex = Regex("""\bmod\s+${Regex.escape(segment)}\s*\{""")
            val match = regex.findAll(masked, bodyStart)
                .firstOrNull { it.range.first in bodyStart until bodyEnd }
                ?: return null
            val open = match.range.last
            val close = BraceScanner.findMatchingBrace(masked, open) ?: return null
            bodyStart = open + 1
            bodyEnd = close
        }
        return bodyStart until bodyEnd
    }

    private fun implBlocks(text: String, masked: String, start: Int, end: Int): List<ImplBlock> {
        val regex = Regex("""\bimpl\b""")
        return regex.findAll(masked, start)
            .filter { it.range.first < end }
            .mapNotNull { match ->
                if (!isItemImpl(masked, match.range.first)) return@mapNotNull null
                val bodyOpen = BraceScanner.findBodyOpen(masked, match.range.last + 1) ?: return@mapNotNull null
                val bodyClose = BraceScanner.findMatchingBrace(masked, bodyOpen) ?: return@mapNotNull null
                var headerEnd = bodyOpen
                while (headerEnd > match.range.first && masked[headerEnd - 1].isWhitespace()) headerEnd--
                val header = text.substring(match.range.first, headerEnd)
                val headerInfo = implHeaderInfo(header)
                ImplBlock(
                    keywordStart = match.range.first,
                    spanStart = extendedStart(text, match.range.first),
                    bodyOpen = bodyOpen,
                    bodyClose = bodyClose,
                    selfType = headerInfo.first,
                    traitName = headerInfo.second,
                )
            }
            .toList()
    }

    private fun isItemImpl(masked: String, implStart: Int): Boolean {
        val lineStart = masked.lastIndexOf('\n', implStart - 1) + 1
        val prefix = masked.substring(lineStart, implStart).trim()
        if (prefix.isEmpty()) return true
        if (prefix == "unsafe" || prefix == "default") return true
        return !prefix.contains("->") &&
            !prefix.endsWith(":") &&
            !prefix.endsWith("=") &&
            !prefix.endsWith("(") &&
            !prefix.endsWith(",") &&
            !prefix.contains("fn ") &&
            !prefix.contains("let ")
    }

    private fun implHeaderInfo(header: String): Pair<String, String?> {
        var rest = header.substringAfter("impl", header).trim()
        if (rest.startsWith("<")) {
            val close = matchingAngle(rest, 0)
            if (close != null) rest = rest.substring(close + 1).trim()
        }
        val whereIndex = findTopLevelWord(rest, "where")
        if (whereIndex != null) rest = rest.substring(0, whereIndex).trim()
        val forIndex = findTopLevelWord(rest, "for")
        val selfType: String
        val traitName: String?
        if (forIndex != null) {
            traitName = SymbolMatcher.simpleTypeName(rest.substring(0, forIndex).trim()).takeIf { it.isNotEmpty() }
            selfType = rest.substring(forIndex + 3).trim()
        } else {
            traitName = null
            selfType = rest
        }
        return selfType.replace(Regex("\\s+"), " ").ifEmpty { "impl" } to traitName
    }

    private data class DerivedImpl(val text: String)

    private fun findAutoDerivedImpls(text: String, masked: String, typeName: String): List<DerivedImpl> {
        val impls = implBlocks(text, masked, 0, text.length)
        return impls.mapNotNull { impl ->
            if (!sameType(impl.selfType, typeName)) return@mapNotNull null
            if (!hasAutoDerivedAttribute(text, impl.keywordStart)) return@mapNotNull null
            DerivedImpl(text.substring(impl.spanStart, impl.bodyClose + 1))
        }
    }

    private fun hasAutoDerivedAttribute(text: String, keywordStart: Int): Boolean {
        var lineBreak = text.lastIndexOf('\n', keywordStart - 1)
        var guard = 0
        while (lineBreak > 0 && guard++ < 20) {
            val lineStart = text.lastIndexOf('\n', lineBreak - 1) + 1
            val line = text.substring(lineStart, lineBreak).trim()
            if (line.isEmpty()) return false
            if (line.contains("#[automatically_derived]")) return true
            if (!line.startsWith("#[") && !line.startsWith("///") && !line.startsWith("//!")) return false
            lineBreak = lineStart - 1
        }
        return false
    }

    private fun sameType(candidate: String, expected: String): Boolean {
        return SymbolMatcher.simpleTypeName(candidate).equals(SymbolMatcher.simpleTypeName(expected), ignoreCase = false)
    }

    private fun sameSimpleName(candidate: String?, expected: String): Boolean {
        if (candidate == null) return false
        return SymbolMatcher.simpleTypeName(candidate).equals(SymbolMatcher.simpleTypeName(expected), ignoreCase = false)
    }

    private fun matchingAngle(text: String, openIndex: Int): Int? {
        var depth = 0
        var index = openIndex
        while (index < text.length) {
            when (text[index]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun findTopLevelWord(text: String, word: String): Int? {
        var angle = 0
        var parens = 0
        var brackets = 0
        var index = 0
        while (index <= text.length - word.length) {
            when (text[index]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> parens++
                ')' -> if (parens > 0) parens--
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
            }
            if (angle == 0 && parens == 0 && brackets == 0 && text.startsWith(word, index)) {
                val before = text.getOrNull(index - 1)
                val after = text.getOrNull(index + word.length)
                val beforeOk = before == null || !before.isLetterOrDigit() && before != '_'
                val afterOk = after == null || !after.isLetterOrDigit() && after != '_'
                if (beforeOk && afterOk) return index
            }
            index++
        }
        return null
    }

    private fun extendedStart(text: String, keywordStart: Int): Int {
        var cursor = text.lastIndexOf('\n', keywordStart - 1) + 1
        var result = cursor
        var guard = 0
        while (cursor > 0 && guard++ < 100) {
            val previousLineBreak = cursor - 1
            val previousStart = text.lastIndexOf('\n', previousLineBreak - 1) + 1
            val line = text.substring(previousStart, previousLineBreak).trim()
            val isAttribute = line.startsWith("#[") || line.startsWith("#![")
            val isDoc = line.startsWith("///") || line.startsWith("//!") || line.startsWith("/**") || line.startsWith("*")
            if (line.isEmpty() || !(isAttribute || isDoc)) break
            result = previousStart
            cursor = previousStart
        }
        while (result < text.length && (text[result] == ' ' || text[result] == '\t')) result++
        return result
    }
}
