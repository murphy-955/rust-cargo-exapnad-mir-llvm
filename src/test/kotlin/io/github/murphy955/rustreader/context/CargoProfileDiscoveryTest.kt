package io.github.murphy955.rustreader.context

import org.junit.Assert.assertEquals
import org.junit.Test

class CargoProfileDiscoveryTest {
    @Test
    fun findsCustomProfilesInManifestOrder() {
        val manifest = """
            [package]
            name = "demo"

            [profile.release]
            opt-level = 3

            [profile.maxopt]
            inherits = "release"

            [profile.fast]
            inherits = "release"

            [profile.small]
            inherits = "release"

            [profile.release.package.dependency]
            opt-level = 2
        """.trimIndent()

        assertEquals(
            listOf("dev", "release", "test", "bench", "check", "maxopt", "fast", "small"),
            CargoProfileDiscovery.names(manifest),
        )
    }

    @Test
    fun ignoresCommentedProfileSections() {
        val manifest = """
            # [profile.commented]
            [profile.real] # inline comment
            opt-level = 1
        """.trimIndent()

        assertEquals(
            listOf("dev", "release", "test", "bench", "check", "real"),
            CargoProfileDiscovery.names(manifest),
        )
    }
}
