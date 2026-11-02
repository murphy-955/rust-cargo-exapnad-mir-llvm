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
        if (candidates.isEmpty()) return null

        val withTimes = candidates.mapNotNull { path ->
            try {
                ArtifactHit(path, true, Files.getLastModifiedTime(path).toMillis())
            } catch (_: Exception) {
                null
            }
        }
        if (withTimes.isEmpty()) return null

        val fresh = withTimes.filter { it.modifiedAt >= runStartedAtMillis - FRESH_SLACK_MILLIS }
        return fresh.maxByOrNull { it.modifiedAt }
            ?: withTimes.maxByOrNull { it.modifiedAt }?.copy(fresh = false)
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
