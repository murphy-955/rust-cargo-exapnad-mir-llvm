package io.github.murphy955.rustreader.command

import io.github.murphy955.rustreader.context.FeaturesMode
import io.github.murphy955.rustreader.context.FeaturesSpec
import io.github.murphy955.rustreader.context.ItemKind
import io.github.murphy955.rustreader.context.ItemRef
import io.github.murphy955.rustreader.context.RustContext
import io.github.murphy955.rustreader.context.TargetKind
import io.github.murphy955.rustreader.context.ToolchainKind
import io.github.murphy955.rustreader.context.ToolchainSpec
import io.github.murphy955.rustreader.context.spanOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class CommandPlannerTest {
    private val workspace = Path.of("/ws")
    private val targetDir = workspace.resolve("target")

    private fun context(
        kind: TargetKind = TargetKind.LIB,
        targetName: String = "demo",
        profile: String = "dev",
        triple: String = "",
        features: FeaturesSpec = FeaturesSpec(),
        toolchain: ToolchainSpec = ToolchainSpec(),
        item: ItemRef? = ItemRef(
            kind = ItemKind.FN,
            name = "run",
            modulePath = listOf("nested"),
            sourceSpan = spanOf("fn run() {}", 0, 1),
        ),
        extraArgs: List<String> = emptyList(),
    ): RustContext = RustContext(
        projectPath = workspace,
        file = workspace.resolve("src/lib.rs"),
        caretOffset = 0,
        crateRoot = workspace.resolve("src/lib.rs"),
        modulePath = listOf("nested"),
        packageName = "demo",
        packageManifest = workspace.resolve("Cargo.toml"),
        targetName = targetName,
        targetKind = kind,
        targetSrcPath = workspace.resolve("src/lib.rs"),
        edition = "2021",
        profile = profile,
        targetTriple = triple,
        toolchain = toolchain,
        features = features,
        extraRustcArgs = extraArgs,
        targetDir = targetDir,
        workspaceRoot = workspace,
        item = item,
    )

    private fun planner(
        options: PlannerOptions = PlannerOptions(),
        available: Set<String> = emptySet(),
    ): CommandPlanner = CommandPlanner(options, ToolAvailability { binary, _ -> binary in available })

    @Test
    fun usesCargoExpandWhenAvailable() {
        val plan = planner(available = setOf("cargo-expand")).plan(ArtifactKind.MACRO, context())
        assertTrue(plan.spec.command.contains("expand"))
        assertFalse(plan.spec.command.contains("rustc"))
        assertFalse(plan.spec.environment.containsKey("RUSTC_BOOTSTRAP"))
        assertEquals(OutputMode.STDOUT, plan.outputMode)
    }

    @Test
    fun fallsBackToRustcExpansionWithBootstrap() {
        val plan = planner().plan(ArtifactKind.MACRO, context())
        assertTrue(plan.spec.command.contains("rustc"))
        assertTrue(plan.spec.command.contains("--profile=check"))
        assertTrue(plan.spec.command.contains("-Zunpretty=expanded"))
        assertEquals("1", plan.spec.environment["RUSTC_BOOTSTRAP"])
    }

    @Test
    fun warnsWhenBootstrapIsDisabled() {
        val plan = planner(PlannerOptions(allowRustcBootstrap = false)).plan(ArtifactKind.MACRO, context())
        assertTrue(plan.warnings.any { it.contains("RUSTC_BOOTSTRAP") })
        assertFalse(plan.spec.environment.containsKey("RUSTC_BOOTSTRAP"))
    }

    @Test
    fun nightlyToolchainUsesRustupArgumentAndNoBootstrap() {
        val plan = planner().plan(
            ArtifactKind.MACRO,
            context(toolchain = ToolchainSpec(ToolchainKind.NIGHTLY), item = null).copy(isNightlyToolchain = true),
        )
        assertEquals("+nightly", plan.spec.command[1])
        assertFalse(plan.spec.environment.containsKey("RUSTC_BOOTSTRAP"))
    }

    @Test
    fun mirPlanUsesEmitMirAndArtifactDirectories() {
        val plan = planner().plan(ArtifactKind.MIR, context())
        assertTrue(plan.spec.command.contains("--emit=mir"))
        assertEquals(OutputMode.ARTIFACT, plan.outputMode)
        assertEquals("mir", plan.artifactExtension)
        assertEquals(listOf(targetDir.resolve("debug/deps")), plan.artifactSearchDirs)
        assertEquals("demo-", plan.crateFilePrefix)
    }

    @Test
    fun customProfileIsUsedInCommandAndArtifactPath() {
        val plan = planner().plan(ArtifactKind.ASM, context(profile = "maxopt"))
        assertTrue(plan.spec.command.windowed(2).contains(listOf("--profile", "maxopt")))
        assertEquals(listOf(targetDir.resolve("maxopt/deps")), plan.artifactSearchDirs)
    }

    @Test
    fun targetTripleAddsSearchDirectory() {
        val plan = planner().plan(ArtifactKind.MIR, context(profile = "release", triple = "x86_64-unknown-linux-gnu"))
        assertEquals(
            listOf(
                targetDir.resolve("x86_64-unknown-linux-gnu/release/deps"),
                targetDir.resolve("release/deps"),
            ),
            plan.artifactSearchDirs,
        )
        assertTrue(plan.spec.command.windowed(2).contains(listOf("--target", "x86_64-unknown-linux-gnu")))
    }

    @Test
    fun asmPlanDisablesIncrementalAndUsesOneCodegenUnit() {
        val plan = planner().plan(ArtifactKind.ASM, context())
        assertEquals("0", plan.spec.environment["CARGO_INCREMENTAL"])
        assertTrue(plan.spec.command.contains("codegen-units=1"))
        assertTrue(plan.spec.command.contains("link-dead-code"))
        assertTrue(plan.spec.command.contains("lto=no"))
        assertTrue(plan.spec.command.contains("--emit=asm"))
        assertEquals("s", plan.artifactExtension)
    }

    @Test
    fun featuresArePassedThrough() {
        val all = planner().plan(ArtifactKind.MIR, context(features = FeaturesSpec(FeaturesMode.ALL)))
        assertTrue(all.spec.command.contains("--all-features"))

        val none = planner().plan(ArtifactKind.MIR, context(features = FeaturesSpec(FeaturesMode.NO_DEFAULT)))
        assertTrue(none.spec.command.contains("--no-default-features"))

        val custom = planner().plan(
            ArtifactKind.MIR,
            context(features = FeaturesSpec(FeaturesMode.CUSTOM, listOf("a", "b"))),
        )
        assertTrue(custom.spec.command.windowed(2).contains(listOf("--features", "a,b")))
    }

    @Test
    fun targetKindSelectsCargoFlags() {
        val bin = planner().plan(ArtifactKind.MIR, context(kind = TargetKind.BIN, targetName = "app"))
        assertTrue(bin.spec.command.windowed(2).contains(listOf("--bin", "app")))

        val example = planner().plan(ArtifactKind.MIR, context(kind = TargetKind.EXAMPLE, targetName = "demo"))
        assertTrue(example.spec.command.windowed(2).contains(listOf("--example", "demo")))
    }

    @Test
    fun extraRustcArgumentsAreAppendedAfterDoubleDash() {
        val plan = planner().plan(ArtifactKind.MIR, context(extraArgs = listOf("-C", "target-cpu=native")))
        val dashIndex = plan.spec.command.indexOf("--")
        assertTrue(dashIndex > 0)
        assertTrue(plan.spec.command.drop(dashIndex).contains("target-cpu=native"))
    }

    @Test
    fun cargoShowAsmBackendProducesStdoutPlan() {
        val options = PlannerOptions(preferCargoShowAsm = true)
        val plan = planner(options, available = setOf("cargo-asm")).plan(ArtifactKind.ASM, context())
        assertTrue(plan.spec.command.contains("asm"))
        assertTrue(plan.spec.command.contains("--intel"))
        assertEquals(OutputMode.STDOUT, plan.outputMode)
        assertTrue(plan.itemAlreadyExtracted)
        assertTrue(plan.spec.command.any { it.contains("nested::run") })
    }

    @Test
    fun isolatedTargetDirectoryIsExported() {
        val plan = planner(PlannerOptions(isolatedTargetDir = true)).plan(ArtifactKind.MIR, context())
        assertEquals(targetDir.toString(), plan.spec.environment["CARGO_TARGET_DIR"])
    }

    @Test
    fun quotesCommandsForDisplay() {
        assertEquals("cargo build", ShellQuote.commandLine(listOf("cargo", "build"), windows = false))
        assertEquals("'a b'", ShellQuote.quote("a b", windows = false))
        assertEquals("'it'\\''s'", ShellQuote.quote("it's", windows = false))
        assertEquals("\"a b\"", ShellQuote.quote("a b", windows = true))
    }
}
