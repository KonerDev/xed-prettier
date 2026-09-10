package com.koner.prettier

import com.koner.prettier.utils.getPrettierIcon
import com.rk.commands.EditorActionContext
import com.rk.commands.EditorCommand
import com.rk.editor.Editor
import com.rk.extension.ExtensionContext
import com.rk.tabs.editor.EditorTab
import io.github.rosemoe.sora.text.TextRange

class PrettierCommand(
    private val extensionContext: ExtensionContext,
    private val onFormat: (EditorTab, Editor, TextRange?) -> Unit,
) : EditorCommand() {
    override val id = "editor.prettier"

    override fun getLabel() = "Format with Prettier"

    override fun getIcon() = getPrettierIcon(extensionContext)

    override fun execute(context: EditorActionContext) {
        val range =
            context.editor.cursorRange.takeIf {
                context.editor.isTextSelected
            }
        onFormat(context.editorTab, context.editor, range)
    }
}
