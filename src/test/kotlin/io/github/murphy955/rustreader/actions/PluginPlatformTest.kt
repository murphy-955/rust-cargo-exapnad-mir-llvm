package io.github.murphy955.rustreader.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.WrapLayout
import io.github.murphy955.rustreader.command.ArtifactKind
import io.github.murphy955.rustreader.settings.InspectorSettings
import io.github.murphy955.rustreader.ui.InspectorPanel
import io.github.murphy955.rustreader.ui.OutputTab
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JScrollPane
import javax.swing.JTabbedPane

class PluginPlatformTest : BasePlatformTestCase() {
    fun testRustFileDetection() {
        val file = myFixture.configureByText("main.rs", "fn main() {}").virtualFile
        assertTrue(ShowInspectorAction.isRustFile(file))
    }

    fun testNonRustFileDetection() {
        val file = myFixture.configureByText("note.txt", "hello").virtualFile
        assertFalse(ShowInspectorAction.isRustFile(file))
    }

    fun testActionIsEnabledForRustFiles() {
        val file = myFixture.configureByText("main.rs", "fn main() {}").virtualFile
        val action = ShowInspectorAction()
        val event = AnActionEvent.createFromAnAction(action, null, "test", dataContext(file))
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    fun testActionIsDisabledForNonRustFiles() {
        val file = myFixture.configureByText("note.txt", "hello").virtualFile
        val action = ShowInspectorAction()
        val event = AnActionEvent.createFromAnAction(action, null, "test", dataContext(file))
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    fun testActionFallsBackToSelectedEditorFile() {
        myFixture.configureByText("main.rs", "fn main() {}")
        val action = ShowInspectorAction()
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .build()
        val event = AnActionEvent.createFromAnAction(action, null, "test", context)
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    private fun dataContext(file: com.intellij.openapi.vfs.VirtualFile): DataContext =
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .build()

    fun testToolWindowIsRegistered() {
        val extension = com.intellij.openapi.wm.ToolWindowEP.EP_NAME.extensionList
            .firstOrNull { it.id == InspectorPanel.TOOL_WINDOW_ID }
        assertNotNull(extension)
        assertEquals(
            "io.github.murphy955.rustreader.ui.InspectorToolWindowFactory",
            extension!!.factoryClass,
        )
    }

    fun testSettingsServiceIsAvailable() {
        val settings = InspectorSettings.getInstance(project)
        settings.profile = "release"
        assertEquals("release", settings.profile)
    }

    fun testInspectorPanelCanBeCreated() {
        val panel = InspectorPanel(project)
        assertNotNull(panel)
        panel.dispose()
    }

    fun testToolWindowDoesNotOverlapWhenNarrow() {
        val panel = InspectorPanel(project)
        try {
            listOf(860, 520, 380).forEach { width ->
                panel.setSize(width, 600)
                layoutTree(panel)
                assertNoOverlaps(panel, "width=$width")
                assertChildrenFit(panel, "width=$width")
            }
        } finally {
            panel.dispose()
        }
    }

    fun testNavigationSwitchesCompilerViews() {
        val panel = InspectorPanel(project)
        try {
            val navigation = findComponents(panel, com.intellij.ui.components.JBList::class.java).single()
            assertEquals(3, navigation.model.size)
            assertEquals(ArtifactKind.MIR, navigation.selectedValue)
            assertTrue(findComponents(panel, JTabbedPane::class.java).isEmpty())

            navigation.setSelectedValue(ArtifactKind.ASM, false)

            assertEquals(ArtifactKind.ASM, navigation.selectedValue)
            assertEquals("ASM", InspectorSettings.getInstance(project).state.lastTab)
            assertTrue(
                findComponents(panel, com.intellij.ui.components.JBLabel::class.java)
                    .any { it.text == "Native assembly for the selected item" },
            )
        } finally {
            panel.dispose()
        }
    }

    fun testNavigationCanCollapseAndExpand() {
        val panel = InspectorPanel(project)
        try {
            val navigation = findComponents(panel, com.intellij.ui.components.JBList::class.java).single()
            val navigationPanel = navigation.parent as javax.swing.JComponent
            val toggle = findComponents(navigationPanel, JButton::class.java).single()
            assertEquals(52, navigationPanel.preferredSize.width)
            assertEquals("Expand compiler views", toggle.toolTipText)

            toggle.doClick()
            assertEquals(154, navigationPanel.preferredSize.width)
            assertEquals("Collapse compiler views", toggle.toolTipText)

            toggle.doClick()
            assertEquals(52, navigationPanel.preferredSize.width)
        } finally {
            panel.dispose()
        }
    }

    fun testBuildConfigurationCanCollapseAndExpand() {
        val panel = InspectorPanel(project)
        try {
            val profile = findComponents(panel, JComboBox::class.java)
                .first { it.itemCount > 0 && it.getItemAt(0) == "dev" }
            val configuration = profile.parent as Container
            val toggle = findComponents(panel, JButton::class.java)
                .first { it.text?.contains("Build configuration") == true }
            val refresh = findComponents(panel, JButton::class.java).first { it.text == "Refresh" }
            assertSame(refresh.parent, toggle.parent)
            assertFalse(configuration.isVisible)

            toggle.doClick()
            assertTrue(configuration.isVisible)
            profile.selectedItem = "release"
            assertTrue(toggle.text.contains("release"))

            panel.setSize(380, 600)
            layoutTree(panel)
            assertNoOverlaps(panel, "expanded configuration")
            assertChildrenFit(panel, "expanded configuration")

            toggle.doClick()
            assertFalse(configuration.isVisible)
        } finally {
            panel.dispose()
        }
    }

    fun testProfileComboFitsLongestName() {
        val panel = InspectorPanel(project)
        try {
            val profile = findComponents(panel, JComboBox::class.java)
                .first { it.itemCount > 0 && it.getItemAt(0) == "dev" }
            val toggle = findComponents(panel, JButton::class.java)
                .first { it.text?.contains("Build configuration") == true }
            toggle.doClick()

            listOf("release", "maxopt").forEach { name ->
                profile.selectedItem = name
                panel.setSize(380, 600)
                layoutTree(panel)
                val textWidth = profile.getFontMetrics(profile.font).stringWidth(name)
                assertTrue(
                    "$name is clipped: combo=${profile.width}px text=${textWidth}px",
                    profile.width >= 120 && profile.width >= textWidth + 50,
                )
            }
        } finally {
            panel.dispose()
        }
    }

    fun testTypedCustomProfileIsCommitted() {
        val panel = InspectorPanel(project)
        try {
            val profile = findComponents(panel, JComboBox::class.java)
                .first { it.itemCount > 0 && it.getItemAt(0) == "dev" }
            profile.editor.item = "maxopt"
            profile.actionPerformed(
                java.awt.event.ActionEvent(profile, java.awt.event.ActionEvent.ACTION_PERFORMED, "comboBoxChanged"),
            )
            assertEquals("maxopt", InspectorSettings.getInstance(project).profile)
        } finally {
            panel.dispose()
        }
    }

    fun testEmptyStateFitsNarrowPanel() {
        val empty = io.github.murphy955.rustreader.ui.EmptyState.create(
            "No context yet.",
            "Open a Rust file and run Show Compiler Reader, or use the button below.",
            "Use Current File",
        ) {}
        empty.setSize(180, 300)
        layoutTree(empty)
        assertChildrenFit(empty as Container, "empty state")
    }

    fun testOutputToolbarDoesNotOverlapWhenNarrow() {
        val tab = OutputTab(project, ArtifactKind.MIR)
        try {
            listOf(860, 520, 380).forEach { width ->
                tab.root.setSize(width, 400)
                layoutTree(tab.root)
                val top = tab.root.getComponent(0) as Container
                val toolbar = top.components.filterIsInstance<Container>()
                    .first { it.layout is WrapLayout }
                assertNoOverlaps(toolbar, "output width=$width")
                assertChildrenFit(toolbar, "output width=$width")
            }
        } finally {
            tab.dispose()
        }
    }

    fun testWarningIsLeftAlignedAndCompact() {
        val tab = OutputTab(project, ArtifactKind.MIR)
        try {
            tab.setWarnings(listOf("first warning", "second warning", "third warning"))
            tab.root.setSize(1000, 400)
            layoutTree(tab.root)
            val top = tab.root.getComponent(0) as Container
            val warning = top.components.filterIsInstance<com.intellij.ui.components.JBLabel>()
                .first { it.isVisible }
            assertEquals(
                "x=${warning.x} w=${warning.width} ax=${warning.alignmentX} top=${top.width}",
                0,
                warning.x,
            )
            assertTrue("warning should not exceed the panel", warning.width <= top.width)
            assertTrue("warning should stay on one line", warning.text.length < 200 && warning.height < 60)
        } finally {
            tab.dispose()
        }
    }

    fun testSaveCreatesNewFileWithContent() {
        val tab = OutputTab(project, ArtifactKind.MIR)
        val directory = java.nio.file.Files.createTempDirectory("rust-reader-save")
        val target = directory.resolve("output.mir")
        try {
            tab.setContent("fn saved() -> u32 {\n    42\n}\n")
            assertFalse(java.nio.file.Files.exists(target))

            assertTrue(
                tab.saveContentTo(com.intellij.openapi.vfs.VirtualFileWrapper(target.toFile())),
            )
            assertEquals(
                "fn saved() -> u32 {\n    42\n}\n",
                java.nio.file.Files.readString(target),
            )
        } finally {
            tab.dispose()
            java.nio.file.Files.deleteIfExists(target)
            java.nio.file.Files.deleteIfExists(directory)
        }
    }

    fun testNextMatchMovesCaretAndScrolls() {
        val tab = OutputTab(project, ArtifactKind.MIR)
        try {
            val text = (0..240).joinToString("\n") { index ->
                if (index == 200) "needle here" else "line $index"
            }
            tab.setContent(text)
            tab.root.setSize(600, 320)
            layoutTree(tab.root)

            val search = findComponents(tab.root, com.intellij.ui.components.JBTextField::class.java).single()
            search.text = "needle"
            val next = findComponents(tab.root, JButton::class.java)
                .first { it.toolTipText == "Next match" }
            next.doClick()
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

            assertEquals(text.indexOf("needle") + "needle".length, tab.caretOffset())
            assertTrue(
                "editor should scroll to the match, offset=${tab.verticalScrollOffset()}",
                tab.verticalScrollOffset() > 0,
            )
        } finally {
            tab.dispose()
        }
    }

    fun testAssemblyAndMacroOutputAreHighlighted() {
        val asm = OutputTab(project, ArtifactKind.ASM)
        val macro = OutputTab(project, ArtifactKind.MACRO)
        try {
            asm.setContent(".globl main\nmain:\n  movq %rsp, %rbp\n  ret\n")
            macro.setContent("fn main() {\n    let x: i32 = 1;\n}\n")

            assertTrue(
                "assembly output should use the ASM highlighter, got ${asm.tokenTypeAt(0)}",
                asm.tokenTypeAt(0)?.startsWith("AsmTokenType.") == true,
            )
            assertEquals(
                "macro expansion should use the Rust lexer for the fn keyword",
                "fn",
                macro.tokenTypeAt(0),
            )
        } finally {
            asm.dispose()
            macro.dispose()
        }
    }

    private fun layoutTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutTree)
        }
    }

