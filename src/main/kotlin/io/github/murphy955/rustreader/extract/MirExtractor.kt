package io.github.murphy955.rustreader.extract

import io.github.murphy955.rustreader.context.ItemKind
import io.github.murphy955.rustreader.context.ItemRef

data class ExtractResult(
    val content: String,
    val warnings: List<String> = emptyList(),
    val matchedItems: List<String> = emptyList(),
    val empty: Boolean = false,
)

private data class MirFunction(
    val name: String,
    val lineStart: Int,
    val bodyOpen: Int,
    val bodyClose: Int,
) {
    val end: Int
        get() = bodyClose + 1
}

/**
 * Extracts one function (plus its closures) from rustc MIR pretty output.
 */
object MirExtractor {
    private val functionStart = Regex("""^fn (.+?)\(.*""", RegexOption.MULTILINE)
    // rustc prefixes impl methods with their module path, e.g.
    // `fn api::<impl at src/api.rs:29:1: 29:14>::new`. The prefix also appears for impls
    // nested in generated code (`...::deserialize::<impl at ...>::expecting`), so match the
    // last `<impl at ...>` segment.
    private val implHeader = Regex("""^(?:.*::)?<impl at (.+):(\d+):(\d+): (\d+):(\d+)>::(.+)$""")
    private val closureMarker = "{closure#"

    fun extract(
        mir: String,
        item: ItemRef?,
        includeClosures: Boolean = true,
        sourceFileHint: String? = null,
    ): ExtractResult {
        if (item == null) {
            return ExtractResult(
                content = mir,
                warnings = listOf("No enclosing item was found; showing the whole MIR file."),
                matchedItems = allFunctionNames(mir),
            )
        }

        val functions = parseFunctions(mir)
        val primary = when (item.kind) {
            ItemKind.IMPL_METHOD -> functions.filter { scoreImplMethod(it.name, item, sourceFileHint) > 0 }
            else -> {
                val scored = functions
                    .filter { closureMarker !in it.name && !it.name.startsWith("<impl at ") }
                    .map { it to scoreFreeFunction(it.name, item) }
                    .filter { it.second > 0 }
                val best = scored.maxOfOrNull { it.second }
                if (best == null) emptyList() else scored.filter { it.second == best }.map { it.first }
            }
        }

        if (primary.isEmpty()) {
            return ExtractResult(
                content = mir,
                warnings = listOf(
                    "Function '${item.qualifiedName}' was not found in the MIR output; showing the whole file.",
                ),
                matchedItems = allFunctionNames(mir),
            )
        }

        val selected = primary.toMutableList()
        if (includeClosures) {
            val prefixes = primary.map { it.name }
            functions.forEach { function ->
                if (function in selected) return@forEach
                if (prefixes.any { prefix -> function.name.startsWith("$prefix::$closureMarker") }) {
                    selected += function
                }
            }
        }

        val warnings = mutableListOf<String>()
        if (primary.size > 1) {
            warnings += "Multiple MIR functions matched '${item.name}'; showing all of them."
        }

        val content = selected
            .sortedBy { it.lineStart }
            .joinToString("\n\n") { mir.substring(it.lineStart, it.end) }

        return ExtractResult(
            content = content,
            warnings = warnings,
            matchedItems = selected.map { it.name },
        )
    }

    fun allFunctionNames(mir: String): List<String> = parseFunctions(mir).map { it.name }

    private fun scoreFreeFunction(mirName: String, item: ItemRef): Int {
        val qualified = item.qualifiedName
        return when {
            mirName == qualified -> 100
            mirName == "crate::$qualified" -> 95
            mirName == item.name -> 80
            mirName.endsWith("::${item.name}") -> 70
            else -> 0
        }
    }

    private fun scoreImplMethod(mirName: String, item: ItemRef, sourceFileHint: String?): Int {
        val match = implHeader.matchEntire(mirName) ?: return 0
        val file = match.groupValues[1]
        val startLine = match.groupValues[2].toIntOrNull() ?: return 0
        val startColumn = match.groupValues[3].toIntOrNull() ?: return 0
        val method = match.groupValues[6]
        if (method != item.name) return 0

        val header = item.implHeaderSpan ?: return 60
        if (header.start.line != startLine) return 0
        if (sourceFileHint != null && !sameFile(file, sourceFileHint)) return 0
        return if (header.start.column == startColumn) 200 else 150
    }

    private fun sameFile(rustcPath: String, hint: String): Boolean {
        val rustc = rustcPath.replace('\\', '/').removePrefix("./")
        val expected = hint.replace('\\', '/').removePrefix("./")
        return rustc.endsWith(expected) || expected.endsWith(rustc)
    }

    private fun parseFunctions(mir: String): List<MirFunction> {
        val masked = RustTextMasker.mask(mir)
        val functions = mutableListOf<MirFunction>()
        for (match in functionStart.findAll(masked)) {
            val name = match.groupValues[1].trim()
            val lineEnd = mir.indexOf('\n', match.range.first).let { if (it < 0) mir.length else it }
            var bodyOpen = -1
            var cursor = lineEnd - 1
            while (cursor > match.range.first) {
                if (masked[cursor] == '{' && mirroredBodyBrace(masked, cursor, lineEnd)) {
                    bodyOpen = cursor
                    break
                }
                cursor--
            }
            if (bodyOpen < 0) continue
            val bodyClose = BraceScanner.findMatchingBrace(masked, bodyOpen) ?: continue
            functions += MirFunction(name, match.range.first, bodyOpen, bodyClose)
        }
        return functions
    }

    /** The brace that opens the function body is the last non-whitespace character on the header line. */
    private fun mirroredBodyBrace(masked: String, index: Int, lineEnd: Int): Boolean {
        for (position in index + 1 until lineEnd) {
            if (!masked[position].isWhitespace()) return false
        }
        return true
    }
}
