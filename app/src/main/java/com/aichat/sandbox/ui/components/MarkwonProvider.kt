package com.aichat.sandbox.ui.components

import android.content.Context
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarkwonProvider @Inject constructor() {

    fun provide(context: Context, isDarkTheme: Boolean): Markwon {
        val prism4j = Prism4j(MarkdownGrammarLocator())
        val prism4jTheme: Prism4jTheme = if (isDarkTheme) {
            Prism4jThemeDarkula.create()
        } else {
            Prism4jThemeDefault.create()
        }

        return Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(SyntaxHighlightPlugin.create(prism4j, prism4jTheme))
            .usePlugin(JLatexMathPlugin.create(42f) { builder ->
                builder.inlinesEnabled(true)
            })
            .build()
    }
}