    private fun <T : Component> findComponents(component: Component, type: Class<T>): List<T> {
        val result = mutableListOf<T>()
        if (type.isInstance(component)) result.add(type.cast(component))
        if (component is Container) {
            component.components.forEach { child -> result.addAll(findComponents(child, type)) }
        }
        return result
    }

    private fun assertNoOverlaps(container: Container, context: String) {
        val children = container.components.filter { it.isVisible }
        for (i in children.indices) {
            for (j in i + 1 until children.size) {
                val first = children[i]
                val second = children[j]
                assertFalse(
                    "$context: ${first.javaClass.simpleName} overlaps ${second.javaClass.simpleName}",
                    first.bounds.intersects(second.bounds),
                )
            }
        }
        children.filterIsInstance<Container>()
            .filterNot { it is JComboBox<*> || it is JScrollPane || it is JTabbedPane }
            .forEach { assertNoOverlaps(it, context) }
    }

    private fun assertChildrenFit(container: Container, context: String) {
        val children = container.components.filter { it.isVisible }
        children.forEach { child ->
            assertTrue(
                "$context: ${child.javaClass.simpleName} bounds=${child.bounds} " +
                    "preferred=${child.preferredSize} extends past ${container.javaClass.simpleName} " +
                    "bounds=${container.bounds} layout=${container.layout}",
                child.x + child.width <= container.width + 1,
            )
        }
        children.filterIsInstance<Container>()
            .filterNot { it is JComboBox<*> || it is JScrollPane || it is JTabbedPane }
            .forEach { assertChildrenFit(it, context) }
    }

    fun testSettingsConfigurableScrollsAllSections() {
        val configurable = io.github.murphy955.rustreader.settings.InspectorSettingsConfigurable(project)
        try {
            val component = configurable.createComponent()
            assertTrue("settings should use a scroll pane, got ${component.javaClass.name}", component is JScrollPane)
            val scroll = component as JScrollPane
            assertEquals(
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                scroll.verticalScrollBarPolicy,
            )
            assertTrue(
                "settings form should be taller than the visible viewport",
                scroll.viewport.view.preferredSize.height > 400,
            )
        } finally {
            configurable.disposeUIResources()
        }
    }
}
