package io.github.murphy955.rustreader.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/**
 * Build output pane. Cargo diagnostics are rendered as HTML so `path:line:column` references
 * become clickable links.
 */
class BuildOutputView(
    private val project: Project,
    private val basePathProvider: () -> Path?,
) {
    private val pane = JEditorPane("text/html", "").apply {
        isEditable = false
        isOpaque = false
        background = UIUtil.getPanelBackground()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                openLink(event.description)
            }
        }
    }

    val component: JPanel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(pane), BorderLayout.CENTER)
    }

    fun update(command: String?, status: String?, output: String?) {
        val foreground = cssColor(UIUtil.getLabelForeground())
        val html = buildString {
            append("<html><body style='font-family:monospace;font-size:11px;color:$foreground'>")
            if (!command.isNullOrBlank()) {
                append("<b>Command</b><br><pre style='margin:4px 0'>").append(escape(command)).append("</pre>")
            }
            if (!status.isNullOrBlank()) {
                append("<b>Status</b>: ").append(escape(status)).append("<br><br>")
            }
            if (!output.isNullOrBlank()) {
                val truncated = if (output.length > MAX_OUTPUT_CHARS) output.take(MAX_OUTPUT_CHARS) + "\n... truncated ..." else output
                append("<pre style='margin:4px 0'>").append(linkify(escape(truncated))).append("</pre>")
            } else if (!command.isNullOrBlank() || !status.isNullOrBlank()) {
                append("<pre style='margin:4px 0'>(no output)</pre>")
            }
            append("</body></html>")
        }
        pane.text = html
        pane.caretPosition = 0
    }

    fun clear() {
        pane.text = ""
    }

    private fun openLink(description: String?) {
        if (description == null || !description.startsWith(LINK_PREFIX)) return
        val payload = description.removePrefix(LINK_PREFIX)
        val parts = payload.split(':')
        if (parts.size < 3) return
        val column = parts.last().toIntOrNull() ?: return
        val line = parts[parts.size - 2].toIntOrNull() ?: return
        val encodedPath = parts.dropLast(2).joinToString(":")
        val rawPath = try {
            URLDecoder.decode(encodedPath, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return
        }
        val path = try {
            val candidate = Path.of(rawPath)
            if (candidate.isAbsolute) candidate else basePathProvider()?.resolve(candidate) ?: candidate
        } catch (_: Exception) {
            return
        }
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(path.toString())
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())
            ?: return
        FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, virtualFile, (line - 1).coerceAtLeast(0), (column - 1).coerceAtLeast(0)),
            true,
        )
    }

    private fun linkify(escaped: String): String = pathPattern.replace(escaped) { match ->
        val path = match.groupValues[1]
        val line = match.groupValues[2]
        val column = match.groupValues[3]
        val encoded = URLEncoder.encode(path, StandardCharsets.UTF_8)
        "<a href=\"$LINK_PREFIX$encoded:$line:$column\">${match.value}</a>"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun cssColor(color: Color): String = "#%02x%02x%02x".format(color.red, color.green, color.blue)

    companion object {
        private const val LINK_PREFIX = "open:"
        private const val MAX_OUTPUT_CHARS = 500_000
        private val pathPattern = Regex("""((?:[A-Za-z]:)?[A-Za-z0-9_./\\-]+\.(?:rs|toml|mir|s|ll)):(\d+):(\d+)""")
    }
}
