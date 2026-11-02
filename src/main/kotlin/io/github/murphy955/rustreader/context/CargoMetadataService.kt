package io.github.murphy955.rustreader.context

import com.google.gson.JsonParser
import io.github.murphy955.rustreader.command.ProcessSpec
import io.github.murphy955.rustreader.command.ProcessResult
import io.github.murphy955.rustreader.command.RustProcessRunner
import java.nio.file.Files
import java.nio.file.Path

data class CargoTarget(
    val name: String,
    val kinds: List<String>,
    val srcPath: Path,
    val edition: String,
) {
    val kind: TargetKind
        get() = TargetKind.fromCargoKinds(kinds)

    fun toTargetRef(): TargetRef = TargetRef(name, kind, srcPath)
}

data class CargoPackage(
    val name: String,
    val manifestPath: Path,
    val edition: String,
    val targets: List<CargoTarget>,
) {
    val directory: Path
        get() = manifestPath.parent
}

data class CargoMetadata(
    val workspaceRoot: Path,
    val packages: List<CargoPackage>,
) {
    fun packageFor(file: Path): CargoPackage? {
        val normalized = file.toAbsolutePath().normalize()
        return packages
            .filter { normalized.startsWith(it.directory.toAbsolutePath().normalize()) }
            .maxByOrNull { it.directory.nameCount }
    }
}

data class TargetChoice(
    val target: CargoTarget,
    val ambiguous: List<CargoTarget> = emptyList(),
) {
    val kind: TargetKind
        get() = target.kind
}

object CargoMetadataParser {
    fun parse(json: String): CargoMetadata {
        val root = JsonParser.parseString(json).asJsonObject
        val workspaceRoot = Path.of(root.get("workspace_root").asString)
        val packages = root.getAsJsonArray("packages").map { element ->
            val packageObject = element.asJsonObject
            val targets = packageObject.getAsJsonArray("targets").map { targetElement ->
                val targetObject = targetElement.asJsonObject
                CargoTarget(
                    name = targetObject.get("name").asString,
                    kinds = targetObject.getAsJsonArray("kind").map { it.asString },
                    srcPath = Path.of(targetObject.get("src_path").asString),
                    edition = targetObject.get("edition")?.asString ?: "2021",
                )
            }
            CargoPackage(
                name = packageObject.get("name").asString,
                manifestPath = Path.of(packageObject.get("manifest_path").asString),
                edition = packageObject.get("edition")?.asString ?: "2021",
                targets = targets,
            )
        }
        return CargoMetadata(workspaceRoot, packages)
    }

    fun resolveTarget(metadata: CargoMetadata, packageName: String, file: Path, overrideKey: String? = null): TargetChoice? {
        val pkg = metadata.packages.firstOrNull { it.name == packageName } ?: return null
        return resolveTarget(pkg, file, overrideKey)
    }

    fun resolveTarget(pkg: CargoPackage, file: Path, overrideKey: String? = null): TargetChoice? {
        val normalized = file.toAbsolutePath().normalize()
        val targets = pkg.targets.filter { it.kind != TargetKind.OTHER || "custom-build" !in it.kinds }

        if (overrideKey != null) {
            val byKey = targets.firstOrNull { it.toTargetRef().key == overrideKey }
                ?: targets.firstOrNull { it.name == overrideKey }
            if (byKey != null) return TargetChoice(byKey)
        }

        targets.firstOrNull { it.srcPath.toAbsolutePath().normalize() == normalized }?.let {
            return TargetChoice(it)
        }

        val directory = pkg.directory.toAbsolutePath().normalize()
        val srcDir = directory.resolve("src")
        if (normalized.startsWith(srcDir)) {
            val binDir = srcDir.resolve("bin")
            if (normalized.startsWith(binDir)) {
                val relative = binDir.relativize(normalized)
                if (relative.nameCount > 1) {
                    val crateName = relative.getName(0).toString()
                    val candidates = targets.filter {
                        it.kind == TargetKind.BIN &&
                            (it.name == crateName || it.srcPath.parent?.fileName?.toString() == crateName)
                    }
                    if (candidates.isNotEmpty()) {
                        return TargetChoice(candidates.first(), candidates.drop(1))
                    }
                }
            } else {
                val libs = targets.filter { it.kind == TargetKind.LIB }
                if (libs.isNotEmpty()) return TargetChoice(libs.first(), libs.drop(1))

                val mainBin = targets.firstOrNull { it.kind == TargetKind.BIN && it.name == pkg.name }
                    ?: targets.firstOrNull { it.kind == TargetKind.BIN && it.srcPath.fileName.toString() == "main.rs" }
                if (mainBin != null) return TargetChoice(mainBin)
            }
        }

        for ((folder, targetKind) in listOf("examples" to TargetKind.EXAMPLE, "tests" to TargetKind.TEST, "benches" to TargetKind.BENCH)) {
            val root = directory.resolve(folder)
            if (normalized.startsWith(root)) {
                val relative = root.relativize(normalized)
                val stem = relative.getName(0).toString().removeSuffix(".rs")
                val candidates = targets.filter { it.kind == targetKind && it.name == stem }
                if (candidates.isNotEmpty()) return TargetChoice(candidates.first(), candidates.drop(1))
            }
        }

        // Files outside src/examples/tests/benches can still be modules of a target.
        val ancestors = generateSequence(normalized.parent) { it.parent }.toList()
        val byRoot = targets
            .filter { target ->
                val root = target.srcPath.toAbsolutePath().normalize().parent ?: return@filter false
                normalized.startsWith(root) && root in ancestors.plus(normalized.parent)
            }
            .sortedByDescending { it.srcPath.nameCount }
        if (byRoot.isNotEmpty()) return TargetChoice(byRoot.first(), byRoot.drop(1))

        return null
    }
}

