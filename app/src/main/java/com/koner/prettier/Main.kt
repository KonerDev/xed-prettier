package com.koner.prettier

import android.os.Build
import androidx.annotation.Keep
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.rk.commands.CommandProvider
import com.rk.components.BasicToggle
import com.rk.components.InfoBlock
import com.rk.components.PreferenceList
import com.rk.components.PreferenceSingleInput
import com.rk.components.SettingsItem
import com.rk.components.SmoothValueSlider
import com.rk.components.SteppedValueSlider
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.editor.Editor
import com.rk.editor.Formatters
import com.rk.exec.ShellUtils
import com.rk.extension.ActivityProvider
import com.rk.extension.ExtensionAPI
import com.rk.extension.ExtensionContext
import com.rk.file.child
import com.rk.tabs.editor.EditorTab
import com.rk.utils.dialog
import io.github.rosemoe.sora.event.EditorFormatEvent
import io.github.rosemoe.sora.text.TextRange
import io.github.rosemoe.sora.widget.subscribeEvent
import java.io.File

@Keep
@Suppress("unused")
class Main(context: ExtensionContext) : ExtensionAPI(context) {

    private val settings = PrettierSettings(context)

    private var provider: PrettierProvider? = null
    private var command: PrettierCommand? = null

    private val armBinary = File(context.extension.installPath).child("bin/prettier-linux-arm64")
    private val x64Binary = File(context.extension.installPath).child("bin/prettier-linux-x64")

    override fun onLoad() {
        val binary = resolveBinary() ?: return

        val provider =
            PrettierProvider(context, settings, binary).also {
                this.provider = it
            }
        Formatters.registerFormatter(provider)

        command =
            PrettierCommand(context) { editorTab, editor, textRange ->
                    format(editorTab, editor, provider, textRange)
                }
                .also { CommandProvider.registerCommand(it) }
    }

    private fun format(
        editorTab: EditorTab,
        editor: Editor,
        provider: PrettierProvider,
        range: TextRange? = null,
    ) {
        val file = editorTab.file ?: return
        if (file.getExtension() !in provider.supportedExtensions) {
            return
        }

        editorTab.registerTask("${context.extension.id}.formatting")
        editor.subscribeEvent<EditorFormatEvent> { event, subscription ->
            subscription.unsubscribe()
            editorTab.unregisterTask("${context.extension.id}.formatting")
        }

        val formatContent =
            editor.text.copyText(false).apply {
                isUndoEnabled = false
            }
        provider.getFormatter(file)?.apply {
            setReceiver(editor)
            if (range != null) {
                formatRegion(formatContent, range, editor.cursorRange)
            } else {
                format(formatContent, editor.cursorRange)
            }
        }
        editor.postInvalidate()
    }

    override fun onDispose() {
        provider?.let {
            Formatters.unregisterFormatter(it)
        }
        command?.let {
            CommandProvider.unregisterCommand(it)
        }
    }

    override fun afterUpdate() {
        organize()
    }

    override fun onInstalled() {
        organize()
    }

    private fun organize() {
        val binary = resolveBinary() ?: return

        val unused = if (binary == armBinary) x64Binary else armBinary

        if (!unused.delete()) {
            context.logError("Failed to delete unused binary: ${unused.absolutePath}")
        }

        if (!binary.setExecutable(true)) {
            context.logError("Failed to make Prettier binary executable: ${binary.absolutePath}")
        }
    }

    private fun resolveBinary(): File? {
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull()

        if (deviceAbi == "arm64-v8a") {
            return armBinary
        } else if (deviceAbi == "x86_64") {
            return x64Binary
        }

        ActivityProvider.currentActivity?.let {
            dialog(
                activity = it,
                title = context.extension.name,
                msg = "This extension is not supported on your device (ABI $deviceAbi).",
            )
        }
        return null
    }

