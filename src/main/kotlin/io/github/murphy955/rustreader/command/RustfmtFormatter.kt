package io.github.murphy955.rustreader.command

import java.nio.file.Path

/**
 * Optional formatting pass for macro expansion output. Failures are silent and the raw text is
 * returned because rustfmt cannot format every partial item.
 */
class RustfmtFormatter(
    private val runner: RustProcessRunner = RustProcessRunner(),
) {
    fun format(
        text: String,
        rustfmtExecutable: String,
        edition: String,
        workingDirectory: Path,
    ): String {
        if (text.isBlank()) return text
        val safeEdition = edition.takeIf { it in setOf("2015", "2018", "2021", "2024") } ?: "2021"
        return try {
            val result = runner.run(
                ProcessSpec(
                    command = listOf(rustfmtExecutable, "--edition", safeEdition, "--emit", "stdout", "--quiet"),
                    workingDirectory = workingDirectory,
                    environment = mapOf("RUSTFMT" to rustfmtExecutable),
                ),
                timeoutSeconds = 20,
                input = text,
            )
            if (result.succeeded && result.stdout.isNotBlank()) result.stdout else text
        } catch (_: Exception) {
            text
        }
    }
}
