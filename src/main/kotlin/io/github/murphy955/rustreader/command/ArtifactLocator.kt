package io.github.murphy955.rustreader.command

import java.nio.file.Files
import java.nio.file.Path

data class ArtifactHit(
    val path: Path,
    val fresh: Boolean,
    val modifiedAt: Long,
)

class ArtifactLocator {
    fun locate(plan: CommandPlan, runStartedAtMillis: Long): ArtifactHit? {
        val extension = plan.artifactExtension ?: return null
        val candidates = plan.artifactSearchDirs
            .filter { Files.isDirectory(it) }
            .flatMap { directory -> listCandidates(directory, plan.crateFilePrefix, extension) }
        val matching = candidatesForTarget(candidates, plan.targetSourcePath, plan.spec.workingDirectory)
        if (matching.isEmpty()) return null

        val withTimes = matching.mapNotNull { path ->
            try {
                val modifiedAt = Files.getLastModifiedTime(path).toMillis()
                // Cargo skips recompiling an up-to-date target, so the artifact mtime may predate
                // this run even though it is current. Prefer the dependency list over the clock.
                val fresh = isUpToDate(path, plan.spec.workingDirectory)
                    ?: (modifiedAt >= runStartedAtMillis - FRESH_SLACK_MILLIS)
                ArtifactHit(path, fresh, modifiedAt)
            } catch (_: Exception) {
                null
            }
        }
        if (withTimes.isEmpty()) return null

        return withTimes.filter { it.fresh }.maxByOrNull { it.modifiedAt }
            ?: withTimes.maxByOrNull { it.modifiedAt }?.copy(fresh = false)
    }

    /**
     * Keeps only artifacts whose dependency file mentions the planned target source. Cargo
     * names lib and bin artifacts of the same package `<crate>-<hash>.<ext>`, so the shared
     * prefix alone can point at another target's output.
     */
    private fun candidatesForTarget(candidates: List<Path>, targetSource: Path?, workingDirectory: Path): List<Path> {
        if (targetSource == null) return candidates
        val absolute = targetSource.toAbsolutePath().normalize()
        val relative = try {
            workingDirectory.toAbsolutePath().normalize().relativize(absolute)
        } catch (_: Exception) {
            null
        }
        val needles = listOfNotNull(absolute.toString(), relative?.toString())
            .map { it.replace('\\', '/') }
            .filter { it.isNotEmpty() }

        val matching = mutableListOf<Path>()
        val unknown = mutableListOf<Path>()
        for (candidate in candidates) {
            val dependencies = readDependencies(candidate)
            if (dependencies == null) {
                unknown.add(candidate)
                continue
            }
            if (needles.any { mentionsFile(dependencies, it) }) matching.add(candidate)
        }
        return matching + unknown
    }

    private fun readSidecar(artifact: Path): String? {
        val name = artifact.fileName?.toString() ?: return null
        val stem = name.substringBeforeLast('.', name)
        val sidecar = artifact.resolveSibling("$stem.d")
        if (!Files.isRegularFile(sidecar)) return null
        return try {
            Files.readString(sidecar)
        } catch (_: Exception) {
            null
        }
    }

    private fun readDependencies(artifact: Path): String? =
        readSidecar(artifact)?.replace("\\ ", " ")?.replace('\\', '/')

    /**
     * Whether the artifact is newer than every source file listed in its `.d` file. Returns null
     * when the dependency information is unavailable.
     */
    private fun isUpToDate(artifact: Path, workingDirectory: Path): Boolean? {
        val raw = readSidecar(artifact) ?: return null
        val artifactTime = try {
            Files.getLastModifiedTime(artifact).toMillis()
        } catch (_: Exception) {
            return null
        }
        val base = workingDirectory.toAbsolutePath().normalize()
        val newestDependency = raw
            // Keep escaped spaces while splitting, and drop make-style line continuations.
            .replace("\\\r\n", "").replace("\\\n", "").replace("\\\r", "")
            .replace("\\ ", "\u0000")
            .lineSequence()
            .flatMap { line ->
                val separator = line.indexOf(": ")
                if (separator < 0) {
                    emptySequence()
                } else {
                    line.substring(separator + 2).split(' ').asSequence()
                }
            }
            .mapNotNull { token ->
                val cleaned = token.replace("\u0000", " ").trim()
                if (cleaned.isEmpty()) return@mapNotNull null
                val path = try {
                    Path.of(cleaned)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                try {
                    Files.getLastModifiedTime(if (path.isAbsolute) path else base.resolve(path)).toMillis()
                } catch (_: Exception) {
                    null
                }
            }
            .maxOrNull() ?: return null
        return artifactTime >= newestDependency
    }

    private fun mentionsFile(dependencies: String, path: String): Boolean {
        var index = dependencies.indexOf(path)
        while (index >= 0) {
            val end = index + path.length
            if (end >= dependencies.length || dependencies[end].isWhitespace()) return true
            index = dependencies.indexOf(path, index + 1)
        }
        return false
    }

    fun listCandidates(directory: Path, prefix: String, extension: String): List<Path> = try {
        Files.list(directory).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter {
                    val name = it.fileName.toString()
                    name.startsWith(prefix) && name.endsWith(".$extension")
                }
                .toList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val FRESH_SLACK_MILLIS = 3_000L
    }
}
