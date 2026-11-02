package io.github.murphy955.rustreader.ui

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import io.github.murphy955.rustreader.command.ArtifactKind
import io.github.murphy955.rustreader.command.ArtifactLocator
import io.github.murphy955.rustreader.command.CommandPlan
import io.github.murphy955.rustreader.command.CommandPlanner
import io.github.murphy955.rustreader.command.OutputMode
import io.github.murphy955.rustreader.command.RustProcessRunner
import io.github.murphy955.rustreader.command.RustfmtFormatter
import io.github.murphy955.rustreader.command.ShellQuote
import io.github.murphy955.rustreader.context.CargoMetadataService
import io.github.murphy955.rustreader.context.CargoProfileDiscovery
import io.github.murphy955.rustreader.context.ResolveResult
import io.github.murphy955.rustreader.context.ResolverOptions
import io.github.murphy955.rustreader.context.RustContext
import io.github.murphy955.rustreader.context.RustContextResolver
import io.github.murphy955.rustreader.context.TargetRef
import io.github.murphy955.rustreader.extract.AsmExtractor
import io.github.murphy955.rustreader.extract.AsmExtractResult
import io.github.murphy955.rustreader.extract.ExpandExtractor
import io.github.murphy955.rustreader.extract.ExtractResult
import io.github.murphy955.rustreader.extract.MirExtractor
import io.github.murphy955.rustreader.settings.InspectorSettings
import io.github.murphy955.rustreader.settings.InspectorSettingsConfigurable
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import kotlin.concurrent.withLock

class InspectorPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val settings = InspectorSettings.getInstance(project)
    private val metadataService = CargoMetadataService()
    private val resolver = RustContextResolver(metadataService)
    private val runner = RustProcessRunner()
    private val artifactLocator = ArtifactLocator()
    private val formatter = RustfmtFormatter(runner)

    private val runLock = ReentrantLock(true)
    private val generation = AtomicInteger()
    private val results = ConcurrentHashMap<String, CachedResult>()
    private val lastPlans = ConcurrentHashMap<ArtifactKind, CommandPlan>()

    private val fileLabel = JBLabel("No Rust file").apply {
        font = JBUI.Fonts.label(15f).asBold()
    }
    private val itemLabel = JBLabel("Open a Rust file to inspect an item").apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val targetLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val statusLabel = JBLabel("Ready", AllIcons.General.Information, SwingConstants.LEFT)
    private val timeLabel = JBLabel("").apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val profileCombo = JComboBox(CargoProfileDiscovery.BUILT_IN.toTypedArray()).apply {
        toolTipText = "Profile used by Rust Compiler Reader; independent of the IDE Cargo configuration"
    }
    private val targetCombo = JComboBox<TargetRef>()
    private val configurationToggle = JButton().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        horizontalAlignment = SwingConstants.LEFT
        toolTipText = "Show or hide build configuration"
    }
    private val configurationPanel = JPanel(WrapLayout(FlowLayout.LEFT, 7, 3)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
    }
    private val copyButton = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Copy the command for the active view"
    }
    private val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh).apply {
        toolTipText = "Regenerate the active view"
    }
    private val settingsButton = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Open Rust Compiler Reader settings"
    }
    private val openButton = JButton("Open in Editor")
    private val saveButton = JButton("Save…")

    private val wholeCrateCheck = JBCheckBox("Whole crate", settings.wholeCrate)
    private val includeClosuresCheck = JBCheckBox("Include closures", settings.includeClosures)
    private val wholeAsmCheck = JBCheckBox("Whole assembly", false)

    private val macroTab = OutputTab(project, ArtifactKind.MACRO)
    private val mirTab = OutputTab(project, ArtifactKind.MIR)
    private val asmTab = OutputTab(project, ArtifactKind.ASM)
    private val navigationList = JBList(ArtifactKind.entries.toList()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ViewNavigationRenderer { navigationExpanded }
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4, 5)
        accessibleContext.accessibleName = "Compiler views"
    }
    private val viewCards = JPanel(CardLayout())
    private val viewTitleLabel = JBLabel().apply {
        font = JBUI.Fonts.label(15f).asBold()
    }
    private val viewDescriptionLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private val buildOutput = BuildOutputView(project) {
        currentContext?.workspaceRoot ?: project.basePath?.let { Path.of(it) }
    }
    private val outputBody = JPanel(BorderLayout()).apply {
        add(buildOutput.component, BorderLayout.CENTER)
        preferredSize = Dimension(0, 180)
    }
    private val outputToggle = JButton("▸ Build output").apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        horizontalAlignment = SwingConstants.LEFT
    }
    private val outputPanel = JPanel(BorderLayout()).apply {
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(2, 8)
                add(outputToggle, BorderLayout.WEST)
                add(JButton("Clear").apply { addActionListener { buildOutput.clear() } }, BorderLayout.EAST)
            },
            BorderLayout.NORTH,
        )
        add(outputBody, BorderLayout.CENTER)
        outputBody.isVisible = false
    }

    @Volatile
    private var currentFile: VirtualFile? = null

    @Volatile
    private var caretOffset: Int = 0

    @Volatile
    private var currentContext: RustContext? = null

    @Volatile
    private var currentWarnings: List<String> = emptyList()

    @Volatile
    private var resolvedSignature: String? = null

    @Volatile
    private var knownPackageName: String? = null

    @Volatile
    private var disposed = false

    @Volatile
    private var activeKind: ArtifactKind = ArtifactKind.MIR

    private var selectingTab = false
    private var updatingProfiles = false
    private var updatingTargets = false
    private var outputExpanded = false
    private var navigationExpanded = false
    private var configurationExpanded = false

    init {
        setupViews()
        add(buildHeader(), BorderLayout.NORTH)
        add(buildWorkspace(), BorderLayout.CENTER)
        add(buildFooter(), BorderLayout.SOUTH)
        installListeners()
        showWelcomeState()
    }

    fun showContext(file: VirtualFile, offset: Int) {
        if (disposed || !file.name.endsWith(".rs", ignoreCase = true)) return
        currentFile = file
        caretOffset = offset
        currentContext = null
        currentWarnings = emptyList()
        resolvedSignature = null
        knownPackageName = null
        results.clear()
        lastPlans.clear()
        updateFileLabel(file)
        itemLabel.text = "Resolving the selected item…"
        targetLabel.text = ""
        timeLabel.text = ""
        profileCombo.selectedItem = settings.profile
        status("Resolving Cargo context…")
        val kind = kindFromName(settings.lastTab.ifBlank { settings.defaultTab })
        selectTab(kind)
        runTab(kind, force = true)
    }

    private fun buildHeader(): JComponent {
        profileCombo.isEditable = true
        profileCombo.selectedItem = settings.profile
        resizeProfileCombo()
        targetCombo.isEnabled = false
        targetCombo.preferredSize = Dimension(180, targetCombo.preferredSize.height)

        val identityRow = JPanel(BorderLayout(8, 0)).apply {
            add(fileLabel, BorderLayout.CENTER)
            add(settingsButton, BorderLayout.EAST)
        }
        val itemRow = JPanel(WrapLayout(FlowLayout.LEFT, 10, 1)).apply {
            add(itemLabel)
            add(targetLabel)
        }
        val actionRow = JPanel(WrapLayout(FlowLayout.LEFT, 7, 3)).apply {
            add(refreshButton)
            add(copyButton)
            add(openButton)
            add(saveButton)
            add(configurationToggle)
        }
        configurationPanel.apply {
            add(JBLabel("Profile"))
            add(profileCombo)
            add(Box.createHorizontalStrut(5))
            add(JBLabel("Target"))
            add(targetCombo)
        }
        configurationToggle.addActionListener {
            setConfigurationExpanded(!configurationExpanded)
        }
        updateConfigurationSummary()
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(12, 12, 10, 12),
            )
            add(identityRow)
            add(Box.createVerticalStrut(3))
            add(itemRow)
            add(Box.createVerticalStrut(9))
            add(actionRow)
            add(Box.createVerticalStrut(2))
            add(configurationPanel)
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    revalidate()
                }
            })
        }
    }

    private fun buildWorkspace(): JComponent {
        val navigationTitle = JBLabel("VIEWS").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
            border = JBUI.Borders.empty(5, 5, 4, 5)
            isVisible = false
        }
        val navigationToggle = JButton(AllIcons.Actions.Forward).apply {
            toolTipText = "Expand compiler views"
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
        }
        val navigationHeader = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 4)
            add(navigationTitle, BorderLayout.WEST)
            add(navigationToggle, BorderLayout.EAST)
        }
        val navigation = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(52, 0)
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 1)
            background = UIUtil.getPanelBackground()
            add(navigationHeader, BorderLayout.NORTH)
            add(navigationList, BorderLayout.CENTER)
        }
        navigationToggle.addActionListener {
            navigationExpanded = !navigationExpanded
            navigationTitle.isVisible = navigationExpanded
            navigationToggle.icon = if (navigationExpanded) AllIcons.Actions.Back else AllIcons.Actions.Forward
            navigationToggle.toolTipText = if (navigationExpanded) {
                "Collapse compiler views"
            } else {
                "Expand compiler views"
            }
            navigation.preferredSize = Dimension(if (navigationExpanded) 154 else 52, 0)
            navigationList.revalidate()
            navigationList.repaint()
            navigation.revalidate()
            revalidate()
        }
        val viewHeader = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(10, 12, 9, 12),
            )
            add(viewTitleLabel)
            add(Box.createVerticalStrut(2))
            add(viewDescriptionLabel)
        }
        val content = JPanel(BorderLayout()).apply {
            add(viewHeader, BorderLayout.NORTH)
            add(viewCards, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            add(navigation, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
        }
    }

    private fun buildFooter(): JComponent {
        val statusRow = JPanel(BorderLayout(12, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(5, 10),
            )
            add(statusLabel, BorderLayout.WEST)
            add(timeLabel, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            add(outputPanel, BorderLayout.NORTH)
            add(statusRow, BorderLayout.SOUTH)
        }
    }

    private fun setupViews() {
        macroTab.addOption(wholeCrateCheck)
        mirTab.addOption(includeClosuresCheck)
        asmTab.addOption(wholeAsmCheck)
        viewCards.add(macroTab.root, ArtifactKind.MACRO.name)
        viewCards.add(mirTab.root, ArtifactKind.MIR.name)
        viewCards.add(asmTab.root, ArtifactKind.ASM.name)
        navigationList.setSelectedValue(ArtifactKind.MIR, false)
        (viewCards.layout as CardLayout).show(viewCards, ArtifactKind.MIR.name)
        updateViewHeader(ArtifactKind.MIR)
    }

    private fun updateViewHeader(kind: ArtifactKind) {
        viewTitleLabel.text = kind.label()
        viewDescriptionLabel.text = when (kind) {
            ArtifactKind.MACRO -> "Expanded Rust source for the selected item"
            ArtifactKind.MIR -> "Mid-level IR for the selected item"
            ArtifactKind.ASM -> "Native assembly for the selected item"
        }
    }

    private fun setConfigurationExpanded(expanded: Boolean) {
        configurationExpanded = expanded
        configurationPanel.isVisible = expanded
        updateConfigurationSummary()
        configurationPanel.parent?.revalidate()
        revalidate()
        repaint()
    }

    private fun updateConfigurationSummary() {
        val profile = selectedProfile().ifBlank { "default" }
        val arrow = if (configurationExpanded) "▾" else "▸"
        configurationToggle.text = "$arrow Build configuration  ·  $profile"
        configurationToggle.toolTipText = if (configurationExpanded) {
            "Hide build configuration. Profile is independent of the IDE Cargo configuration."
        } else {
            "Show build configuration (current profile: $profile)"
        }
    }

    private fun selectedProfile(): String {
        val edited = profileCombo.editor.item?.toString()?.trim().orEmpty()
        return edited.ifBlank {
            profileCombo.selectedItem?.toString()?.trim().orEmpty()
        }.ifBlank {
            settings.profile
        }.ifBlank {
            "dev"
        }
    }

    private fun resizeProfileCombo() {
        val metrics = profileCombo.getFontMetrics(profileCombo.font)
        val names = (0 until profileCombo.itemCount).map { profileCombo.getItemAt(it).toString() } +
            selectedProfile()
        val textWidth = names.maxOfOrNull { metrics.stringWidth(it) } ?: 0
        val width = (textWidth + JBUI.scale(58)).coerceIn(JBUI.scale(132), JBUI.scale(260))
        val height = profileCombo.preferredSize.height.coerceAtLeast(JBUI.scale(28))
        profileCombo.preferredSize = Dimension(width, height)
        profileCombo.revalidate()
    }

    private fun updateProfileChoices(context: RustContext) {
        val selected = selectedProfile()
        val choices = (CargoProfileDiscovery.namesFor(context) + selected).distinct()
        val current = (0 until profileCombo.itemCount).map { profileCombo.getItemAt(it).toString() }
        if (current == choices) {
            resizeProfileCombo()
            return
        }
        updatingProfiles = true
        try {
            profileCombo.removeAllItems()
            choices.forEach { profileCombo.addItem(it) }
            profileCombo.selectedItem = selected
        } finally {
            updatingProfiles = false
        }
        resizeProfileCombo()
    }

    private fun installListeners() {
        refreshButton.addActionListener {
            if (currentFile == null) openCurrentEditor() else runTab(activeKind, force = true)
        }
        copyButton.addActionListener { copyCommand() }
        openButton.addActionListener { activeTab().openInEditor() }
        saveButton.addActionListener { activeTab().saveToFile() }
        settingsButton.addActionListener {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, InspectorSettingsConfigurable::class.java)
        }
        outputToggle.addActionListener { setOutputExpanded(!outputExpanded) }

        navigationList.addListSelectionListener { event ->
            if (event.valueIsAdjusting || selectingTab) return@addListSelectionListener
            val kind = navigationList.selectedValue ?: return@addListSelectionListener
            activeKind = kind
            settings.lastTab = kind.name
            (viewCards.layout as CardLayout).show(viewCards, kind.name)
            updateViewHeader(kind)
            if (currentContext == null || results[activeRequestKey(kind)] == null) {
                runTab(kind, force = false)
            } else {
                showCached(kind)
            }
        }

        profileCombo.addActionListener {
            if (updatingProfiles) return@addActionListener
            resizeProfileCombo()
            updateConfigurationSummary()
            val selected = selectedProfile()
            if (selected.isNotEmpty()) {
                settings.profile = selected
                invalidateContext()
                runTab(activeKind, force = true)
            }
        }

        targetCombo.addActionListener {
            if (updatingTargets) return@addActionListener
            val selected = targetCombo.selectedItem as? TargetRef ?: return@addActionListener
            val context = currentContext ?: return@addActionListener
            settings.rememberTarget(context.packageName, selected.key)
            invalidateContext()
            runTab(activeKind, force = true)
        }

        wholeCrateCheck.addActionListener {
            settings.wholeCrate = wholeCrateCheck.isSelected
            if (activeKind == ArtifactKind.MACRO) runTab(ArtifactKind.MACRO, force = true)
        }
        includeClosuresCheck.addActionListener {
            settings.includeClosures = includeClosuresCheck.isSelected
            if (activeKind == ArtifactKind.MIR) runTab(ArtifactKind.MIR, force = true)
        }
        wholeAsmCheck.addActionListener {
            if (activeKind == ArtifactKind.ASM) runTab(ArtifactKind.ASM, force = true)
        }

        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val file = currentFile ?: return
                var cargoChanged = false
                var fileChanged = false
                events.forEach { event ->
                    if (event.file == file) fileChanged = true
                    if (event.path.endsWith("Cargo.toml") || event.path.endsWith("rust-toolchain") ||
                        event.path.endsWith("rust-toolchain.toml")
                    ) {
                        cargoChanged = true
                    }
                }
                if (cargoChanged) {
                    metadataService.invalidate()
                    invalidateContext()
                }
                if (fileChanged || cargoChanged) markStale()
            }
        })
    }

    private fun runTab(kind: ArtifactKind, force: Boolean) {
        if (disposed || project.isDisposed) return
        val file = currentFile ?: return
        if (!TrustedProjects.isProjectTrusted(project)) {
            showFailure(
                kind,
                "The project is not trusted.",
                "Trust the project before running cargo. Rust Compiler Reader executes cargo in the project directory.",
            )
            return
        }

        val requestGeneration = generation.incrementAndGet()
        activeKind = kind
        val capturedOffset = caretOffset
        val options = settings.resolverOptions(
            profileOverride = selectedProfile(),
            targetOverride = knownPackageName?.let { settings.targetOverride(it) },
        )
        val plannerOptions = settings.plannerOptions()
        val timeoutSeconds = settings.timeoutSeconds
        val wholeCrate = wholeCrateCheck.isSelected
        val includeClosures = includeClosuresCheck.isSelected
        val wholeAsm = wholeAsmCheck.isSelected
        val formatExpansion = settings.formatExpanded
        val rustfmtPath = settings.rustfmtPath

        status("Running ${kind.label()}…")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Rust Compiler Reader: ${kind.label()}", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val text = documentText(file)
                    val resolveResult = ensureContext(file, capturedOffset, text, options)
                    val context = when (resolveResult) {
                        is ResolveResult.Success -> resolveResult.context
                        is ResolveResult.Failure -> {
                            invokeLaterSafely {
                                showFailure(kind, resolveResult.message, resolveResult.details)
                            }
                            return
                        }
                    }
                    knownPackageName = context.packageName
                    invokeLaterSafely {
                        if (requestGeneration == generation.get()) {
                            updateContextLabels(context, resolveResult.warnings)
                        }
                    }

                    val requestKey = requestKey(context, kind, wholeCrate, includeClosures, wholeAsm)
                    if (!force) {
                        val cached = results[requestKey]
                        if (cached != null) {
                            invokeLaterSafely {
                                if (requestGeneration == generation.get()) showCached(kind)
                            }
                            return
                        }
                    }

                    runLock.lock()
                    try {
                        if (indicator.isCanceled || disposed) return
                        val plan = CommandPlanner(plannerOptions).plan(kind, context)
                        lastPlans[kind] = plan
                        val startedAt = System.currentTimeMillis()
                        invokeLaterSafely {
                            if (requestGeneration == generation.get()) {
                                buildOutput.update(plan.displayCommand, "Running…", "")
                                setOutputExpanded(true)
                            }
                        }

                        val processResult = runner.run(plan.spec, timeoutSeconds, indicator)
                        if (indicator.isCanceled || processResult.canceled) {
                            invokeLaterSafely {
                                if (requestGeneration == generation.get()) status("Canceled")
                            }
                            return
                        }
                        if (processResult.timedOut) {
                            invokeLaterSafely {
                                if (requestGeneration == generation.get()) {
                                    showFailure(kind, "cargo timed out after $timeoutSeconds seconds.", processResult.stderr)
                                }
                            }
                            return
                        }
                        if (!processResult.succeeded) {
                            val output = listOf(processResult.stdout, processResult.stderr).filter { it.isNotBlank() }.joinToString("\n")
                            invokeLaterSafely {
                                if (requestGeneration == generation.get()) {
                                    showFailure(kind, "cargo exited with code ${processResult.exitCode}.", output, plan.displayCommand)
                                }
                            }
                            return
                        }

                        val outcome = when (plan.outputMode) {
                            OutputMode.STDOUT -> buildStdoutOutcome(kind, context, plan, processResult.stdout, wholeCrate, wholeAsm, includeClosures, formatExpansion, rustfmtPath)
                            OutputMode.ARTIFACT -> buildArtifactOutcome(kind, context, plan, startedAt, wholeAsm, includeClosures, formatExpansion, rustfmtPath)
                        }

                        val warnings = buildList {
                            addAll(resolveResult.warnings)
                            addAll(plan.warnings)
                            addAll(processResult.stderr.lineSequence().filter { it.isNotBlank() && it.contains("warning:", ignoreCase = true) }.map { it.trim() }.take(20))
                            addAll(outcome.warnings)
                            if (processResult.truncated) add("Command output exceeded the 50 MB limit and was truncated.")
                        }.distinct()

                        val statusText = if (outcome.empty) {
                            "No match · ${kind.label()}"
                        } else {
                            "Ready · ${kind.label()} · ${processResult.durationMillis} ms"
                        }
                        val artifactNote = outcome.artifactPath?.let { "\nArtifact: $it" } ?: ""
                        invokeLaterSafely {
                            if (requestGeneration != generation.get()) return@invokeLaterSafely
                            results[requestKey] = CachedResult(
                                content = outcome.content,
                                warnings = warnings,
                                emptyTitle = outcome.emptyTitle,
                                emptyHint = outcome.emptyHint,
                                status = statusText,
                            )
                            showCached(kind)
                            buildOutput.update(
                                plan.displayCommand,
                                statusText,
                                processResult.stderr + artifactNote,
                            )
                            status(statusText)
                            timeLabel.text = "Updated ${currentTimeText()}"
                        }
                    } finally {
                        runLock.unlock()
                    }
                } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                    invokeLaterSafely {
                        if (requestGeneration == generation.get()) status("Canceled")
                    }
                } catch (exception: Exception) {
                    invokeLaterSafely {
                        if (requestGeneration == generation.get()) {
                            showFailure(kind, exception.message ?: exception.javaClass.simpleName, "")
                        }
                    }
                }
            }
        })
    }

    private data class Outcome(
        val content: String = "",
        val warnings: List<String> = emptyList(),
        val empty: Boolean = false,
        val emptyTitle: String = "",
        val emptyHint: String? = null,
        val artifactPath: Path? = null,
    )

    private fun buildStdoutOutcome(
        kind: ArtifactKind,
        context: RustContext,
        plan: CommandPlan,
        stdout: String,
        wholeCrate: Boolean,
        wholeAsm: Boolean,
        includeClosures: Boolean,
        formatExpansion: Boolean,
        rustfmtPath: String,
    ): Outcome {
        if (stdout.isBlank()) {
            return Outcome(empty = true, emptyTitle = "No output produced.", emptyHint = "See Build output for details.")
        }
        return when (kind) {
            ArtifactKind.MACRO -> {
                val extracted = ExpandExtractor.extract(stdout, context.item, wholeCrate)
                var content = extracted.content
                if (formatExpansion && content.isNotBlank()) {
                    content = formatter.format(content, rustfmtPath, context.edition, context.workspaceRoot)
                }
                Outcome(content, extracted.warnings)
            }

            ArtifactKind.MIR -> {
                val extracted = MirExtractor.extract(stdout, context.item, includeClosures, relativeFileHint(context))
                toOutcome(extracted)
            }

            ArtifactKind.ASM -> {
                if (plan.itemAlreadyExtracted) {
                    Outcome(stdout)
                } else {
                    val extracted = AsmExtractor.extract(stdout, context.crateName, context.modulePath, context.item, wholeAsm)
                    toOutcome(extracted)
                }
            }
        }
    }

    private fun buildArtifactOutcome(
        kind: ArtifactKind,
        context: RustContext,
        plan: CommandPlan,
        startedAt: Long,
        wholeAsm: Boolean,
        includeClosures: Boolean,
        formatExpansion: Boolean,
        rustfmtPath: String,
    ): Outcome {
        val hit = artifactLocator.locate(plan, startedAt)
        if (hit == null) {
            return Outcome(
                empty = true,
                emptyTitle = "No .${plan.artifactExtension ?: "?"} artifact found.",
                emptyHint = "Looked in:\n${plan.artifactSearchDirs.joinToString("\n")}\n\n" +
                    "The target may not have been rebuilt, or Cargo uses a custom target directory.",
            )
        }
        val text = try {
            Files.readString(hit.path)
        } catch (exception: Exception) {
            return Outcome(empty = true, emptyTitle = "Cannot read ${hit.path.fileName}.", emptyHint = exception.message)
        }
        return when (kind) {
            ArtifactKind.MIR -> {
                val extracted = MirExtractor.extract(text, context.item, includeClosures, relativeFileHint(context))
                toOutcome(extracted).copy(artifactPath = hit.path, warnings = extracted.warnings + staleWarning(hit))
            }

            ArtifactKind.ASM -> {
                val extracted = AsmExtractor.extract(text, context.crateName, context.modulePath, context.item, wholeAsm)
                toOutcome(extracted).copy(artifactPath = hit.path, warnings = extracted.warnings + staleWarning(hit))
            }

            ArtifactKind.MACRO -> Outcome(text, artifactPath = hit.path)
        }
    }

    private fun staleWarning(hit: io.github.murphy955.rustreader.command.ArtifactHit): List<String> =
        if (hit.fresh) emptyList() else listOf("The newest artifact is older than this run; it may be stale.")

    private fun toOutcome(extracted: ExtractResult): Outcome = Outcome(
        content = extracted.content,
        warnings = extracted.warnings,
        empty = extracted.content.isBlank(),
        emptyTitle = "No matching output.",
        emptyHint = extracted.warnings.joinToString("\n").ifBlank { null },
    )

    private fun toOutcome(extracted: AsmExtractResult): Outcome = Outcome(
        content = extracted.content,
        warnings = extracted.warnings,
        empty = extracted.empty || extracted.content.isBlank(),
        emptyTitle = "Function not found in assembly.",
        emptyHint = extracted.warnings.joinToString("\n").ifBlank { null },
    )

    private fun ensureContext(
        file: VirtualFile,
        offset: Int,
        text: String,
        options: ResolverOptions,
    ): ResolveResult {
        val signature = contextSignature(file, offset, options) + "|" + text.hashCode()
        val cached = currentContext
        if (cached != null && signature == resolvedSignature) {
            return ResolveResult.Success(cached, currentWarnings)
        }
        val projectPath = project.basePath?.let { Path.of(it) } ?: Path.of(file.parent?.path ?: ".")
        val result = resolver.resolve(projectPath, Path.of(file.path), offset, text, options)
        if (result is ResolveResult.Success) {
            currentContext = result.context
            currentWarnings = result.warnings
            resolvedSignature = signature
        }
        return result
    }

    private fun contextSignature(file: VirtualFile, offset: Int, options: ResolverOptions): String {
        val manifest = try {
            resolver.findManifest(Path.of(file.path))
        } catch (_: Exception) {
            null
        }
        val manifestMtime = try {
            manifest?.let { Files.getLastModifiedTime(it).toMillis() } ?: 0L
        } catch (_: Exception) {
            0L
        }
        return listOf(
            file.path,
            file.modificationStamp,
            offset,
            options.toString(),
            manifestMtime,
        ).joinToString("|")
    }

    private fun requestKey(
        context: RustContext,
        kind: ArtifactKind,
        wholeCrate: Boolean,
        includeClosures: Boolean,
        wholeAsm: Boolean,
    ): String {
        val flags = when (kind) {
            ArtifactKind.MACRO -> "whole=$wholeCrate"
            ArtifactKind.MIR -> "closures=$includeClosures"
            ArtifactKind.ASM -> "whole=$wholeAsm"
        }
        return "${context.file}|${context.caretOffset}|${context.packageName}|${context.targetName}|" +
            "${context.targetKind}|${context.profile}|${context.targetTriple}|${context.features}|" +
            "${context.item?.kind}|${context.item?.qualifiedName}|${context.item?.implHeaderSpan?.start}|" +
            "${context.extraRustcArgs}|$kind|$flags"
    }

    private fun activeRequestKey(kind: ArtifactKind): String {
        val context = currentContext ?: return ""
        return requestKey(context, kind, wholeCrateCheck.isSelected, includeClosuresCheck.isSelected, wholeAsmCheck.isSelected)
    }

    private fun showCached(kind: ArtifactKind) {
        val key = activeRequestKey(kind)
        val cached = results[key] ?: return
        val tab = tabFor(kind)
        if (cached.emptyTitle.isNotBlank() && cached.content.isBlank()) {
            tab.showEmpty(cached.emptyTitle, cached.emptyHint)
        } else {
            tab.setContent(cached.content, cached.warnings)
        }
        status(cached.status)
    }

    private fun updateContextLabels(context: RustContext, warnings: List<String>) {
        currentContext = context
        currentWarnings = warnings
        val file = currentFile ?: return
        fileLabel.text = "${file.name}  ·  ${context.packageName}"
        fileLabel.toolTipText = context.file.toString()
        val item = context.item
        itemLabel.text = if (item == null) {
            "Whole target"
        } else {
            "${item.kind.name.lowercase().replaceFirstChar { it.uppercase() }}  ·  ${item.name}"
        }
        targetLabel.text = "Target  ·  ${context.displayTarget}"
        targetLabel.toolTipText = context.crateRoot.toString()

        updateProfileChoices(context)
        if (profileCombo.itemCount == 0 || profileCombo.selectedItem == null) {
            profileCombo.selectedItem = context.profile
        }
        updateConfigurationSummary()

        updatingTargets = true
        try {
            targetCombo.removeAllItems()
            context.availableTargets.forEach { targetCombo.addItem(it) }
            val selected = context.availableTargets.firstOrNull { it.key == context.displayTarget }
            if (selected != null) targetCombo.selectedItem = selected
            targetCombo.isEnabled = context.availableTargets.size > 1
        } finally {
            updatingTargets = false
        }
    }

    private fun showFailure(kind: ArtifactKind, message: String, details: String, command: String? = null) {
        status("Failed · ${kind.label()}")
        tabFor(kind).showEmpty(message, details.ifBlank { null })
        buildOutput.update(command, "Failed", listOf(message, details).filter { it.isNotBlank() }.joinToString("\n\n"))
        setOutputExpanded(true)
    }

    private fun copyCommand() {
        val plan = lastPlans[activeKind]
        val text = plan?.displayCommand ?: run {
            status("Run the view once to capture the command.")
            return
        }
        com.intellij.openapi.ide.CopyPasteManager.getInstance()
            .setContents(com.intellij.util.ui.TextTransferable(text as CharSequence))
        status("Command copied.")
    }

    private fun invalidateContext() {
        currentContext = null
        currentWarnings = emptyList()
        resolvedSignature = null
    }

    private fun markStale() {
        if (currentContext == null || disposed) return
        results.clear()
        status("Stale — file changed. Click Refresh.")
    }

    private fun selectTab(kind: ArtifactKind) {
        selectingTab = true
        try {
            navigationList.setSelectedValue(kind, false)
            activeKind = kind
            settings.lastTab = kind.name
            (viewCards.layout as CardLayout).show(viewCards, kind.name)
            updateViewHeader(kind)
        } finally {
            selectingTab = false
        }
    }

    private fun showWelcomeState() {
        val hint = "Open a Rust file and run Show Compiler Reader, or use the button below."
        macroTab.showEmpty("No context yet.", hint, "Use Current File") { openCurrentEditor() }
        mirTab.showEmpty("No context yet.", hint, "Use Current File") { openCurrentEditor() }
        asmTab.showEmpty("No context yet.", hint, "Use Current File") { openCurrentEditor() }
    }

    private fun openCurrentEditor() {
        val editorManager = FileEditorManager.getInstance(project)
        val file = ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
            editorManager.selectedFiles
                .firstOrNull { it.isValid && !it.isDirectory && it.extension?.equals("rs", ignoreCase = true) == true }
        }
        if (file == null) {
            status("Open a Rust file in the editor first.")
            return
        }
        val editor = editorManager.getSelectedTextEditor()
        val editorFile = editor?.let {
            ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
                FileDocumentManager.getInstance().getFile(it.document)
            }
        }
        val offset = if (editor != null && editorFile == file) editor.caretModel.offset else 0
        showContext(file, offset)
    }

    private fun updateFileLabel(file: VirtualFile) {
        fileLabel.text = file.name
        fileLabel.toolTipText = file.path
    }

    private fun documentText(file: VirtualFile): String = ReadAction.computeBlocking<String, RuntimeException> {
        try {
            FileDocumentManager.getInstance().getDocument(file)?.text
                ?: com.intellij.openapi.vfs.VfsUtilCore.loadText(file)
        } catch (_: Exception) {
            ""
        }
    }

    private fun relativeFileHint(context: RustContext): String? = try {
        context.workspaceRoot.relativize(context.file).toString()
    } catch (_: Exception) {
        null
    }

    private fun setOutputExpanded(expanded: Boolean) {
        outputExpanded = expanded
        outputBody.isVisible = expanded
        outputToggle.text = if (expanded) "▾ Build output" else "▸ Build output"
        outputPanel.revalidate()
        outputPanel.repaint()
    }

    private fun status(text: String) {
        statusLabel.text = text
        statusLabel.toolTipText = text
        statusLabel.icon = when {
            text.startsWith("Failed") -> AllIcons.General.Error
            text.startsWith("Stale") -> AllIcons.General.Warning
            text.startsWith("Ready") -> AllIcons.General.InspectionsOK
            else -> AllIcons.General.Information
        }
    }

    private fun currentTimeText(): String {
        val format = java.text.SimpleDateFormat("HH:mm:ss")
        return format.format(java.util.Date())
    }

    private fun invokeLaterSafely(action: () -> Unit) {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) action()
        }
    }

    private fun tabFor(kind: ArtifactKind): OutputTab = when (kind) {
        ArtifactKind.MACRO -> macroTab
        ArtifactKind.MIR -> mirTab
        ArtifactKind.ASM -> asmTab
    }

    private fun activeTab(): OutputTab = tabFor(activeKind)

    private fun kindFromName(name: String): ArtifactKind = when (name.uppercase()) {
        "MACRO", "MACRO EXPANSION" -> ArtifactKind.MACRO
        "ASM", "ASSEMBLY" -> ArtifactKind.ASM
        else -> ArtifactKind.MIR
    }

    override fun dispose() {
        disposed = true
        macroTab.dispose()
        mirTab.dispose()
        asmTab.dispose()
    }

    private class ViewNavigationRenderer(
        private val isExpanded: () -> Boolean,
    ) : ListCellRenderer<ArtifactKind> {
        override fun getListCellRendererComponent(
            list: JList<out ArtifactKind>?,
            value: ArtifactKind?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val foreground = if (isSelected) {
                UIUtil.getListSelectionForeground(cellHasFocus)
            } else {
                UIUtil.getListForeground()
            }
            val icon = when (value) {
                ArtifactKind.MACRO -> AllIcons.Actions.Preview
                ArtifactKind.MIR -> AllIcons.Nodes.Function
                ArtifactKind.ASM -> AllIcons.Actions.Compile
                null -> null
            }
            val subtitle = when (value) {
                ArtifactKind.MACRO -> "Rust expansion"
                ArtifactKind.MIR -> "Intermediate IR"
                ArtifactKind.ASM -> "Native code"
                null -> ""
            }
            if (!isExpanded()) {
                return JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(10, 4)
                    background = if (isSelected) {
                        UIUtil.getListSelectionBackground(cellHasFocus)
                    } else {
                        UIUtil.getPanelBackground()
                    }
                    isOpaque = true
                    add(JBLabel(icon).apply { this.foreground = foreground }, BorderLayout.CENTER)
                    toolTipText = value?.let { "${it.label()} — $subtitle" }
                }
            }

            val title = JBLabel(
                when (value) {
                    ArtifactKind.MACRO -> "Macro"
                    ArtifactKind.MIR -> "MIR"
                    ArtifactKind.ASM -> "Assembly"
                    null -> ""
                },
            ).apply {
                font = JBUI.Fonts.label(13f).asBold()
                this.foreground = foreground
            }
            val subtitleLabel = JBLabel(subtitle).apply {
                font = JBUI.Fonts.smallFont()
                this.foreground = if (isSelected) foreground else UIUtil.getContextHelpForeground()
            }
            val text = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(title)
                add(Box.createVerticalStrut(2))
                add(subtitleLabel)
            }
            return JPanel(BorderLayout(9, 0)).apply {
                border = JBUI.Borders.empty(9, 8)
                background = if (isSelected) {
                    UIUtil.getListSelectionBackground(cellHasFocus)
                } else {
                    UIUtil.getPanelBackground()
                }
                isOpaque = true
                add(JBLabel(icon).apply { this.foreground = foreground }, BorderLayout.WEST)
                add(text, BorderLayout.CENTER)
                toolTipText = value?.label()
            }
        }
    }

    private data class CachedResult(
        val content: String,
        val warnings: List<String>,
        val emptyTitle: String,
        val emptyHint: String?,
        val status: String,
    )

    companion object {
        const val TOOL_WINDOW_ID: String = "Rust Compiler Reader"

        private val LOG = Logger.getInstance(InspectorPanel::class.java)

        fun open(project: Project, file: VirtualFile, offset: Int) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
            if (toolWindow == null) {
                LOG.warn("Tool window '$TOOL_WINDOW_ID' is not registered")
                return
            }
            if (toolWindow.contentManager.contents.none { findPanel(it.component) != null }) {
                InspectorToolWindowFactory().createToolWindowContent(project, toolWindow)
            }
            toolWindow.activate(null)
            val panel = toolWindow.contentManager.contents
                .asSequence()
                .mapNotNull { findPanel(it.component) }
                .firstOrNull()
            if (panel == null) {
                LOG.warn("Inspector panel was not found in tool window '$TOOL_WINDOW_ID'")
                return
            }
            panel.showContext(file, offset)
        }

        private fun findPanel(component: Component?): InspectorPanel? {
            if (component is InspectorPanel) return component
            if (component is Container) {
                for (child in component.components) {
                    findPanel(child)?.let { return it }
                }
            }
            return null
        }
    }
}

private fun ArtifactKind.label(): String = when (this) {
    ArtifactKind.MACRO -> "Macro Expansion"
    ArtifactKind.MIR -> "MIR"
    ArtifactKind.ASM -> "Assembly"
}
