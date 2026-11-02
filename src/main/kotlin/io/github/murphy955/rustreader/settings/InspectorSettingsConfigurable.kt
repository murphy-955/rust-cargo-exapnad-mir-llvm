package io.github.murphy955.rustreader.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.github.murphy955.rustreader.command.ToolDetector
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class InspectorSettingsConfigurable(private val project: Project) : SearchableConfigurable {
    private var panel: SettingsPanel? = null

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Rust Compiler Reader"

    override fun createComponent(): JComponent {
        val newPanel = SettingsPanel(InspectorSettings.getInstance(project))
        panel = newPanel
        return newPanel.root
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun getPreferredFocusedComponent(): JComponent? = panel?.cargoField

    override fun disposeUIResources() {
        panel = null
    }

    companion object {
        const val ID: String = "io.github.murphy955.rustreader.settings"
    }

    private inner class SettingsPanel(private val settings: InspectorSettings) {
        val cargoField = JBTextField(settings.cargoPath, 30)
        private val toolchainCombo = ComboBox(arrayOf("auto", "stable", "nightly", "custom"))
        private val customToolchainField = JBTextField(settings.state.customToolchain, 20)
        private val profileField = JBTextField(settings.profile, 12)
        private val targetTripleField = JBTextField(settings.targetTriple, 24)
        private val featuresModeCombo = ComboBox(arrayOf("default", "all", "no-default", "custom"))
        private val featuresField = JBTextField(settings.features, 30)
        private val extraArgsField = JBTextField(settings.extraRustcArgs, 40)
        private val macroBackendCombo = ComboBox(arrayOf("auto", "cargo-expand", "rustc"))
        private val rustfmtField = JBTextField(settings.rustfmtPath, 24)
        private val timeoutSpinner = JSpinner(SpinnerNumberModel(settings.timeoutSeconds, 10, 3600, 10))
        private val defaultTabCombo = ComboBox(arrayOf("MACRO", "MIR", "ASM"))

        private val allowBootstrapCheck = JBCheckBox("Allow RUSTC_BOOTSTRAP=1 for stable macro expansion", settings.allowRustcBootstrap)
        private val formatCheck = JBCheckBox("Format macro expansion with rustfmt when available", settings.formatExpanded)
        private val preferCargoAsmCheck = JBCheckBox("Prefer cargo-show-asm when installed", settings.preferCargoShowAsm)
        private val isolatedCheck = JBCheckBox("Use an isolated target directory (.rust-compiler-reader/target)", settings.isolatedTargetDir)
        private val wholeCrateCheck = JBCheckBox("Default to whole-crate macro expansion", settings.wholeCrate)
        private val includeClosuresCheck = JBCheckBox("Include closures in MIR output", settings.includeClosures)

        private val cargoExpandStatus = JBLabel()
        private val cargoAsmStatus = JBLabel()
        private val rustfmtStatus = JBLabel()

        private val original = snapshot()
        lateinit var root: JComponent
            private set

        init {
            reset()
            buildUi()
            updateFieldStates()
            updateToolStatus()
        }

        fun isModified(): Boolean = snapshot() != original

        fun apply() {
            val state = settings.state
            state.cargoPath = cargoField.text.trim().ifEmpty { "cargo" }
            state.toolchain = toolchainCombo.selectedItem?.toString() ?: "auto"
            state.customToolchain = customToolchainField.text.trim()
            state.profile = profileField.text.trim().ifEmpty { "dev" }
            state.targetTriple = targetTripleField.text.trim()
            state.featuresMode = featuresModeCombo.selectedItem?.toString() ?: "default"
            state.features = featuresField.text.trim()
            state.extraRustcArgs = extraArgsField.text.trim()
            state.macroBackend = macroBackendCombo.selectedItem?.toString() ?: "auto"
            state.allowRustcBootstrap = allowBootstrapCheck.isSelected
            state.formatExpanded = formatCheck.isSelected
            state.rustfmtPath = rustfmtField.text.trim().ifEmpty { "rustfmt" }
            state.preferCargoShowAsm = preferCargoAsmCheck.isSelected
            state.isolatedTargetDir = isolatedCheck.isSelected
            state.defaultTab = defaultTabCombo.selectedItem?.toString() ?: "MIR"
            state.wholeCrate = wholeCrateCheck.isSelected
            state.includeClosures = includeClosuresCheck.isSelected
            settings.timeoutSeconds = (timeoutSpinner.value as Number).toInt()
            ToolDetector.clearCache()
            updateToolStatus()
        }

        fun reset() {
            val state = settings.state
            cargoField.text = state.cargoPath
            toolchainCombo.selectedItem = state.toolchain
            customToolchainField.text = state.customToolchain
            profileField.text = state.profile
            targetTripleField.text = state.targetTriple
            featuresModeCombo.selectedItem = state.featuresMode
            featuresField.text = state.features
            extraArgsField.text = state.extraRustcArgs
            macroBackendCombo.selectedItem = state.macroBackend
            rustfmtField.text = state.rustfmtPath
            timeoutSpinner.value = state.timeoutSeconds
            defaultTabCombo.selectedItem = state.defaultTab
            allowBootstrapCheck.isSelected = state.allowRustcBootstrap
            formatCheck.isSelected = state.formatExpanded
            preferCargoAsmCheck.isSelected = state.preferCargoShowAsm
            isolatedCheck.isSelected = state.isolatedTargetDir
            wholeCrateCheck.isSelected = state.wholeCrate
            includeClosuresCheck.isSelected = state.includeClosures
            updateFieldStates()
        }

        private fun buildUi() {
            val cargoRow = JPanel(BorderLayout(4, 0)).apply {
                add(cargoField, BorderLayout.CENTER)
                add(browseButton("Choose cargo executable") { path -> cargoField.text = path }, BorderLayout.EAST)
            }
            val toolchainRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(toolchainCombo)
                add(javax.swing.Box.createHorizontalStrut(8))
                add(JBLabel("custom:"))
                add(customToolchainField)
            }
            val rustfmtRow = JPanel(BorderLayout(4, 0)).apply {
                add(rustfmtField, BorderLayout.CENTER)
                add(browseButton("Choose rustfmt executable") { path -> rustfmtField.text = path }, BorderLayout.EAST)
            }
            val featuresRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(featuresModeCombo)
                add(javax.swing.Box.createHorizontalStrut(8))
                add(featuresField)
            }

            val form = FormBuilder.createFormBuilder()
                .addComponent(TitledSeparator("Build"))
                .addLabeledComponent("Cargo executable:", cargoRow)
                .addLabeledComponent("Toolchain:", toolchainRow)
                .addLabeledComponent("Profile:", profileField)
                .addLabeledComponent("Target triple (empty = host):", targetTripleField)
                .addLabeledComponent("Features:", featuresRow)
                .addLabeledComponent("Extra rustc arguments:", extraArgsField)
                .addLabeledComponent("Default tab:", defaultTabCombo)
                .addComponent(TitledSeparator("Macro expansion"))
                .addLabeledComponent("Backend:", macroBackendCombo)
                .addComponent(allowBootstrapCheck)
                .addComponent(formatCheck)
                .addLabeledComponent("rustfmt executable:", rustfmtRow)
                .addComponent(rustfmtStatus)
                .addComponent(TitledSeparator("Assembly"))
                .addComponent(preferCargoAsmCheck)
                .addComponent(cargoAsmStatus)
                .addComponent(TitledSeparator("Execution"))
                .addLabeledComponent("Timeout (seconds):", timeoutSpinner)
                .addComponent(isolatedCheck)
                .addComponent(wholeCrateCheck)
                .addComponent(includeClosuresCheck)
                .addComponent(TitledSeparator("Detected tools"))
                .addComponent(cargoExpandStatus)
                .addComponent(
                    JPanel(BorderLayout()).apply {
                        add(JBLabel("Tools are never installed automatically."), BorderLayout.WEST)
                        add(JButton("Refresh").apply { addActionListener { ToolDetector.clearCache(); updateToolStatus() } }, BorderLayout.EAST)
                    },
                )
                .addComponentFillVertically(JPanel(), 0)
                .panel

            form.border = JBUI.Borders.empty(8)
            root = JPanel(BorderLayout()).apply {
                add(form, BorderLayout.NORTH)
                preferredSize = Dimension(760, 620)
            }

            toolchainCombo.addActionListener { updateFieldStates() }
            featuresModeCombo.addActionListener { updateFieldStates() }
        }

        private fun updateFieldStates() {
            customToolchainField.isEnabled = toolchainCombo.selectedItem == "custom"
            allowBootstrapCheck.isEnabled = toolchainCombo.selectedItem != "nightly" && toolchainCombo.selectedItem != "custom"
            featuresField.isEnabled = featuresModeCombo.selectedItem == "custom"
        }

        private fun updateToolStatus() {
            val cargo = cargoField.text.trim().ifBlank { "cargo" }
            cargoExpandStatus.text = statusText("cargo-expand", ToolDetector.isCargoSubcommandAvailable("cargo-expand", cargo))
            cargoAsmStatus.text = statusText("cargo-show-asm", ToolDetector.isCargoSubcommandAvailable("cargo-asm", cargo))
            rustfmtStatus.text = statusText("rustfmt", ToolDetector.findExecutable(rustfmtField.text.trim().ifBlank { "rustfmt" }) != null)
        }

        private fun statusText(name: String, available: Boolean): String =
            if (available) "<html><b>$name</b>: found</html>" else "<html><b>$name</b>: not found on PATH</html>"

        private fun browseButton(title: String, onSelected: (String) -> Unit): JButton = JButton("Browse…").apply {
            addActionListener {
                val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                val file = FileChooser.chooseFile(descriptor, project, null) ?: return@addActionListener
                onSelected(file.path)
            }
            toolTipText = title
        }

        private fun snapshot(): UiState = UiState(
            cargo = cargoField.text,
            toolchain = toolchainCombo.selectedItem?.toString().orEmpty(),
            customToolchain = customToolchainField.text,
            profile = profileField.text,
            targetTriple = targetTripleField.text,
            featuresMode = featuresModeCombo.selectedItem?.toString().orEmpty(),
            features = featuresField.text,
            extraArgs = extraArgsField.text,
            macroBackend = macroBackendCombo.selectedItem?.toString().orEmpty(),
            allowBootstrap = allowBootstrapCheck.isSelected,
            format = formatCheck.isSelected,
            rustfmt = rustfmtField.text,
            preferCargoAsm = preferCargoAsmCheck.isSelected,
            isolated = isolatedCheck.isSelected,
            defaultTab = defaultTabCombo.selectedItem?.toString().orEmpty(),
            wholeCrate = wholeCrateCheck.isSelected,
            includeClosures = includeClosuresCheck.isSelected,
            timeout = (timeoutSpinner.value as Number).toInt(),
        )
    }

    private data class UiState(
        val cargo: String,
        val toolchain: String,
        val customToolchain: String,
        val profile: String,
        val targetTriple: String,
        val featuresMode: String,
        val features: String,
        val extraArgs: String,
        val macroBackend: String,
        val allowBootstrap: Boolean,
        val format: Boolean,
        val rustfmt: String,
        val preferCargoAsm: Boolean,
        val isolated: Boolean,
        val defaultTab: String,
        val wholeCrate: Boolean,
        val includeClosures: Boolean,
        val timeout: Int,
    )
}