    @Composable
    override fun SettingsContent() {
        val useEditorDefault = settings.useEditorDefault
        val binary = resolveBinary()

        InfoBlock(
            icon = { Icon(imageVector = Icons.Outlined.Warning, contentDescription = null) },
            text =
                "These settings are only used when no Prettier configuration file exists " +
                    "(.prettierrc, prettier.config.js). " +
                    "EditorConfig may override settings such as indentation and line endings when enabled.",
        )

        if (binary == null) {
            InfoBlock(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                    )
                },
                text = "Prettier binary is not available for this device.",
                warning = true,
            )
        } else if (!binary.exists()) {
            InfoBlock(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                    )
                },
                text = "Prettier binary not found: ${binary.name}",
                warning = true,
            )
        } else {
            PreferenceGroup(heading = "Installation") {
                SettingsItem(
                    label = "Binary",
                    description = binary.name,
                    default = false,
                    showSwitch = false,
                )

                SettingsItem(
                    label = "Architecture",
                    description = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown",
                    default = false,
                    showSwitch = false,
                )

                SettingsItem(
                    label = "Permission",
                    description = if (binary.canExecute()) "Executable" else "Not executable",
                    default = false,
                    showSwitch = false,
                )

                val version by
                    produceState("Checking...") {
                        val result =
                            ShellUtils.runUbuntu(
                                command = arrayOf(binary.absolutePath, "--version"),
                                timeoutSeconds = 3,
                            )
                        if (!result.timedOut && result.exitCode == 0) {
                            value = result.output
                        }
                    }

                SettingsItem(
                    label = "Version",
                    description = version,
                    default = false,
                    showSwitch = false,
                )
            }
        }

        PreferenceGroup(heading = "Indentation") {
            BasicToggle(
                label = "Use editor defaults",
                description = "Use the default indentation settings of the editor",
                checked = useEditorDefault,
                onSwitch = {
                    settings.useEditorDefault = it
                },
            )

            SteppedValueSlider(
                label = "Tab width",
                description = "Number of spaces per indentation level",
                min = 1,
                max = 16,
                enabled = !useEditorDefault,
                default = settings.tabWidth,
            ) {
                settings.tabWidth = it
            }

            BasicToggle(
                label = context.appResources.getString("use_tabs") ?: "Insert tab character",
                description =
                    context.appResources.getString("use_tabs_desc")
                        ?: "Use a real tab (\\t) instead of spaces for indentation",
                checked = settings.useTabs,
                enabled = !useEditorDefault,
                onSwitch = {
                    settings.useTabs = it
                },
            )
        }

        PreferenceGroup(heading = "JavaScript / TypeScript") {
            BasicToggle(
                label = "Semicolons",
                description = "Add semicolons where required",
                checked = settings.semicolon,
                onSwitch = {
                    settings.semicolon = it
                },
            )

            BasicToggle(
                label = "Single quotes",
                description = "Prefer single quotes instead of double quotes",
                checked = settings.singleQuote,
                onSwitch = {
                    settings.singleQuote = it
                },
            )

            BasicToggle(
                label = "JSX single quotes",
                description = "Use single quotes in JSX attributes",
                checked = settings.jsxSingleQuote,
                onSwitch = {
                    settings.jsxSingleQuote = it
                },
            )

            PreferenceList(
                label = "Quote properties",
                description = "When to add quotes around object properties",
                items =
                    listOf(
                        "as-needed" to "As needed",
                        "consistent" to "Consistent",
                        "preserve" to "Preserve",
                    ),
                selectedItem = settings.quoteProps,
                onItemSelected = {
                    settings.quoteProps = it
                },
            )

            PreferenceList(
                label = "Trailing comma",
                description = null,
                items =
                    listOf(
                        "all" to "All",
                        "es5" to "ES5",
                        "none" to "None",
                    ),
                selectedItem = settings.trailingComma,
                onItemSelected = {
                    settings.trailingComma = it
                },
            )

            PreferenceList(
                label = "Arrow parentheses",
                description = null,
                items =
                    listOf(
                        "always" to "Always",
                        "avoid" to "Avoid when possible",
                    ),
                selectedItem = settings.arrowParens,
                onItemSelected = {
                    settings.arrowParens = it
                },
            )
        }

        PreferenceGroup(heading = "HTML") {
            BasicToggle(
                label = "Single attribute per line",
                description = "Put each attribute on its own line",
                checked = settings.singleAttributePerLine,
                onSwitch = {
                    settings.singleAttributePerLine = it
                },
            )

            BasicToggle(
                label = "Bracket same line",
                description = "Put closing brackets of multiline elements on the same line",
                checked = settings.bracketSameLine,
                onSwitch = {
                    settings.bracketSameLine = it
                },
            )
        }

        PreferenceGroup(heading = "Layout") {
            SmoothValueSlider(
                label = "Print width",
                description = "Maximum line length before wrapping",
                min = 40,
                max = 200,
                default = settings.printWidth,
            ) {
                settings.printWidth = it
            }

            BasicToggle(
                label = "Bracket spacing",
                description = "Add spaces inside object braces",
                checked = settings.bracketSpacing,
                onSwitch = {
                    settings.bracketSpacing = it
                },
            )

            BasicToggle(
                label = "Preserve object wrapping",
                description = "Keep existing object wrapping when formatting",
                checked = settings.preserveObjectWrap,
                onSwitch = {
                    settings.preserveObjectWrap = it
                },
            )
        }

        PreferenceGroup(heading = "Advanced") {
            PreferenceSingleInput(
                value = settings.customArgs,
                onConfirm = {
                    settings.customArgs = it
                },
                label = "Additional arguments",
                description =
                    "Custom Prettier CLI arguments appended to the formatter command. Use this for plugins or advanced options.",
                validate = {
                    val valid =
                        it.trim().split(" ").all { arg ->
                            arg.startsWith("--")
                        }
                    if (!valid) {
                        "Invalid arguments. Please use the format `--arg=value`."
                    } else {
                        null
                    }
                },
            )
        }
    }
}
