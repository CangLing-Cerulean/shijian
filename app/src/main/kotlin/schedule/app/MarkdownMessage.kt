package schedule.app

import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

internal fun chatMarkdown(context: android.content.Context): Markwon = Markwon.builder(context)
    .usePlugin(TablePlugin.create(context))
    .usePlugin(StrikethroughPlugin.create())
    .usePlugin(TaskListPlugin.create(context))
    .build()

@Composable internal fun MarkdownMessage(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember(context) { chatMarkdown(context) }
    val rendered = remember(renderer, markdown) { renderer.toMarkdown(markdown) }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val fontSize = MaterialTheme.typography.bodyMedium.fontSize.value
    AndroidView(modifier = modifier, factory = { TextView(it).apply {
        setTextIsSelectable(true)
        setPadding(0, 0, 0, 0)
        setLineSpacing(0f, 1.15f)
    } }, update = { view ->
        view.setTextColor(foreground)
        view.setLinkTextColor(link)
        view.textSize = fontSize
        // Keep the TextView layout and selection stable during unrelated Compose updates.
        if (view.tag !== rendered) {
            renderer.setParsedMarkdown(view, rendered)
            view.tag = rendered
        }
    })
}
