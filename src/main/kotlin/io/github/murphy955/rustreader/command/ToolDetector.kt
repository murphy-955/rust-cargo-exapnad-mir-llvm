package io.github.murphy955.rustreader.command

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object ShellQuote {
    private val safePattern = Regex("""^[A-Za-z0-9_@%+=:,./-]+$""")

    fun quote(argument: String, windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)): String {
        if (argument.isEmpty()) return if (windows) "\"\"" else "''"
        if (safePattern.matches(argument)) return argument
        return if (windows) {
            "\"" + argument.replace("\"", "\\\"") + "\""
        } else {
            "'" + argument.replace("'", "'\\''") + "'"
        }
    }

    fun commandLine(command: List<String>, windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)): String =
        command.joinToString(" ") { quote(it, windows) }

    fun withEnvironment(
        command: List<String>,
        environment: Map<String, String>,
        windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    ): String {
        val env = environment.entries
            .sortedBy { it.key }
            .joinToString(" ") { "${it.key}=${quote(it.value, windows)}" }
        val cmd = commandLine(command, windows)
        return if (env.isEmpty()) cmd else "$env $cmd"
    }
}

object ToolDetector {
    private val cache = ConcurrentHashMap<String, Boolean>()

    fun isCargoSubcommandAvailable(binary: String, cargoExecutable: String): Boolean {
        val key = "$binary\u0000$cargoExecutable"
        return cache.getOrPut(key) { detect(binary, cargoExecutable) }
    }

    fun clearCache() {
        cache.clear()
    }

    fun findExecutable(name: String): Path? {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val names = if (windows) listOf(name, "$name.exe", "$name.cmd", "$name.bat") else listOf(name)
        val pathDirectories = System.getenv("PATH").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
        for (directory in pathDirectories) {
            for (candidateName in names) {
                val candidate = directory.resolve(candidateName)
                if (isUsable(candidate)) return candidate
            }
        }
        return null
    }

    private fun detect(binary: String, cargoExecutable: String): Boolean {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val suffixes = if (windows) listOf(".exe", ".cmd", ".bat", "") else listOf("")
        val directories = mutableListOf<Path>()

        val cargoPath = Path.of(cargoExecutable)
        if (cargoPath.isAbsolute || cargoExecutable.contains('/') || cargoExecutable.contains('\\')) {
            cargoPath.parent?.let(directories::add)
        }
        System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }?.let { directories.add(Path.of(it).resolve("bin")) }
        System.getenv("PATH").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .mapTo(directories) { Path.of(it) }

        for (directory in directories) {
            for (suffix in suffixes) {
                val candidate = directory.resolve(binary + suffix)
                if (isUsable(candidate)) return true
            }
        }
        return false
    }

    private fun isUsable(path: Path): Boolean = try {
        Files.isRegularFile(path) && (System.getProperty("os.name").startsWith("Windows", ignoreCase = true) || Files.isExecutable(path))
    } catch (_: Exception) {
        false
    }
}
