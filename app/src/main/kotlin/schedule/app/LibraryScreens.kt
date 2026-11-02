package schedule.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import schedule.study.SavedSummary
import androidx.activity.compose.BackHandler

@Composable fun ConversationHistory(model: AppModel, modifier: Modifier, onBack: () -> Unit, onOpen: (String) -> Unit) {
    BackHandler(onBack = onBack)
    var selected by remember { mutableStateOf(setOf<String>()) }
    Column(modifier.padding(horizontal = 20.dp)) {
        PageTitle("聊天记录", onBack)
        Row {
            TextButton(onClick = { selected = model.conversations.map { it.id }.toSet() }) { Text("全选") }
            TextButton(onClick = { model.deleteConversations(selected); selected = emptySet() }, enabled = selected.isNotEmpty() && !model.chatBusy) { Text("删除所选 (${selected.size})") }
            TextButton(onClick = { model.deleteCurrentConversation(); onBack() }, enabled = !model.agentRunning && !model.chatBusy) { Text("删除当前") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.conversations.isEmpty()) item { Text("暂无历史对话") }
            items(model.conversations, key = { it.id }) { conversation ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp)) {
                        Checkbox(conversation.id in selected, { selected = if (it) selected + conversation.id else selected - conversation.id })
                        Column(Modifier.weight(1f).clickable(enabled = !model.chatBusy) { onOpen(conversation.id) }.padding(8.dp)) {
                            Text(conversation.title, maxLines = 2)
                            Text(dateTimeText(conversation.updatedAt, "Asia/Shanghai"), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { model.deleteConversations(setOf(conversation.id)) }, enabled = !model.chatBusy) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable fun SummaryLibrary(model: AppModel, onBack: () -> Unit) {
    var opened by remember { mutableStateOf<SavedSummary?>(null) }
    LaunchedEffect(Unit) { model.reloadLibrary() }
    opened?.let { summary ->
        SummaryReader(summary, onBack = { opened = null }, onDelete = { model.deleteSummaries(setOf(summary.id)); opened = null })
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        PageTitle("课程总结", onBack)
        SummaryList(model, model.summaries, Modifier.weight(1f), onOpen = { opened = it })
    }
}

@Composable fun SummaryList(model: AppModel, entries: List<SavedSummary>, modifier: Modifier, onOpen: (SavedSummary) -> Unit) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    Column(modifier) {
            Row {
                TextButton(onClick = { selected = entries.map { it.id }.toSet() }) { Text("全选") }
                TextButton(onClick = { model.deleteSummaries(selected); selected = emptySet() }, enabled = selected.isNotEmpty()) { Text("删除所选 (${selected.size})") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.isEmpty()) item { Text("暂无课程总结") }
                items(entries, key = { it.id }) { entry ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp)) {
                            Checkbox(entry.id in selected, { selected = if (it) selected + entry.id else selected - entry.id })
                            Column(Modifier.weight(1f).clickable { onOpen(entry) }.padding(8.dp)) {
                                Text(entry.courseName)
                                Text(entry.lessonStart?.let { dateTimeText(it, "Asia/Shanghai") } ?: "课件总结 · 上课时间未指定", style = MaterialTheme.typography.labelMedium)
                                Text("生成 ${dateTimeText(entry.createdAt, "Asia/Shanghai")}", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { model.deleteSummaries(setOf(entry.id)) }) { Text("删除") }
                        }
                    }
                }
            }
    }
}

@Composable fun SummaryReader(summary: SavedSummary, modifier: Modifier = Modifier.fillMaxSize(), onBack: () -> Unit, onDelete: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(modifier.padding(horizontal = 22.dp)) {
        PageTitle(summary.courseName, onBack)
        Text(summary.lessonStart?.let { "上课 ${dateTimeText(it, "Asia/Shanghai")}" + (summary.lessonEnd?.let { end -> " — ${dateTimeText(end, "Asia/Shanghai")}" } ?: "") } ?: "上课时间未指定", style = MaterialTheme.typography.labelMedium)
        Text("生成 ${dateTimeText(summary.createdAt, "Asia/Shanghai")}", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = onDelete) { Text("删除总结") }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(summary.sections) { section ->
                Text(section.source, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 12.dp))
                MarkdownMessage(section.summary, Modifier.fillMaxWidth())
            }
        }
    }
}
