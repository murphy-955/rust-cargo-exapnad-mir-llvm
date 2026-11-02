package io.github.murphy955.rustreader.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import io.github.murphy955.rustreader.command.CommandPlanner
import io.github.murphy955.rustreader.command.MacroBackend
import io.github.murphy955.rustreader.command.PlannerOptions
import io.github.murphy955.rustreader.context.FeaturesMode
import io.github.murphy955.rustreader.context.FeaturesSpec
import io.github.murphy955.rustreader.context.ResolverOptions
import io.github.murphy955.rustreader.context.ToolchainKind
import io.github.murphy955.rustreader.context.ToolchainSpec

@State(name = "RustCompilerReader", storages = [Storage("rust-compiler-reader.xml")])
class InspectorSettings : PersistentStateComponent<InspectorSettings.State> {
    data class State(
        var cargoPath: String = "cargo",
        var toolchain: String = "auto",
        var customToolchain: String = "",
        var profile: String = "dev",
        var targetTriple: String = "",
        var featuresMode: String = "default",
        var features: String = "",
        var extraRustcArgs: String = "",
        var macroBackend: String = "auto",
        var allowRustcBootstrap: Boolean = true,
        var formatExpanded: Boolean = true,
        var rustfmtPath: String = "rustfmt",
        var preferCargoShowAsm: Boolean = false,
        var timeoutSeconds: Int = 120,
        var isolatedTargetDir: Boolean = false,
        var defaultTab: String = "MIR",
        var wholeCrate: Boolean = false,
        var includeClosures: Boolean = true,
        var targetSelections: MutableMap<String, String> = mutableMapOf(),
        var lastTab: String = "MIR",
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var cargoPath: String
        get() = myState.cargoPath
        set(value) {
            myState.cargoPath = value
        }

    var timeoutSeconds: Int
        get() = myState.timeoutSeconds
        set(value) {
            myState.timeoutSeconds = value.coerceIn(10, 3600)
        }

    var profile: String
        get() = myState.profile
        set(value) {
            myState.profile = value
        }

    var defaultTab: String
        get() = myState.defaultTab
        set(value) {
            myState.defaultTab = value
        }

    var lastTab: String
        get() = myState.lastTab
        set(value) {
            myState.lastTab = value
        }

    var wholeCrate: Boolean
        get() = myState.wholeCrate
        set(value) {
            myState.wholeCrate = value
        }

    var includeClosures: Boolean
        get() = myState.includeClosures
        set(value) {
            myState.includeClosures = value
        }

    var isolatedTargetDir: Boolean
        get() = myState.isolatedTargetDir
        set(value) {
            myState.isolatedTargetDir = value
        }

    fun toolchainSpec(): ToolchainSpec {
        val kind = when (myState.toolchain.lowercase()) {
            "stable" -> ToolchainKind.STABLE
            "nightly" -> ToolchainKind.NIGHTLY
            "custom" -> ToolchainKind.CUSTOM
            else -> ToolchainKind.AUTO
        }
        return ToolchainSpec(
            kind = kind,
            custom = myState.customToolchain.takeIf { it.isNotBlank() },
            cargoExecutable = myState.cargoPath.ifBlank { "cargo" },
        )
    }

    fun featuresSpec(): FeaturesSpec {
        val mode = when (myState.featuresMode.lowercase()) {
            "all" -> FeaturesMode.ALL
            "no-default", "none" -> FeaturesMode.NO_DEFAULT
            "custom" -> FeaturesMode.CUSTOM
            else -> FeaturesMode.DEFAULT
        }
        val features = myState.features
            .split(',', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return FeaturesSpec(mode, features)
    }

    fun macroBackend(): MacroBackend = when (myState.macroBackend.lowercase()) {
        "cargo-expand", "expand" -> MacroBackend.CARGO_EXPAND
        "rustc" -> MacroBackend.RUSTC
        else -> MacroBackend.AUTO
    }

    fun plannerOptions(): PlannerOptions = PlannerOptions(
        macroBackend = macroBackend(),
        allowRustcBootstrap = myState.allowRustcBootstrap,
        preferCargoShowAsm = myState.preferCargoShowAsm,
        isolatedTargetDir = myState.isolatedTargetDir,
    )

    fun resolverOptions(profileOverride: String? = null, targetOverride: String? = null): ResolverOptions = ResolverOptions(
        cargoExecutable = myState.cargoPath.ifBlank { "cargo" },
        toolchain = toolchainSpec(),
        profile = profileOverride?.takeIf { it.isNotBlank() } ?: myState.profile.ifBlank { "dev" },
        targetTriple = myState.targetTriple.trim(),
        features = featuresSpec(),
        extraRustcArgs = parseRustcArgs(myState.extraRustcArgs),
        isolatedTargetDir = myState.isolatedTargetDir,
        timeoutSeconds = timeoutSeconds,
        targetOverride = targetOverride,
        targetOverrides = myState.targetSelections.toMap(),
    )

    fun targetOverride(packageName: String): String? = myState.targetSelections[packageName]

    fun rememberTarget(packageName: String, targetKey: String?) {
        if (targetKey.isNullOrBlank()) {
            myState.targetSelections.remove(packageName)
        } else {
            myState.targetSelections[packageName] = targetKey
        }
    }

    val formatExpanded: Boolean
        get() = myState.formatExpanded

    val rustfmtPath: String
        get() = myState.rustfmtPath.ifBlank { "rustfmt" }

    val allowRustcBootstrap: Boolean
        get() = myState.allowRustcBootstrap

    val preferCargoShowAsm: Boolean
        get() = myState.preferCargoShowAsm

    val extraRustcArgs: String
        get() = myState.extraRustcArgs

    val targetTriple: String
        get() = myState.targetTriple

    val featuresMode: String
        get() = myState.featuresMode

    val features: String
        get() = myState.features

    companion object {
        fun getInstance(project: Project): InspectorSettings = project.getService(InspectorSettings::class.java)

        fun parseRustcArgs(value: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var escaped = false
            for (char in value) {
                when {
                    escaped -> {
                        current.append(char)
                        escaped = false
                    }

                    char == '\\' -> escaped = true
                    quote != null && char == quote -> quote = null
                    quote == null && (char == '"' || char == '\'') -> quote = char
                    quote == null && char.isWhitespace() -> {
                        if (current.isNotEmpty()) {
                            result += current.toString()
                            current.clear()
                        }
                    }

                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) result += current.toString()
            return result
        }

        fun profileDirectory(profile: String): String = CommandPlanner.profileDirectory(profile)
    }
}