data class MetadataResult(
    val metadata: CargoMetadata?,
    val error: String? = null,
    val command: List<String> = emptyList(),
)

class CargoMetadataService(
    private val runner: RustProcessRunner = RustProcessRunner(),
) {
    private var cachedKey: String? = null
    private var cachedResult: MetadataResult? = null

    fun load(
        cargoExecutable: String,
        toolchain: ToolchainSpec,
        manifestPath: Path,
        timeoutSeconds: Int = 60,
    ): MetadataResult {
        val manifest = manifestPath.toAbsolutePath().normalize()
        val command = buildCommand(cargoExecutable, toolchain, manifest)
        val key = buildKey(manifest, command)
        synchronized(this) {
            val cached = cachedResult
            if (cached != null && cachedKey == key) return cached
            val result = runMetadata(command, manifest.parent, timeoutSeconds)
            cachedKey = key
            cachedResult = result
            return result
        }
    }

    fun invalidate() {
        synchronized(this) {
            cachedKey = null
            cachedResult = null
        }
    }

    private fun runMetadata(command: List<String>, workingDirectory: Path, timeoutSeconds: Int): MetadataResult {
        val result = try {
            runner.run(
                ProcessSpec(command, workingDirectory, mapOf("CARGO_TERM_COLOR" to "never")),
                timeoutSeconds,
            )
        } catch (exception: Exception) {
            return MetadataResult(null, "Failed to run cargo metadata: ${exception.message}", command)
        }
        if (!result.succeeded) {
            val details = result.stderr.ifBlank { result.stdout }
            return MetadataResult(
                metadata = null,
                error = "cargo metadata failed (exit ${result.exitCode}).\n$details",
                command = command,
            )
        }
        return try {
            MetadataResult(CargoMetadataParser.parse(result.stdout), command = command)
        } catch (exception: Exception) {
            MetadataResult(null, "Failed to parse cargo metadata: ${exception.message}", command)
        }
    }

    private fun buildCommand(cargoExecutable: String, toolchain: ToolchainSpec, manifest: Path): List<String> {
        val command = mutableListOf(cargoExecutable)
        toolchain.rustupArgument()?.let(command::add)
        command += listOf(
            "metadata",
            "--no-deps",
            "--format-version",
            "1",
            "--manifest-path",
            manifest.toString(),
        )
        return command
    }

    private fun buildKey(manifest: Path, command: List<String>): String {
        val mtime = try {
            Files.getLastModifiedTime(manifest).toMillis()
        } catch (_: Exception) {
            0L
        }
        val workspaceManifest = manifest.parent?.resolve("Cargo.toml")
        val workspaceMtime = try {
            workspaceManifest?.takeIf { Files.exists(it) }?.let { Files.getLastModifiedTime(it).toMillis() } ?: 0L
        } catch (_: Exception) {
            0L
        }
        return "${command.joinToString("\u0000")}|$mtime|$workspaceMtime"
    }
}
