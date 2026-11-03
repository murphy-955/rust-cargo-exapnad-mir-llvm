package io.github.murphy955.rustreader.command

import io.github.murphy955.rustreader.context.FeaturesMode
import io.github.murphy955.rustreader.context.FeaturesSpec
import io.github.murphy955.rustreader.context.ItemKind
import io.github.murphy955.rustreader.context.RustContext
import io.github.murphy955.rustreader.context.TargetKind
import java.nio.file.Path

enum class ArtifactKind {
    MACRO,
    MIR,
    ASM,
}

enum class MacroBackend {
    AUTO,
    CARGO_EXPAND,
    RUSTC,
}

data class PlannerOptions(
    val macroBackend: MacroBackend = MacroBackend.AUTO,
    val allowRustcBootstrap: Boolean = true,
    val preferCargoShowAsm: Boolean = false,
    val isolatedTargetDir: Boolean = false,
)

enum class OutputMode {
    STDOUT,
    ARTIFACT,
}

data class CommandPlan(
    val kind: ArtifactKind,
    val spec: ProcessSpec,
    val displayCommand: String,
    val outputMode: OutputMode,
    val artifactExtension: String? = null,
    val artifactSearchDirs: List<Path> = emptyList(),
    val crateFilePrefix: String = "",
    val itemAlreadyExtracted: Boolean = false,
    val targetSourcePath: Path? = null,
    val warnings: List<String> = emptyList(),
)

fun interface ToolAvailability {
    fun isAvailable(binaryName: String, cargoExecutable: String): Boolean

    companion object {
        val SYSTEM = ToolAvailability { binary, cargo -> ToolDetector.isCargoSubcommandAvailable(binary, cargo) }
    }
}

