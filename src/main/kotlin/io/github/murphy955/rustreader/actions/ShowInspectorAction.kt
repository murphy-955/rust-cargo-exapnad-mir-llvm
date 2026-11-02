package io.github.murphy955.rustreader.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.murphy955.rustreader.ui.InspectorPanel

class ShowInspectorAction : AnAction(
    "Show Compiler Reader",
    "Show macro expansion, MIR and assembly for the current item",
    AllIcons.Nodes.Console,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: selectedRustFile(project)
        event.presentation.isEnabled = project != null && isRustFile(file)
        event.presentation.isVisible = true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: selectedRustFile(project) ?: return
        if (!isRustFile(file)) return
        val editor = event.getData(CommonDataKeys.EDITOR)
            ?: FileEditorManager.getInstance(project).getSelectedTextEditor()
        val editorFile = editor?.let {
            ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
                FileDocumentManager.getInstance().getFile(it.document)
            }
        }
        val offset = if (editor != null && editorFile == file) editor.caretModel.offset else 0
        InspectorPanel.open(project, file, offset)
    }

    companion object {
        fun isRustFile(file: VirtualFile?): Boolean =
            file != null && !file.isDirectory && file.extension?.equals("rs", ignoreCase = true) == true

        private fun selectedRustFile(project: Project?): VirtualFile? =
            project?.let {
                ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
                    FileEditorManager.getInstance(it).selectedFiles.firstOrNull(::isRustFile)
                }
            }
    }
}
