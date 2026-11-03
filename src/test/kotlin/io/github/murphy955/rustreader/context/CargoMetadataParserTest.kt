package io.github.murphy955.rustreader.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class CargoMetadataParserTest {
    private val json = """
        {
          "workspace_root": "/ws",
          "packages": [
            {
              "name": "app",
              "edition": "2021",
              "manifest_path": "/ws/app/Cargo.toml",
              "targets": [
                {"name": "app", "kind": ["lib"], "src_path": "/ws/app/src/lib.rs", "edition": "2021"},
                {"name": "app", "kind": ["bin"], "src_path": "/ws/app/src/main.rs", "edition": "2021"},
                {"name": "extra", "kind": ["bin"], "src_path": "/ws/app/src/bin/extra.rs", "edition": "2021"},
                {"name": "demo", "kind": ["example"], "src_path": "/ws/app/examples/demo.rs", "edition": "2021"},
                {"name": "basic", "kind": ["test"], "src_path": "/ws/app/tests/basic.rs", "edition": "2021"},
                {"name": "build-script-build", "kind": ["custom-build"], "src_path": "/ws/app/build.rs", "edition": "2021"}
              ]
            },
            {
              "name": "util",
              "edition": "2021",
              "manifest_path": "/ws/util/Cargo.toml",
              "targets": [
                {"name": "util", "kind": ["lib"], "src_path": "/ws/util/src/lib.rs", "edition": "2021"}
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesPackagesAndTargets() {
        val metadata = CargoMetadataParser.parse(json)
        assertEquals(Path.of("/ws"), metadata.workspaceRoot)
        assertEquals(2, metadata.packages.size)
        assertEquals(6, metadata.packages.first().targets.size)
    }

    @Test
    fun picksLongestPackagePrefix() {
        val metadata = CargoMetadataParser.parse(json)
        val pkg = metadata.packageFor(Path.of("/ws/util/src/foo.rs"))
        assertNotNull(pkg)
        assertEquals("util", pkg!!.name)
        assertNull(metadata.packageFor(Path.of("/other/src/foo.rs")))
    }

    @Test
    fun resolvesLibForModuleFile() {
        val metadata = CargoMetadataParser.parse(json)
        val choice = CargoMetadataParser.resolveTarget(metadata, "app", Path.of("/ws/app/src/foo.rs"))
        assertNotNull(choice)
        assertEquals(TargetKind.LIB, choice!!.kind)
    }

    @Test
    fun resolvesExactBinAndExampleTargets() {
        val metadata = CargoMetadataParser.parse(json)
        val bin = CargoMetadataParser.resolveTarget(metadata, "app", Path.of("/ws/app/src/bin/extra.rs"))
        assertEquals(TargetKind.BIN, bin!!.kind)
        assertEquals("extra", bin.target.name)

        val example = CargoMetadataParser.resolveTarget(metadata, "app", Path.of("/ws/app/examples/demo.rs"))
        assertEquals(TargetKind.EXAMPLE, example!!.kind)
        assertEquals("demo", example.target.name)

        val test = CargoMetadataParser.resolveTarget(metadata, "app", Path.of("/ws/app/tests/basic.rs"))
        assertEquals(TargetKind.TEST, test!!.kind)
    }

    @Test
    fun resolvesModuleOfBinaryDirectory() {
        val metadata = CargoMetadataParser.parse(json)
        val choice = CargoMetadataParser.resolveTarget(metadata, "app", Path.of("/ws/app/src/bin/extra/helper.rs"))
        assertNotNull(choice)
        assertEquals("extra", choice!!.target.name)
    }

    @Test
    fun honoursTargetOverride() {
        val metadata = CargoMetadataParser.parse(json)
        val choice = CargoMetadataParser.resolveTarget(metadata, "app", Path.of("/ws/app/src/foo.rs"), "bin:extra")
        assertNotNull(choice)
        assertEquals("extra", choice!!.target.name)
    }

    @Test
    fun exactSourceRootWinsOverStaleOverride() {
        val metadata = CargoMetadataParser.parse(json)
        val choice = CargoMetadataParser.resolveTarget(metadata, "app", Path.of("/ws/app/src/main.rs"), "lib:app")
        assertNotNull(choice)
        assertEquals(TargetKind.BIN, choice!!.kind)
        assertEquals("main.rs", choice.target.srcPath.fileName.toString())
    }

    @Test
    fun binaryModuleWinsOverStaleOverride() {
        val metadata = CargoMetadataParser.parse(json)
        val choice = CargoMetadataParser.resolveTarget(
            metadata,
            "app",
            Path.of("/ws/app/src/bin/extra/helper.rs"),
            "lib:app",
        )
        assertNotNull(choice)
        assertEquals("extra", choice!!.target.name)
    }

    @Test
    fun exampleModuleWinsOverStaleOverride() {
        val metadata = CargoMetadataParser.parse(json)
        val choice = CargoMetadataParser.resolveTarget(
            metadata,
            "app",
            Path.of("/ws/app/examples/demo/helper.rs"),
            "lib:app",
        )
        assertNotNull(choice)
        assertEquals(TargetKind.EXAMPLE, choice!!.kind)
        assertEquals("demo", choice.target.name)
    }

    @Test
    fun ignoresCustomBuildTargets() {
        val metadata = CargoMetadataParser.parse(json)
        assertNull(CargoMetadataParser.resolveTarget(metadata, "app", Path.of("/ws/app/build.rs")))
    }
}
