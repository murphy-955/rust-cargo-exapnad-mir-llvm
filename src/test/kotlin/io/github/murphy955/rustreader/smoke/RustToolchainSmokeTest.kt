package io.github.murphy955.rustreader.smoke

import io.github.murphy955.rustreader.command.ArtifactKind
import io.github.murphy955.rustreader.command.ArtifactLocator
import io.github.murphy955.rustreader.command.CommandPlanner
import io.github.murphy955.rustreader.command.PlannerOptions
import io.github.murphy955.rustreader.command.RustProcessRunner
import io.github.murphy955.rustreader.context.ResolveResult
import io.github.murphy955.rustreader.context.ResolverOptions
import io.github.murphy955.rustreader.context.RustContextResolver
import io.github.murphy955.rustreader.extract.AsmExtractor
import io.github.murphy955.rustreader.extract.ExpandExtractor
import io.github.murphy955.rustreader.extract.MirExtractor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opt-in end-to-end test that runs the real cargo toolchain:
 * `./gradlew test -Drustreader.smoke=true`.
 */
class RustToolchainSmokeTest {
    private val sample = Path.of("src/test/testData/sample-crate").toAbsolutePath().normalize()
    private val source = sample.resolve("src/lib.rs")
    private val runner = RustProcessRunner()
    private val resolver = RustContextResolver()

    @Before
    fun requireOptIn() {
        assumeTrue("Set -Drustreader.smoke=true to run", System.getProperty("rustreader.smoke") == "true")
        assertTrue(Files.isRegularFile(sample.resolve("Cargo.toml")))
    }

    @After
    fun cleanup() {
        // @After still runs for tests skipped by an assumption, so do not delete the shared
        // sample target directory during an ordinary unit-test run.
        if (System.getProperty("rustreader.smoke") != "true") return
        listOf(sample.resolve("target"), sample.resolve(".rust-compiler-reader")).forEach { path ->
            if (Files.exists(path)) {
                Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        Files.deleteIfExists(sample.resolve("Cargo.lock"))
    }

    private fun context(token: String) = run {
        val text = Files.readString(source)
        val offset = text.indexOf(token)
        val options = ResolverOptions(isolatedTargetDir = true, timeoutSeconds = 240)
        when (val result = resolver.resolve(sample, source, offset, text, options)) {
            is ResolveResult.Success -> result.context
            is ResolveResult.Failure -> error(result.message + "\n" + result.details)
        }
    }

    @Test
    fun mirEndToEnd() {
        val context = context("pub fn run")
        val plan = CommandPlanner(PlannerOptions(isolatedTargetDir = true)).plan(ArtifactKind.MIR, context)
        val result = runner.run(plan.spec, timeoutSeconds = 240)
        assertTrue(result.stderr, result.succeeded)
        val hit = ArtifactLocator().locate(plan, System.currentTimeMillis())
        requireNotNull(hit) { "No MIR artifact in ${plan.artifactSearchDirs}" }
        val mir = Files.readString(hit.path)
        val extracted = MirExtractor.extract(mir, context.item, includeClosures = true, sourceFileHint = "src/lib.rs")
        assertTrue(extracted.content.contains("fn run("))
        assertTrue(extracted.content.contains("{closure#0}"))
    }

    @Test
    fun asmEndToEnd() {
        val context = context("pub fn run")
        val plan = CommandPlanner(PlannerOptions(isolatedTargetDir = true)).plan(ArtifactKind.ASM, context)
        val result = runner.run(plan.spec, timeoutSeconds = 240)
        assertTrue(result.stderr, result.succeeded)
        val hit = ArtifactLocator().locate(plan, System.currentTimeMillis())
        requireNotNull(hit) { "No ASM artifact in ${plan.artifactSearchDirs}" }
        val asm = Files.readString(hit.path)
        val extracted = AsmExtractor.extract(asm, context.crateName, context.modulePath, context.item)
        assertFalse(extracted.empty)
        assertTrue(extracted.content.contains("3run"))
    }

    @Test
    fun macroExpansionEndToEnd() {
        val context = context("pub fn use_macro")
        val plan = CommandPlanner(PlannerOptions(allowRustcBootstrap = true, isolatedTargetDir = true)).plan(ArtifactKind.MACRO, context)
        val result = runner.run(plan.spec, timeoutSeconds = 240)
        assertTrue(result.stderr, result.succeeded)
        val extracted = ExpandExtractor.extract(result.stdout, context.item, wholeCrate = false)
        assertTrue(extracted.content.contains("pub fn use_macro"))
        assertTrue(extracted.content.contains("v + v"))
    }
}
