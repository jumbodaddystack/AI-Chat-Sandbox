package com.aichat.sandbox.ui.components

import android.content.Context
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarkwonProvider @Inject constructor() {

    fun provide(context: Context, isDarkTheme: Boolean): Markwon {
        return Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(JLatexMathPlugin.create(42f) { builder ->
                builder.inlinesEnabled(true)
            })
            .build()
    }
}
