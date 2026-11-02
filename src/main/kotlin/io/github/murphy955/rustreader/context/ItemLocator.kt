package io.github.murphy955.rustreader.context

import io.github.murphy955.rustreader.extract.BraceScanner
import io.github.murphy955.rustreader.extract.RustTextMasker

/**
 * Lightweight textual locator for the Rust item under the caret.
 *
 * It deliberately avoids PSI and rust-analyzer so it also works while indexing is in progress.
 * The parser ignores comments and literals by working on [RustTextMasker] output.
 */
object ItemLocator {
    private data class RawItem(
        val kind: ItemKind,
        val name: String,
        val keywordStart: Int,
        val spanStart: Int,
        val spanEnd: Int,
        val bodyOpen: Int? = null,
        val bodyClose: Int? = null,
        val implHeaderStart: Int? = null,
        val implHeaderEnd: Int? = null,
        val implTraitName: String? = null,
    )

    private data class MacroCall(
        val name: String,
        val spanStart: Int,
        val spanEnd: Int,
    )

    private val fnRegex = Regex("""\bfn\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val implRegex = Regex("""\bimpl\b""")
    private val containerRegex = Regex("""\b(struct|enum|union|trait)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val typeRegex = Regex("""\btype\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val moduleRegex = Regex("""\bmod\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val macroRulesRegex = Regex("""\bmacro_rules!\s*([A-Za-z_][A-Za-z0-9_]*)""")
    private val constRegex = Regex("""\b(?:const|static)\s+(?:mut\s+)?([A-Za-z_][A-Za-z0-9_]*)""")
    private val macroCallRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)!\s*[\(\{\[]""")

    fun locate(text: String, caretOffset: Int, modulePath: List<String> = emptyList()): ItemRef? {
        if (text.isEmpty()) return null
        val masked = RustTextMasker.mask(text)
        val offset = caretOffset.coerceIn(0, text.length)

        val functions = findFunctions(text, masked)
        val impls = findImpls(text, masked)
        val containers = findContainers(text, masked)
        val macroCalls = findMacroCalls(text, masked)

        val function = functions
            .filter { containsInclusive(it.spanStart, it.spanEnd, offset) }
            .minByOrNull { it.spanEnd - it.spanStart }
        if (function != null) {
            val enclosingImpl = impls
                .filter { impl ->
                    val open = impl.bodyOpen ?: return@filter false
                    val close = impl.bodyClose ?: return@filter false
                    function.keywordStart in open..close
                }
                .minByOrNull { (it.bodyClose ?: 0) - (it.bodyOpen ?: 0) }

            if (enclosingImpl != null) {
                val headerSpan = implHeaderSpan(text, enclosingImpl)
                return ItemRef(
                    kind = ItemKind.IMPL_METHOD,
                    name = function.name,
                    modulePath = modulePath,
                    implHeaderSpan = headerSpan,
                    sourceSpan = spanOf(text, function.spanStart, function.spanEnd),
                    implSelfType = enclosingImpl.name,
                    implTraitName = enclosingImpl.implTraitName,
                )
            }
            return ItemRef(
                kind = ItemKind.FN,
                name = function.name,
                modulePath = modulePath,
                sourceSpan = spanOf(text, function.spanStart, function.spanEnd),
            )
        }

        val impl = impls
            .filter { containsInclusive(it.spanStart, it.spanEnd, offset) }
            .minByOrNull { it.spanEnd - it.spanStart }
        if (impl != null) {
            return ItemRef(
                kind = ItemKind.IMPL,
                name = impl.name,
                modulePath = modulePath,
                implHeaderSpan = implHeaderSpan(text, impl),
                sourceSpan = spanOf(text, impl.spanStart, impl.spanEnd),
                implSelfType = impl.name,
                implTraitName = impl.implTraitName,
            )
        }

        val container = containers
            .filter { containsInclusive(it.spanStart, it.spanEnd, offset) }
            .minByOrNull { it.spanEnd - it.spanStart }
        if (container != null) {
            return ItemRef(
                kind = container.kind,
                name = container.name,
                modulePath = modulePath,
                sourceSpan = spanOf(text, container.spanStart, container.spanEnd),
            )
        }

        val macroCall = macroCalls
            .filter { containsInclusive(it.spanStart, it.spanEnd, offset) }
            .minByOrNull { it.spanEnd - it.spanStart }
        if (macroCall != null) {
            return ItemRef(
                kind = ItemKind.MACRO_CALL,
                name = macroCall.name,
                modulePath = modulePath,
                sourceSpan = spanOf(text, macroCall.spanStart, macroCall.spanEnd),
            )
        }
        return null
    }

    private fun containsInclusive(start: Int, endExclusive: Int, offset: Int): Boolean =
        offset in start..endExclusive

    private fun implHeaderSpan(text: String, impl: RawItem): Span? {
        val start = impl.implHeaderStart ?: return null
        val end = impl.implHeaderEnd ?: return null
        return spanOf(text, start, end)
    }

    private fun findFunctions(text: String, masked: String): List<RawItem> =
        fnRegex.findAll(masked).mapNotNull { match ->
            val keywordStart = match.range.first
            val name = match.groupValues[1]
            val bodyOpen = BraceScanner.findBodyOpen(masked, match.range.last + 1)
            if (bodyOpen == null) {
                val semi = masked.indexOf(';', match.range.last + 1).takeIf { it >= 0 }
                    ?: return@mapNotNull null
                RawItem(
                    kind = ItemKind.FN,
                    name = name,
                    keywordStart = keywordStart,
                    spanStart = extendedStart(text, keywordStart),
                    spanEnd = semi + 1,
                )
            } else {
                val bodyClose = BraceScanner.findMatchingBrace(masked, bodyOpen) ?: return@mapNotNull null
                RawItem(
                    kind = ItemKind.FN,
                    name = name,
                    keywordStart = keywordStart,
                    spanStart = extendedStart(text, keywordStart),
                    spanEnd = bodyClose + 1,
                    bodyOpen = bodyOpen,
                    bodyClose = bodyClose,
                )
            }
        }.toList()

    private fun findImpls(text: String, masked: String): List<RawItem> =
        implRegex.findAll(masked).mapNotNull { match ->
            val keywordStart = match.range.first
            if (!isItemImpl(masked, keywordStart)) return@mapNotNull null

            val bodyOpen = BraceScanner.findBodyOpen(masked, match.range.last + 1) ?: return@mapNotNull null
            val bodyClose = BraceScanner.findMatchingBrace(masked, bodyOpen) ?: return@mapNotNull null
            var headerEnd = bodyOpen
            while (headerEnd > keywordStart && masked[headerEnd - 1].isWhitespace()) headerEnd--
            val headerText = text.substring(keywordStart, headerEnd)
            val headerInfo = parseImplHeader(headerText)
            RawItem(
                kind = ItemKind.IMPL,
                name = headerInfo.selfType,
                keywordStart = keywordStart,
                spanStart = extendedStart(text, keywordStart),
                spanEnd = bodyClose + 1,
                bodyOpen = bodyOpen,
                bodyClose = bodyClose,
                implHeaderStart = keywordStart,
                implHeaderEnd = headerEnd,
                implTraitName = headerInfo.traitName,
            )
        }.toList()

    private fun isItemImpl(masked: String, implStart: Int): Boolean {
        val lineStart = masked.lastIndexOf('\n', implStart - 1) + 1
        val prefix = masked.substring(lineStart, implStart).trim()
        if (prefix.isEmpty()) return true
        if (prefix == "unsafe" || prefix == "default") return true
        // `-> impl Trait`, `: impl Trait`, argument positions and similar are type positions.
        return !prefix.contains("->") &&
            !prefix.endsWith(":") &&
            !prefix.endsWith("=") &&
            !prefix.endsWith("(") &&
            !prefix.endsWith(",") &&
            !prefix.contains("fn ") &&
            !prefix.contains("let ")
    }

    private fun findContainers(text: String, masked: String): List<RawItem> {
        val items = mutableListOf<RawItem>()

        containerRegex.findAll(masked).forEach { match ->
            val keywordStart = match.range.first
            val keyword = match.groupValues[1]
            val name = match.groupValues[2]
            val kind = when (keyword) {
                "struct" -> ItemKind.STRUCT
                "enum" -> ItemKind.ENUM
                "union" -> ItemKind.UNION
                else -> ItemKind.TRAIT
            }
            addBlockItem(items, text, masked, kind, name, keywordStart, match.range.last + 1)
        }

        moduleRegex.findAll(masked).forEach { match ->
            addBlockItem(
                items, text, masked, ItemKind.MODULE,
                match.groupValues[1], match.range.first, match.range.last + 1,
            )
        }

        macroRulesRegex.findAll(masked).forEach { match ->
            val keywordStart = match.range.first
            val name = match.groupValues[1]
            val delimiterIndex = BraceScanner.findTopLevel(masked, match.range.last + 1, setOf('{', '(', '['))
                ?: return@forEach
            val close = findMatchingDelimiter(masked, delimiterIndex) ?: return@forEach
            items += RawItem(
                kind = ItemKind.MACRO_RULES,
                name = name,
                keywordStart = keywordStart,
                spanStart = extendedStart(text, keywordStart),
                spanEnd = close + 1,
                bodyOpen = delimiterIndex,
                bodyClose = close,
            )
        }

        typeRegex.findAll(masked).forEach { match ->
            val keywordStart = match.range.first
            // Skip associated types inside impl/trait bodies; the enclosing block still wins.
            val end = BraceScanner.findStatementEnd(masked, match.range.last + 1) ?: return@forEach
            val spanEnd = if (masked[end] == ';') end + 1 else end + 1
            items += RawItem(
                kind = ItemKind.TYPE_ALIAS,
                name = match.groupValues[1],
                keywordStart = keywordStart,
                spanStart = extendedStart(text, keywordStart),
                spanEnd = spanEnd,
            )
        }

        constRegex.findAll(masked).forEach { match ->
            val keywordStart = match.range.first
            val lineStart = masked.lastIndexOf('\n', keywordStart - 1) + 1
            val prefix = masked.substring(lineStart, keywordStart).trim()
            // Const generic parameters (`const N: usize`) are not items.
            if (prefix.isNotEmpty() && !prefix.startsWith("#")) return@forEach
            val end = BraceScanner.findStatementEnd(masked, match.range.last + 1) ?: return@forEach
            items += RawItem(
                kind = if (masked.substring(keywordStart, keywordStart + 6) == "static") ItemKind.STATIC else ItemKind.CONST,
                name = match.groupValues[1],
                keywordStart = keywordStart,
                spanStart = extendedStart(text, keywordStart),
                spanEnd = end + 1,
            )
        }

        return items
    }

    private fun addBlockItem(
        items: MutableList<RawItem>,
        text: String,
        masked: String,
        kind: ItemKind,
        name: String,
        keywordStart: Int,
        searchStart: Int,
    ) {
        val bodyOpen = BraceScanner.findBodyOpen(masked, searchStart)
        if (bodyOpen != null) {
            val bodyClose = BraceScanner.findMatchingBrace(masked, bodyOpen) ?: return
            items += RawItem(
                kind = kind,
                name = name,
                keywordStart = keywordStart,
                spanStart = extendedStart(text, keywordStart),
                spanEnd = bodyClose + 1,
                bodyOpen = bodyOpen,
                bodyClose = bodyClose,
            )
            return
        }
        val statementEnd = BraceScanner.findStatementEnd(masked, searchStart) ?: return
        if (masked[statementEnd] == ';') {
            items += RawItem(
                kind = kind,
                name = name,
                keywordStart = keywordStart,
                spanStart = extendedStart(text, keywordStart),
                spanEnd = statementEnd + 1,
            )
        }
    }

    private fun findMacroCalls(text: String, masked: String): List<MacroCall> =
        macroCallRegex.findAll(masked).mapNotNull { match ->
            val open = match.range.last
            val close = findMatchingDelimiter(masked, open) ?: return@mapNotNull null
            MacroCall(
                name = match.groupValues[1],
                spanStart = extendedStart(text, match.range.first),
                spanEnd = close + 1,
            )
        }.toList()

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

    private data class ImplHeaderInfo(val selfType: String, val traitName: String?)

    private fun parseImplHeader(header: String): ImplHeaderInfo {
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
            traitName = simpleTypeName(rest.substring(0, forIndex).trim())
            selfType = rest.substring(forIndex + 3).trim()
        } else {
            traitName = null
            selfType = rest
        }

        return ImplHeaderInfo(
            selfType = selfType.trim().replace(Regex("\\s+"), " ").ifEmpty { "impl" },
            traitName = traitName?.takeIf { it.isNotEmpty() },
        )
    }

    private fun simpleTypeName(type: String): String {
        var text = type.trim()
        val generic = text.indexOf('<')
        if (generic >= 0) text = text.substring(0, generic)
        return text.substringAfterLast("::").trim()
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
}
