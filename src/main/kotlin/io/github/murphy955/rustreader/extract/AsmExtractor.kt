package io.github.murphy955.rustreader.extract

import io.github.murphy955.rustreader.context.ItemKind
import io.github.murphy955.rustreader.context.ItemRef

data class AsmSymbolBlock(
    val symbol: String,
    val text: String,
    val startOffset: Int,
)

data class AsmExtractResult(
    val content: String,
    val warnings: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
    val empty: Boolean = false,
)

/**
 * Extracts function blocks from rustc `--emit=asm` output by matching v0/legacy mangled symbols.
 */
object AsmExtractor {
    private val typeDirective = Regex("""^\s*\.type\s+([^\s,]+)\s*,\s*[@%]function\s*$""")
    private val preambleDirectives = setOf(
        ".section", ".globl", ".global", ".p2align", ".prefalign", ".balign", ".align",
        ".hidden", ".weak", ".private_extern", ".local", ".text", ".data", "#", "//",
    )

    fun extract(
        asm: String,
        crateName: String,
        modulePath: List<String>,
        item: ItemRef?,
        wholeFile: Boolean = false,
    ): AsmExtractResult {
        if (wholeFile || item == null) {
            return AsmExtractResult(
                content = asm,
                warnings = if (item == null) listOf("No enclosing item was found; showing the whole assembly file.") else emptyList(),
                candidates = parseSymbolBlocks(asm).map { it.symbol },
            )
        }

        val blocks = parseSymbolBlocks(asm)
        val scored = blocks.map { block ->
            val selfType = if (item.kind == ItemKind.IMPL_METHOD || item.kind == ItemKind.IMPL) item.implSelfType else null
            block to SymbolMatcher.score(
                symbol = block.symbol,
                crateName = crateName,
                modulePath = modulePath,
                itemName = item.name,
                implSelfType = selfType,
            )
        }.filter { it.second > 0 }

        if (scored.isEmpty()) {
            return AsmExtractResult(
                content = "",
                warnings = listOf(missingSymbolMessage(item)),
                candidates = blocks.map { it.symbol },
                empty = true,
            )
        }

        val best = scored.maxOf { it.second }
        val selected = scored.filter { it.second == best }.map { it.first }
        val warnings = mutableListOf<String>()
        if (selected.size > 1) {
            warnings += "Multiple assembly symbols matched '${item.name}'; showing all candidates."
        }

        return AsmExtractResult(
            content = selected.joinToString("\n\n") { it.text },
            warnings = warnings,
            candidates = selected.map { it.symbol },
        )
    }

    fun parseSymbolBlocks(asm: String): List<AsmSymbolBlock> {
        val lines = splitLines(asm)
        val blocks = mutableListOf<AsmSymbolBlock>()
        var index = 0
        while (index < lines.size) {
            val match = typeDirective.matchEntire(lines[index].text.trimEnd())
            if (match == null) {
                index++
                continue
            }
            val symbol = match.groupValues[1]
            val startLine = findPreambleStart(lines, index)
            val sizeLine = (index + 1 until lines.size).firstOrNull { lineIndex ->
                val trimmed = lines[lineIndex].text.trim()
                trimmed.startsWith(".size $symbol,") || trimmed.startsWith(".size\t$symbol,")
            }
            val endLine = sizeLine ?: findNextFunctionLine(lines, index + 1)
            val startOffset = lines[startLine].start
            val endOffset = when {
                sizeLine != null -> lines[sizeLine].end
                endLine != null && endLine < lines.size -> lines[endLine].start
                else -> asm.length
            }
            blocks += AsmSymbolBlock(symbol, asm.substring(startOffset, endOffset), startOffset)
            index = when {
                sizeLine != null -> sizeLine + 1
                endLine != null -> endLine
                else -> index + 1
            }
        }
        return blocks
    }

    private fun findPreambleStart(lines: List<Line>, typeLine: Int): Int {
        var start = typeLine
        var cursor = typeLine - 1
        while (cursor >= 0) {
            val trimmed = lines[cursor].text.trim()
            if (trimmed.isEmpty()) {
                cursor--
                continue
            }
            val directive = trimmed.substringBefore('\t').substringBefore(' ')
            if (directive in preambleDirectives || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                start = cursor
                cursor--
            } else {
                break
            }
        }
        return start
    }

    private fun findNextFunctionLine(lines: List<Line>, from: Int): Int? {
        for (index in from until lines.size) {
            if (typeDirective.matchEntire(lines[index].text.trimEnd()) != null) return index
        }
        return null
    }

    private fun missingSymbolMessage(item: ItemRef): String {
        val display = item.qualifiedName.ifEmpty { item.name }
        return "Function '$display' was not found in the generated assembly. " +
            "It may have been inlined, optimized away, not monomorphized, or not reachable from the selected target. " +
            "Try #[inline(never)] on the function, a non-generic pub wrapper, or a different profile. " +
            "Use \"Whole .s\" to inspect the complete file."
    }

    private data class Line(val start: Int, val end: Int, val text: String)

    private fun splitLines(text: String): List<Line> {
        val lines = mutableListOf<Line>()
        var start = 0
        var index = 0
        while (index <= text.length) {
            if (index == text.length || text[index] == '\n') {
                val end = if (index < text.length) index + 1 else index
                lines += Line(start, end, text.substring(start, index))
                start = end
            }
            index++
        }
        return lines
    }
}
