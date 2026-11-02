package io.github.murphy955.rustreader.context

import java.nio.file.Files
import java.nio.file.Path

object CargoProfileDiscovery {
    private val profileSection = Regex("""^\s*\[profile\.([A-Za-z0-9_-]+)]\s*(?:#.*)?$""")

    val BUILT_IN: List<String> = listOf("dev", "release", "test", "bench", "check")

    fun names(manifestText: String): List<String> {
        val custom = manifestText.lineSequence()
            .mapNotNull { line -> profileSection.find(line)?.groupValues?.get(1) }
            .toList()
        return (BUILT_IN + custom).distinct()
    }

    fun namesFor(context: RustContext): List<String> {
        val manifests = listOf(context.packageManifest, context.workspaceRoot.resolve("Cargo.toml"))
            .map { it.toAbsolutePath().normalize() }
            .distinct()
        return names(manifests.joinToString("\n") { manifestText(it) })
    }

    private fun manifestText(path: Path): String = try {
        if (Files.isRegularFile(path)) Files.readString(path) else ""
    } catch (_: Exception) {
        ""
    }
}
