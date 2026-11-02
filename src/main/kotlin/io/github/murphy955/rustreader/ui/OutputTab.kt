package io.github.murphy955.rustreader.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.icons.AllIcons
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import io.github.murphy955.rustreader.command.ArtifactKind
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

class OutputTab(
    private val project: Project,
    val kind: ArtifactKind,
) {
    private val fileType: FileType = when (kind) {
        ArtifactKind.MACRO -> FileTypeManager.getInstance().getFileTypeByExtension("rs")
        ArtifactKind.ASM -> FileTypeManager.getInstance().getFileTypeByExtension("asm")
        ArtifactKind.MIR -> PlainTextFileType.INSTANCE
    }
    private val editorView = OutputEditor(project, fileType)
    private val emptyPanel = JPanel(BorderLayout())
    private val cards = JPanel(CardLayout())
    private val warningLabel = JBLabel(AllIcons.General.Warning).apply {
        isVisible = false
        alignmentX = Component.LEFT_ALIGNMENT
        horizontalAlignment = SwingConstants.LEFT
        iconTextGap = 7
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 10),
        )
    }
    private val searchField = JBTextField(16).apply {
        emptyText.text = "Find in output"
        toolTipText = "Find text in the generated output"
    }
    private val resultStatsLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val toolbar = JPanel(WrapLayout(FlowLayout.LEFT, 7, 3)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private var optionCount = 0
    private var contentText: String = ""

    val root: JComponent

    init {
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        top.add(warningLabel)

        toolbar.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 8),
        )
        searchField.addActionListener { find(forward = true) }
        toolbar.add(resultStatsLabel)
        toolbar.add(searchField)
        toolbar.add(searchButton(AllIcons.Actions.Back, "Previous match", forward = false))
        toolbar.add(searchButton(AllIcons.Actions.Forward, "Next match", forward = true))
        toolbar.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                toolbar.revalidate()
            }
        })
        top.add(toolbar)

        cards.add(editorView.component, CARD_CONTENT)
        cards.add(emptyPanel, CARD_EMPTY)

        root = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(cards, BorderLayout.CENTER)
        }
        showEmpty("Not loaded yet.", "Select Refresh to generate this view.")
    }

    fun addOption(checkbox: JCheckBox) {
        toolbar.add(checkbox, optionCount++)
        toolbar.revalidate()
    }

    fun addOption(component: JComponent) {
        toolbar.add(component, optionCount++)
        toolbar.revalidate()
    }

    fun setContent(text: String, warnings: List<String> = emptyList()) {
        val truncated = if (text.length > MAX_DISPLAY_CHARS) {
            text.take(MAX_DISPLAY_CHARS) + "\n\n... output truncated at $MAX_DISPLAY_CHARS characters ..."
        } else {
            text
        }
        contentText = truncated
        val lines = if (truncated.isEmpty()) 0 else truncated.count { it == '\n' } + 1
        resultStatsLabel.text = "$lines ${if (lines == 1) "line" else "lines"}"
        resultStatsLabel.toolTipText = "${truncated.length} characters"
        editorView.setText(truncated)
        setWarnings(warnings)
        (cards.layout as CardLayout).show(cards, CARD_CONTENT)
    }

    fun showEmpty(
        title: String,
        hint: String? = null,
        actionText: String? = null,
        action: (() -> Unit)? = null,
    ) {
        contentText = ""
        resultStatsLabel.text = ""
        resultStatsLabel.toolTipText = null
        emptyPanel.removeAll()
        emptyPanel.add(EmptyState.create(title, hint, actionText, action), BorderLayout.CENTER)
        emptyPanel.revalidate()
        emptyPanel.repaint()
        setWarnings(emptyList())
        (cards.layout as CardLayout).show(cards, CARD_EMPTY)
    }

    fun setWarnings(warnings: List<String>) {
        if (warnings.isEmpty()) {
            warningLabel.isVisible = false
            warningLabel.text = ""
            warningLabel.toolTipText = null
        } else {
            warningLabel.isVisible = true
            val first = warnings.first()
            val summary = if (first.length > 120) first.take(120).trimEnd() + "…" else first
            val more = if (warnings.size > 1) " (+${warnings.size - 1} more)" else ""
            warningLabel.text = summary + more
            warningLabel.toolTipText = "<html>${warnings.joinToString("<br>") { escape(it) }}</html>"
        }
    }

    fun content(): String = contentText

    internal fun caretOffset(): Int = editorView.caretOffset()

    internal fun verticalScrollOffset(): Int = editorView.verticalScrollOffset()

    internal fun tokenTypeAt(offset: Int): String? {
        val iterator = editorView.highlighter().createIterator(offset)
        if (iterator.atEnd()) return null
        return iterator.tokenType?.toString()
    }

    fun saveToFile() {
        if (contentText.isEmpty()) return
        val suggestedName = suggestedFileName()
        val descriptor = FileSaverDescriptor(
            "Save Output",
            "Save the generated output to a file",
            suggestedName.substringAfterLast('.', "txt"),
        )
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val target = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, suggestedName) ?: return
        if (!saveContentTo(target)) {
            Messages.showErrorDialog(project, "Could not create or write the selected file.", "Save Output")
        }
    }

    internal fun saveContentTo(target: com.intellij.openapi.vfs.VirtualFileWrapper): Boolean {
        if (contentText.isEmpty()) return false
        val file = target.file
        return try {
            file.parentFile?.mkdirs()
            file.writeText(contentText, Charsets.UTF_8)
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openInEditor() {
        if (contentText.isEmpty()) return
        ApplicationManager.getApplication().invokeLater {
            val virtualFile = LightVirtualFile(suggestedFileName(), fileType, contentText)
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, virtualFile), true)
        }
    }

    fun dispose() {
        editorView.dispose()
    }

    private fun suggestedFileName(): String = when (kind) {
        ArtifactKind.MACRO -> "macro-expansion.rs"
        ArtifactKind.MIR -> "output.mir"
        ArtifactKind.ASM -> "output.s"
    }

    private fun searchButton(icon: javax.swing.Icon, tooltip: String, forward: Boolean): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            addActionListener { find(forward) }
        }

    private fun find(forward: Boolean) {
        val query = searchField.text
        if (query.isEmpty() || contentText.isEmpty()) return
        val text = editorView.text()
        val selection = editorView.selection()
        var index = if (forward) {
            text.indexOf(query, selection?.selectionEnd ?: 0)
        } else {
            val from = (selection?.selectionStart ?: text.length) - 1
            if (from < 0) -1 else text.lastIndexOf(query, from)
        }
        if (index < 0) {
            index = if (forward) text.indexOf(query) else text.lastIndexOf(query)
        }
        if (index < 0) return
        editorView.select(index, index + query.length)
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private class OutputEditor(project: Project, fileType: FileType) {
        private val editor: EditorEx

        init {
            val document: Document = EditorFactory.getInstance().createDocument("")
            editor = EditorFactory.getInstance().createEditor(document, project, fileType, true) as EditorEx
            editor.settings.apply {
                isLineNumbersShown = true
                isFoldingOutlineShown = true
                isCaretRowShown = true
                isUseSoftWraps = false
                additionalLinesCount = 4
                additionalColumnsCount = 2
            }
            editor.component.border = BorderFactory.createEmptyBorder()
        }

        val component: JComponent
            get() = editor.component

        fun setText(text: String) {
            ApplicationManager.getApplication().runWriteAction {
                if (editor.document.text != text) {
                    editor.document.setText(text)
                }
                editor.caretModel.moveToOffset(0)
                editor.scrollingModel.scrollVertically(0)
            }
        }

        fun text(): String = editor.document.text

        fun highlighter() = editor.highlighter

        fun selection(): com.intellij.openapi.editor.SelectionModel? = editor.selectionModel

        fun caretOffset(): Int = editor.caretModel.offset

        fun verticalScrollOffset(): Int = editor.scrollingModel.verticalScrollOffset

        fun select(start: Int, end: Int) {
            editor.selectionModel.setSelection(start, end)
            editor.caretModel.moveToOffset(end)
            val point = editor.visualPositionToXY(editor.offsetToVisualPosition(start))
            val visibleArea = editor.scrollingModel.visibleArea
            editor.scrollPane.verticalScrollBar.value = (point.y - visibleArea.height / 2).coerceAtLeast(0)
            editor.scrollPane.horizontalScrollBar.value = (point.x - visibleArea.width / 2).coerceAtLeast(0)
        }

        fun dispose() {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    companion object {
        private const val CARD_CONTENT = "content"
        private const val CARD_EMPTY = "empty"
        private const val MAX_DISPLAY_CHARS = 4_000_000
    }
}