class CommandPlanner(
    private val options: PlannerOptions = PlannerOptions(),
    private val toolAvailability: ToolAvailability = ToolAvailability.SYSTEM,
) {
    fun plan(kind: ArtifactKind, context: RustContext): CommandPlan = when (kind) {
        ArtifactKind.MACRO -> planMacro(context)
        ArtifactKind.MIR -> planMir(context)
        ArtifactKind.ASM -> planAsm(context)
    }

    private fun planMacro(context: RustContext): CommandPlan {
        val warnings = mutableListOf<String>()
        val cargoExpandAvailable = toolAvailability.isAvailable("cargo-expand", context.toolchain.cargoExecutable)
        val useCargoExpand = when (options.macroBackend) {
            MacroBackend.CARGO_EXPAND -> {
                if (!cargoExpandAvailable) {
                    warnings += "cargo-expand was requested but was not found on PATH; falling back to rustc."
                }
                cargoExpandAvailable
            }

            MacroBackend.AUTO -> cargoExpandAvailable
            MacroBackend.RUSTC -> false
        }

        if (useCargoExpand) {
            val command = baseCargo(context) +
                listOf("expand", "-p", context.packageName) +
                targetArguments(context) +
                listOf("--profile", context.profile.ifBlank { "dev" }) +
                featureArguments(context.features) +
                targetTripleArguments(context)
            return stdoutPlan(ArtifactKind.MACRO, context, command, warnings, emptyMap())
        }

        val environment = linkedMapOf<String, String>()
        if (!context.isNightlyToolchain) {
            if (options.allowRustcBootstrap) {
                environment["RUSTC_BOOTSTRAP"] = "1"
            } else {
                warnings += "RUSTC_BOOTSTRAP is disabled and the selected toolchain is not nightly; " +
                    "-Zunpretty=expanded will likely fail. Enable it in Settings or select a nightly toolchain."
            }
        }

        val command = baseCargo(context) +
            listOf("rustc", "-p", context.packageName) +
            targetArguments(context) +
            listOf("--profile=check") +
            featureArguments(context.features) +
            targetTripleArguments(context) +
            listOf("--") +
            context.extraRustcArgs +
            listOf("-Zunpretty=expanded")
        return stdoutPlan(ArtifactKind.MACRO, context, command, warnings, environment)
    }

    private fun planMir(context: RustContext): CommandPlan {
        val command = baseCargo(context) +
            listOf("rustc", "-p", context.packageName) +
            targetArguments(context) +
            listOf("--profile", context.profile.ifBlank { "dev" }) +
            featureArguments(context.features) +
            targetTripleArguments(context) +
            listOf("--", "--emit=mir") +
            context.extraRustcArgs
        return artifactPlan(ArtifactKind.MIR, context, command, "mir", emptyMap())
    }

    private fun planAsm(context: RustContext): CommandPlan {
        val cargoAsmAvailable = toolAvailability.isAvailable("cargo-asm", context.toolchain.cargoExecutable)
        val item = context.item
        if (options.preferCargoShowAsm && cargoAsmAvailable && item != null && item.kind in setOf(ItemKind.FN, ItemKind.IMPL_METHOD)) {
            val owner = item.implSelfType?.let { simpleTypeName(it) }
            val filter = buildList {
                add(context.crateName)
                addAll(context.modulePath)
                if (owner != null) add(owner)
                add(item.name)
            }.joinToString("::")
            val command = baseCargo(context) +
                listOf("asm", "-p", context.packageName) +
                targetArguments(context) +
                listOf("--asm", "--simplify", "--intel", filter)
            return CommandPlan(
                kind = ArtifactKind.ASM,
                spec = ProcessSpec(command, context.workspaceRoot, baseEnvironment(context)),
                displayCommand = ShellQuote.withEnvironment(command, baseEnvironment(context)),
                outputMode = OutputMode.STDOUT,
                itemAlreadyExtracted = true,
            )
        }

        val environment = baseEnvironment(context).toMutableMap()
        environment["CARGO_INCREMENTAL"] = "0"
        val command = baseCargo(context) +
            listOf("rustc", "-p", context.packageName) +
            targetArguments(context) +
            listOf("--profile", context.profile.ifBlank { "dev" }) +
            featureArguments(context.features) +
            targetTripleArguments(context) +
            // LTO would split the output into per-CGU `.rcgu.s` files instead of one artifact.
            listOf("--", "-C", "codegen-units=1", "-C", "link-dead-code", "-C", "lto=no", "--emit=asm") +
            context.extraRustcArgs
        return artifactPlan(ArtifactKind.ASM, context, command, "s", environment)
    }

    private fun baseCargo(context: RustContext): List<String> = buildList {
        add(context.toolchain.cargoExecutable)
        context.toolchain.rustupArgument()?.let(::add)
    }

    private fun targetArguments(context: RustContext): List<String> = when (context.targetKind) {
        TargetKind.LIB -> listOf("--lib")
        TargetKind.BIN -> listOf("--bin", context.targetName)
        TargetKind.EXAMPLE -> listOf("--example", context.targetName)
        TargetKind.TEST -> listOf("--test", context.targetName)
        TargetKind.BENCH -> listOf("--bench", context.targetName)
        TargetKind.OTHER -> listOf("--lib")
    }

    private fun targetTripleArguments(context: RustContext): List<String> =
        if (context.targetTriple.isBlank()) emptyList() else listOf("--target", context.targetTriple)

    private fun featureArguments(features: FeaturesSpec): List<String> = when (features.mode) {
        FeaturesMode.DEFAULT -> emptyList()
        FeaturesMode.ALL -> listOf("--all-features")
        FeaturesMode.NO_DEFAULT -> listOf("--no-default-features")
        FeaturesMode.CUSTOM -> {
            if (features.features.isEmpty()) {
                emptyList()
            } else {
                listOf("--features", features.features.joinToString(","))
            }
        }
    }

    private fun baseEnvironment(context: RustContext): Map<String, String> = buildMap {
        put("CARGO_TERM_COLOR", "never")
        if (options.isolatedTargetDir) {
            put("CARGO_TARGET_DIR", context.targetDir.toString())
        }
    }

    private fun stdoutPlan(
        kind: ArtifactKind,
        context: RustContext,
        command: List<String>,
        warnings: List<String>,
        extraEnvironment: Map<String, String>,
    ): CommandPlan {
        val environment = baseEnvironment(context) + extraEnvironment
        return CommandPlan(
            kind = kind,
            spec = ProcessSpec(command, context.workspaceRoot, environment),
            displayCommand = ShellQuote.withEnvironment(command, environment),
            outputMode = OutputMode.STDOUT,
            warnings = warnings,
        )
    }

    private fun artifactPlan(
        kind: ArtifactKind,
        context: RustContext,
        command: List<String>,
        extension: String,
        environment: Map<String, String>,
    ): CommandPlan {
        val mergedEnvironment = baseEnvironment(context) + environment
        return CommandPlan(
            kind = kind,
            spec = ProcessSpec(command, context.workspaceRoot, mergedEnvironment),
            displayCommand = ShellQuote.withEnvironment(command, mergedEnvironment),
            outputMode = OutputMode.ARTIFACT,
            artifactExtension = extension,
            artifactSearchDirs = artifactSearchDirectories(context),
            crateFilePrefix = context.crateName + "-",
            targetSourcePath = context.crateRoot,
            warnings = emptyList(),
        )
    }

    fun artifactSearchDirectories(context: RustContext): List<Path> {
        val profileDirectory = profileDirectory(context.profile)
        val directories = mutableListOf<Path>()
        if (context.targetTriple.isNotBlank()) {
            directories.add(context.targetDir.resolve(context.targetTriple).resolve(profileDirectory).resolve("deps"))
        }
        directories.add(context.targetDir.resolve(profileDirectory).resolve("deps"))
        return directories.distinct()
    }

    companion object {
        fun profileDirectory(profile: String): String = when (profile.trim()) {
            "", "dev", "check", "test" -> "debug"
            "release", "bench" -> "release"
            else -> profile.trim()
        }

        fun simpleTypeName(type: String): String {
            var text = type.trim()
            while (text.startsWith("&")) text = text.removePrefix("&").trim()
            text = text.removePrefix("mut ").trim()
            val generic = text.indexOf('<')
            if (generic >= 0) text = text.substring(0, generic)
            return text.substringAfterLast("::").substringAfterLast(' ').trim()
        }
    }
}
