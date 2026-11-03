package io.github.murphy955.rustreader.command

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class ArtifactLocatorTest {
    private val directory = Files.createTempDirectory("rust-reader-artifacts")

    @After
    fun cleanup() {
        Files.walk(directory).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun plan(searchDirs: List<Path>, targetSource: Path? = null) = CommandPlan(
        kind = ArtifactKind.MIR,
        spec = ProcessSpec(listOf("cargo"), directory),
        displayCommand = "cargo",
        outputMode = OutputMode.ARTIFACT,
        artifactExtension = "mir",
        artifactSearchDirs = searchDirs,
        crateFilePrefix = "demo-",
        targetSourcePath = targetSource,
    )

    @Test
    fun picksNewestFreshArtifact() {
        val old = directory.resolve("demo-111.mir")
        val fresh = directory.resolve("demo-222.mir")
        Files.writeString(old, "old")
        Files.writeString(fresh, "fresh")
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(5_000))
        Files.writeString(directory.resolve("demo-333.txt"), "ignore")
        Files.writeString(directory.resolve("other.mir"), "ignore")

        val hit = ArtifactLocator().locate(plan(listOf(directory)), runStartedAtMillis = 5_500)
        assertEquals(fresh, hit?.path)
        assertTrue(hit?.fresh == true)
    }

    @Test
    fun marksOldArtifactAsStale() {
        val old = directory.resolve("demo-111.mir")
        Files.writeString(old, "old")
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000))

        val hit = ArtifactLocator().locate(plan(listOf(directory)), runStartedAtMillis = 100_000)
        assertEquals(old, hit?.path)
        assertFalse(hit?.fresh == true)
    }

    @Test
    fun treatsArtifactNewerThanSourcesAsFreshEvenAfterRunStart() {
        val artifact = directory.resolve("demo-111.mir")
        Files.writeString(artifact, "mir")
        Files.writeString(directory.resolve("demo-111.d"), "demo-111.mir: src/lib.rs\n")
        val source = directory.resolve("src/lib.rs")
        Files.createDirectories(source.parent)
        Files.writeString(source, "fn get() {}")
        Files.setLastModifiedTime(source, FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(artifact, FileTime.fromMillis(2_000))

        val hit = ArtifactLocator().locate(plan(listOf(directory)), runStartedAtMillis = 100_000)
        assertEquals(artifact, hit?.path)
        assertTrue(hit?.fresh == true)
    }

    @Test
    fun marksArtifactOlderThanSourcesAsStale() {
        val artifact = directory.resolve("demo-111.mir")
        Files.writeString(artifact, "mir")
        Files.writeString(directory.resolve("demo-111.d"), "demo-111.mir: src/lib.rs\n")
        val source = directory.resolve("src/lib.rs")
        Files.createDirectories(source.parent)
        Files.writeString(source, "fn get() {}")
        Files.setLastModifiedTime(source, FileTime.fromMillis(5_000))
        Files.setLastModifiedTime(artifact, FileTime.fromMillis(2_000))

        val hit = ArtifactLocator().locate(plan(listOf(directory)), runStartedAtMillis = 1_000)
        assertEquals(artifact, hit?.path)
        assertFalse(hit?.fresh == true)
    }

    @Test
    fun prefersArtifactOwnedByTheSelectedTarget() {
        val lib = directory.resolve("demo-111.mir")
        val bin = directory.resolve("demo-222.mir")
        Files.writeString(lib, "lib")
        Files.writeString(bin, "bin")
        Files.writeString(directory.resolve("demo-111.d"), "demo-111.mir: src/lib.rs src/api.rs\n")
        Files.writeString(directory.resolve("demo-222.d"), "demo-222.mir: src/main.rs src/api.rs\n")
        Files.setLastModifiedTime(lib, FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(bin, FileTime.fromMillis(5_000))

        val hit = ArtifactLocator().locate(
            plan(listOf(directory), targetSource = directory.resolve("src/lib.rs")),
            runStartedAtMillis = 100_000,
        )
        assertEquals(lib, hit?.path)
    }

    @Test
    fun returnsNullWhenOnlyOtherTargetsProducedArtifacts() {
        Files.writeString(directory.resolve("demo-111.mir"), "lib")
        Files.writeString(directory.resolve("demo-111.d"), "demo-111.mir: src/lib.rs\n")

        val hit = ArtifactLocator().locate(
            plan(listOf(directory), targetSource = directory.resolve("src/main.rs")),
            runStartedAtMillis = 5_000,
        )
        assertNull(hit)
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        Files.writeString(directory.resolve("other.mir"), "ignore")
        assertNull(ArtifactLocator().locate(plan(listOf(directory)), runStartedAtMillis = 5_000))
    }

    @Test
    fun returnsNullForStdoutPlans() {
        val stdoutPlan = plan(listOf(directory)).copy(outputMode = OutputMode.STDOUT, artifactExtension = null)
        assertNull(ArtifactLocator().locate(stdoutPlan, runStartedAtMillis = 5_000))
    }
}
