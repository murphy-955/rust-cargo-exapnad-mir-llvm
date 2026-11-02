package io.github.murphy955.rustreader.settings

import com.intellij.util.xmlb.XmlSerializer
import io.github.murphy955.rustreader.command.MacroBackend
import io.github.murphy955.rustreader.context.FeaturesMode
import io.github.murphy955.rustreader.context.ToolchainKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorSettingsTest {
    @Test
    fun parsesRustcArgumentsWithQuotes() {
        val args = InspectorSettings.parseRustcArgs("""-C target-cpu=native --cfg "foo bar" -Z'x y'""")
        assertEquals(listOf("-C", "target-cpu=native", "--cfg", "foo bar", "-Zx y"), args)
    }

    @Test
    fun mapsToolchainAndFeatures() {
        val settings = InspectorSettings()
        settings.loadState(
            InspectorSettings.State(
                cargoPath = "/opt/cargo",
                toolchain = "nightly",
                featuresMode = "custom",
                features = "a, b c",
                macroBackend = "rustc",
            ),
        )
        val toolchain = settings.toolchainSpec()
        assertEquals(ToolchainKind.NIGHTLY, toolchain.kind)
        assertEquals("/opt/cargo", toolchain.cargoExecutable)
        assertEquals("+nightly", toolchain.rustupArgument())

        val features = settings.featuresSpec()
        assertEquals(FeaturesMode.CUSTOM, features.mode)
        assertEquals(listOf("a", "b", "c"), features.features)
        assertEquals(MacroBackend.RUSTC, settings.macroBackend())
    }

    @Test
    fun resolverOptionsUseOverrides() {
        val settings = InspectorSettings()
        settings.loadState(InspectorSettings.State(profile = "dev", targetTriple = "wasm32-unknown-unknown"))
        val options = settings.resolverOptions(profileOverride = "release", targetOverride = "bin:app")
        assertEquals("release", options.profile)
        assertEquals("bin:app", options.targetOverride)
        assertEquals("wasm32-unknown-unknown", options.targetTriple)
    }

    @Test
    fun remembersTargetSelectionPerPackage() {
        val settings = InspectorSettings()
        settings.rememberTarget("app", "bin:app")
        assertEquals("bin:app", settings.targetOverride("app"))
        settings.rememberTarget("app", null)
        assertEquals(null, settings.targetOverride("app"))
    }

    @Test
    fun stateRoundTripsThroughXml() {
        val settings = InspectorSettings()
        settings.loadState(
            InspectorSettings.State(
                cargoPath = "/custom/cargo",
                timeoutSeconds = 42,
                features = "x,y",
                targetSelections = mutableMapOf("app" to "lib:app"),
            ),
        )
        val xml = XmlSerializer.serialize(settings.state)
        val restored = XmlSerializer.deserialize(xml, InspectorSettings.State::class.java)
        assertEquals("/custom/cargo", restored.cargoPath)
        assertEquals(42, restored.timeoutSeconds)
        assertEquals("x,y", restored.features)
        assertEquals("lib:app", restored.targetSelections["app"])
    }

    @Test
    fun profileDirectoryMapping() {
        assertEquals("debug", InspectorSettings.profileDirectory("dev"))
        assertEquals("debug", InspectorSettings.profileDirectory("check"))
        assertEquals("release", InspectorSettings.profileDirectory("release"))
        assertEquals("custom", InspectorSettings.profileDirectory("custom"))
        assertTrue(InspectorSettings.profileDirectory("test") == "debug")
    }
}
