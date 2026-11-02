package io.github.murphy955.rustreader.context

import java.nio.file.Path

enum class TargetKind {
    LIB,
    BIN,
    EXAMPLE,
    TEST,
    BENCH,
    OTHER;

    companion object {
        fun fromCargoKinds(kinds: List<String>): TargetKind {
            if (kinds.any { it == "lib" || it == "rlib" || it == "proc-macro" || it == "dylib" || it == "staticlib" || it == "cdylib" }) {
                return LIB
            }
            return when {
                "bin" in kinds -> BIN
                "example" in kinds -> EXAMPLE
                "test" in kinds -> TEST
                "bench" in kinds -> BENCH
                else -> OTHER
            }
        }
    }
}

enum class ItemKind {
    FN,
    IMPL_METHOD,
    IMPL,
    STRUCT,
    ENUM,
    UNION,
    TRAIT,
    MACRO_RULES,
    MACRO_CALL,
    MODULE,
    TYPE_ALIAS,
    CONST,
    STATIC,
}

data class SourcePosition(val line: Int, val column: Int)

/**
 * Half-open source range. [end] points one character past the last included character.
 */
data class Span(
    val startOffset: Int,
    val endOffset: Int,
    val start: SourcePosition,
    val end: SourcePosition,
) {
    fun contains(offset: Int): Boolean = offset in startOffset until endOffset
}

fun positionAt(text: String, offset: Int): SourcePosition {
    val safeOffset = offset.coerceIn(0, text.length)
    var line = 1
    var lineStart = 0
    for (index in 0 until safeOffset) {
        if (text[index] == '\n') {
            line++
            lineStart = index + 1
        }
    }
    return SourcePosition(line, safeOffset - lineStart + 1)
}

fun spanOf(text: String, startOffset: Int, endOffset: Int): Span {
    val start = startOffset.coerceIn(0, text.length)
    val end = endOffset.coerceIn(start, text.length)
    return Span(start, end, positionAt(text, start), positionAt(text, end))
}

data class ItemRef(
    val kind: ItemKind,
    val name: String,
    val modulePath: List<String>,
    val implHeaderSpan: Span? = null,
    val sourceSpan: Span,
    val implSelfType: String? = null,
    val implTraitName: String? = null,
) {
    val qualifiedName: String
        get() = (modulePath + name).joinToString("::")
}

enum class ToolchainKind {
    AUTO,
    STABLE,
    NIGHTLY,
    CUSTOM,
}

data class ToolchainSpec(
    val kind: ToolchainKind = ToolchainKind.AUTO,
    val custom: String? = null,
    val cargoExecutable: String = "cargo",
) {
    val isNightly: Boolean
        get() = kind == ToolchainKind.NIGHTLY || (kind == ToolchainKind.CUSTOM && custom?.contains("nightly") == true)

    /** Toolchain argument understood by rustup, e.g. `+nightly`, or null for auto. */
    fun rustupArgument(): String? = when (kind) {
        ToolchainKind.AUTO -> null
        ToolchainKind.STABLE -> "+stable"
        ToolchainKind.NIGHTLY -> "+nightly"
        ToolchainKind.CUSTOM -> custom?.trim()?.takeIf { it.isNotEmpty() }?.let { "+${it.removePrefix("+")}" }
    }
}

enum class FeaturesMode {
    DEFAULT,
    ALL,
    NO_DEFAULT,
    CUSTOM,
}

data class FeaturesSpec(
    val mode: FeaturesMode = FeaturesMode.DEFAULT,
    val features: List<String> = emptyList(),
)

data class RustContext(
    val projectPath: Path,
    val file: Path,
    val caretOffset: Int,
    val crateRoot: Path,
    val modulePath: List<String>,
    val packageName: String,
    val packageManifest: Path,
    val targetName: String,
    val targetKind: TargetKind,
    val targetSrcPath: Path,
    val edition: String,
    val profile: String,
    val targetTriple: String,
    val toolchain: ToolchainSpec,
    val features: FeaturesSpec,
    val extraRustcArgs: List<String>,
    val targetDir: Path,
    val workspaceRoot: Path,
    val item: ItemRef?,
    val isNightlyToolchain: Boolean = false,
    val availableTargets: List<TargetRef> = emptyList(),
) {
    val crateName: String
        get() = targetName.replace('-', '_')

    val displayTarget: String
        get() = "${targetKind.name.lowercase()}:$targetName"
}

data class TargetRef(
    val name: String,
    val kind: TargetKind,
    val srcPath: Path,
) {
    val key: String
        get() = "${kind.name.lowercase()}:$name"

    override fun toString(): String = key
}
