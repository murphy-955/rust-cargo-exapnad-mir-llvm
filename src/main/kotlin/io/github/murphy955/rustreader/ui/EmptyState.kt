package io.github.murphy955.rustreader.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.GridBagLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

object EmptyState {
    fun create(
        title: String,
        hint: String? = null,
        actionText: String? = null,
        action: (() -> Unit)? = null,
    ): JComponent {
        val titleLabel = JBLabel().apply {
            alignmentX = Component.CENTER_ALIGNMENT
            horizontalAlignment = SwingConstants.CENTER
            font = JBUI.Fonts.label(16f).asBold()
        }
        val hintLabel = JBLabel().apply {
            alignmentX = Component.CENTER_ALIGNMENT
            horizontalAlignment = SwingConstants.CENTER
            foreground = UIUtil.getContextHelpForeground()
        }
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(AllIcons.General.Information).apply {
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(12))
            add(titleLabel)
            if (!hint.isNullOrBlank()) {
                add(Box.createVerticalStrut(8))
                add(hintLabel)
            }
            if (actionText != null && action != null) {
                add(Box.createVerticalStrut(18))
                add(JButton(actionText).apply {
                    alignmentX = Component.CENTER_ALIGNMENT
                    addActionListener { action() }
                })
            }
        }
        val root = object : JPanel(GridBagLayout()) {
            var beforeLayout: () -> Unit = {}

            override fun doLayout() {
                beforeLayout()
                super.doLayout()
            }
        }.apply {
            border = JBUI.Borders.empty(20)
            add(
                content,
                java.awt.GridBagConstraints().apply {
                    weightx = 1.0
                    fill = java.awt.GridBagConstraints.HORIZONTAL
                },
            )
        }
        var currentTextWidth = -1

        fun updateText() {
            val availableWidth = if (root.width > 0) root.width - 48 else 320
            val textWidth = availableWidth.coerceIn(80, 420)
            if (textWidth == currentTextWidth) return
            currentTextWidth = textWidth
            titleLabel.text = html(title, textWidth, bold = true)
            hintLabel.text = html(hint.orEmpty(), textWidth)
            content.revalidate()
        }
        updateText()
        root.beforeLayout = ::updateText
        root.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                updateText()
            }
        })
        return root
    }

    private fun html(text: String, width: Int, bold: Boolean = false): String {
        val content = if (bold) "<b>${escape(text)}</b>" else escape(text)
        return "<html><div style='text-align:center;width:${width}px'>$content</div></html>"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")
}
