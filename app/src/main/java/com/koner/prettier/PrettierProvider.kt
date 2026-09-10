package com.koner.prettier

import com.koner.prettier.utils.PRETTIER_EXTENSIONS
import com.koner.prettier.utils.getPrettierIcon
import com.rk.editor.FormatterProvider
import com.rk.extension.ExtensionContext
import com.rk.file.FileObject
import io.github.rosemoe.sora.lang.format.Formatter
import java.io.File

class PrettierProvider(
    private val context: ExtensionContext,
    private val settings: PrettierSettings,
    private val binary: File,
) : FormatterProvider() {
    override val id = "prettier"
    override val label = "Prettier"
    override val supportedExtensions = PRETTIER_EXTENSIONS

    override val icon
        get() = getPrettierIcon(context)

    override fun getFormatter(fileObject: FileObject?): Formatter? {
        if (fileObject == null) return null
        return PrettierFormatter(context, settings, binary, fileObject)
    }
}
