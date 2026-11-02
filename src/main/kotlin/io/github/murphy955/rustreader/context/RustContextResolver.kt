package io.github.murphy955.rustreader.context

import java.nio.file.Files
import java.nio.file.Path

data class ResolverOptions(
    val cargoExecutable: String = "cargo",
    val toolchain: ToolchainSpec = ToolchainSpec(),
    val profile: String = "dev",
    val targetTriple: String = "",
    val features: FeaturesSpec = FeaturesSpec(),
    val extraRustcArgs: List<String> = emptyList(),
    val isolatedTargetDir: Boolean = false,
    val timeoutSeconds: Int = 120,
    val targetOverride: String? = null,
    val targetOverrides: Map<String, String> = emptyMap(),
)

sealed class ResolveResult {
    data class Success(val context: RustContext, val warnings: List<String> = emptyList()) : ResolveResult()
    data class Failure(val message: String, val details: String = "") : ResolveResult()
}

class RustContextResolver(
    private val metadataService: CargoMetadataService = CargoMetadataService(),
    private val itemLocator: (String, Int, List<String>) -> ItemRef? = { text, caret, modules ->
        ItemLocator.locate(text, caret, modules)
    },
) {
    fun metadataService(): CargoMetadataService = metadataService

    fun resolve(
        projectPath: Path,
        file: Path,
        caretOffset: Int,
        text: String,
        options: ResolverOptions,
    ): ResolveResult {
        val normalizedFile = file.toAbsolutePath().normalize()
        val manifest = findManifest(normalizedFile)
            ?: return ResolveResult.Failure(
                "No Cargo.toml found for ${normalizedFile.fileName}.",
                "Open a file that belongs to a Cargo package or workspace.",
            )

        val metadataResult = metadataService.load(
            cargoExecutable = options.cargoExecutable,
            toolchain = options.toolchain,
            manifestPath = manifest,
            timeoutSeconds = minOf(options.timeoutSeconds, 60),
        )
        val metadata = metadataResult.metadata
            ?: return ResolveResult.Failure(
                metadataResult.error ?: "cargo metadata failed.",
                metadataResult.command.joinToString(" "),
            )

        val pkg = metadata.packageFor(normalizedFile)
            ?: metadata.packages.singleOrNull()
            ?: return ResolveResult.Failure(
                "Cannot determine the Cargo package for ${normalizedFile.fileName}.",
                "Packages: ${metadata.packages.joinToString { it.name }}",
            )

        val targetChoice = CargoMetadataParser.resolveTarget(
            pkg,
            normalizedFile,
            options.targetOverride ?: options.targetOverrides[pkg.name],
        )
            ?: return ResolveResult.Failure(
                "Cannot determine a Cargo target for ${normalizedFile.fileName}.",
                "Targets: ${pkg.targets.joinToString { "${it.name} (${it.kinds.joinToString("/")})" }}",
            )

        val target = targetChoice.target
        val modulePath = modulePathFor(normalizedFile, target.srcPath)
        val item = itemLocator(text, caretOffset, modulePath)
        val warnings = mutableListOf<String>()
        if (item == null) {
            warnings += "No enclosing Rust item was found at the caret; whole target output will be shown."
        }
        if (targetChoice.ambiguous.isNotEmpty()) {
            warnings += "Multiple targets could own this file; using ${target.name}. You can pick another target in the toolbar."
        }

        val nightly = options.toolchain.isNightly || toolchainChannel(normalizedFile)?.contains("nightly") == true
        val targetDir = if (options.isolatedTargetDir) {
            metadata.workspaceRoot.resolve(".rust-compiler-reader").resolve("target")
        } else {
            metadata.workspaceRoot.resolve("target")
        }

        val context = RustContext(
            projectPath = metadata.workspaceRoot,
            file = normalizedFile,
            caretOffset = caretOffset,
            crateRoot = target.srcPath,
            modulePath = modulePath,
            packageName = pkg.name,
            packageManifest = pkg.manifestPath,
            targetName = target.name,
            targetKind = target.kind,
            targetSrcPath = target.srcPath,
            edition = target.edition.ifBlank { pkg.edition },
            profile = options.profile,
            targetTriple = options.targetTriple,
            toolchain = options.toolchain,
            features = options.features,
            extraRustcArgs = options.extraRustcArgs,
            targetDir = targetDir,
            workspaceRoot = metadata.workspaceRoot,
            item = item,
            isNightlyToolchain = nightly,
            availableTargets = pkg.targets
                .filter { "custom-build" !in it.kinds }
                .map { it.toTargetRef() },
        )
        return ResolveResult.Success(context, warnings)
    }

    fun modulePathFor(file: Path, crateRoot: Path): List<String> {
        val normalizedFile = file.toAbsolutePath().normalize()
        val normalizedRoot = crateRoot.toAbsolutePath().normalize()
        if (normalizedFile == normalizedRoot) return emptyList()
        val rootParent = normalizedRoot.parent ?: return emptyList()
        val fileName = normalizedRoot.fileName?.toString().orEmpty()
        val rootStem = fileName.removeSuffix(".rs")
        val base = if (fileName in setOf("lib.rs", "main.rs", "mod.rs")) {
            rootParent
        } else {
            val moduleDirectory = rootParent.resolve(rootStem)
            if (normalizedFile.startsWith(moduleDirectory)) moduleDirectory else rootParent
        }
        if (!normalizedFile.startsWith(base)) return emptyList()

        val parts = mutableListOf<String>()
        base.relativize(normalizedFile).forEach { parts += it.toString() }
        if (parts.isEmpty()) return emptyList()
        if (parts.last() == "mod.rs") {
            parts.removeAt(parts.lastIndex)
        } else {
            parts[parts.lastIndex] = parts.last().removeSuffix(".rs")
        }
        return parts.filter { it.isNotEmpty() }
    }

    fun findManifest(file: Path): Path? {
        var directory: Path? = if (Files.isDirectory(file)) file else file.parent
        while (directory != null) {
            val candidate = directory.resolve("Cargo.toml")
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize()
            directory = directory.parent
        }
        return null
    }

    private fun toolchainChannel(file: Path): String? {
        var directory: Path? = file.parent
        while (directory != null) {
            val toml = directory.resolve("rust-toolchain.toml")
            if (Files.isRegularFile(toml)) {
                return try {
                    val content = Files.readString(toml)
                    Regex("""channel\s*=\s*["']([^"']+)["']""").find(content)?.groupValues?.get(1)
                } catch (_: Exception) {
                    null
                }
            }
            val plain = directory.resolve("rust-toolchain")
            if (Files.isRegularFile(plain)) {
                return try {
                    Files.readString(plain).trim().lineSequence().firstOrNull()?.trim()
                } catch (_: Exception) {
                    null
                }
            }
            directory = directory.parent
        }
        return null
    }
}
