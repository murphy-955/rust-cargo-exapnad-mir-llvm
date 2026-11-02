package io.github.murphy955.rustreader.command

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.ProgressIndicator
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ProcessSpec(
    val command: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String> = emptyMap(),
)

data class ProcessResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val canceled: Boolean,
    val truncated: Boolean,
    val durationMillis: Long,
) {
    val succeeded: Boolean
        get() = !timedOut && !canceled && exitCode == 0

    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append(stderr)
            }
        }
}

/**
 * Runs external commands without a shell. Output is collected concurrently from stdout and
 * stderr, capped, and the process tree is destroyed on cancellation or timeout.
 */
class RustProcessRunner {
    fun run(
        spec: ProcessSpec,
        timeoutSeconds: Int,
        indicator: ProgressIndicator? = null,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
        input: String? = null,
    ): ProcessResult {
        val startedAt = System.nanoTime()
        val commandLine = GeneralCommandLine(spec.command)
            .withWorkDirectory(spec.workingDirectory.toString())
            .withCharset(StandardCharsets.UTF_8)
        commandLine.environment.putAll(spec.environment)

        val process = commandLine.createProcess()
        val stdout = StreamCollector(process.inputStream, maxOutputChars)
        val stderr = StreamCollector(process.errorStream, maxOutputChars)
        stdout.start()
        stderr.start()

        if (input != null) {
            Thread {
                try {
                    process.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
                } catch (_: Exception) {
                    // The process may exit before reading stdin.
                }
            }.apply {
                isDaemon = true
                start()
            }
        } else {
            try {
                process.outputStream.close()
            } catch (_: Exception) {
                // Ignore broken pipes.
            }
        }

        var timedOut = false
        var canceled = false
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
            if (indicator?.isCanceled == true || Thread.currentThread().isInterrupted) {
                canceled = true
                destroyProcessTree(process)
                break
            }
            val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000L
            if (elapsedSeconds >= timeoutSeconds) {
                timedOut = true
                destroyProcessTree(process)
                break
            }
        }

        process.waitFor(5, TimeUnit.SECONDS)
        stdout.join(5_000)
        stderr.join(5_000)

        val exitCode = try {
            process.exitValue()
        } catch (_: IllegalThreadStateException) {
            -1
        }

        return ProcessResult(
            command = spec.command,
            exitCode = exitCode,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = timedOut,
            canceled = canceled,
            truncated = stdout.wasTruncated() || stderr.wasTruncated(),
            durationMillis = (System.nanoTime() - startedAt) / 1_000_000L,
        )
    }

    private fun destroyProcessTree(process: Process) {
        try {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
        } catch (_: Exception) {
            // Ignore races while the process is exiting.
        }
        process.destroyForcibly()
    }

    private class StreamCollector(
        private val stream: InputStream,
        private val limit: Int,
    ) : Thread() {
        private val builder = StringBuilder()
        private var truncated = false

        init {
            isDaemon = true
            name = "rust-compiler-reader-output"
        }

        override fun run() {
            try {
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        val remaining = limit - builder.length
                        if (remaining <= 0) {
                            truncated = true
                            continue
                        }
                        val toAppend = minOf(read, remaining)
                        builder.append(buffer, 0, toAppend)
                        if (toAppend < read) truncated = true
                    }
                }
            } catch (_: Exception) {
                // Stream closed while the process was killed.
            }
        }

        fun text(): String = builder.toString()

        fun wasTruncated(): Boolean = truncated
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_CHARS: Int = 50 * 1024 * 1024
    }
}
