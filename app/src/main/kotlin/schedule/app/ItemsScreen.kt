package schedule.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import schedule.core.*
import java.time.*

@Composable
fun ItemsScreen(state: ScheduleState, onCreate: () -> Unit, onDetails: (Occurrence) -> Unit, onComplete: (Occurrence) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30000) } }
    val zone = ZoneId.of(state.settings.zoneId)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val pending = remember(state.revision, today, zone) { pendingItems(state.items, today, zone) }
    fun isToday(value: Occurrence): Boolean = listOfNotNull(value.startAt, value.deadlineAt).any {
        Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today
    }
    val todays = pending.filter(::isToday)
    val remaining = pending.filterNot(::isToday)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("事项", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            FilledTonalButton(onClick = onCreate) { Glyph(R.drawable.ic_square_pen, null); Spacer(Modifier.width(8.dp)); Text("创建") }
        }
        LazyColumn(contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (todays.isNotEmpty()) {
                item { SectionLabel("今天", todays.size.toString()) }
                items(todays, key = { "today:${it.item.id}:${it.originalStart}" }) { value ->
                    OccurrenceCard(value, now, false, { onDetails(value) }, { onComplete(value) }, showDate = true)
                }
            }
            if (remaining.isNotEmpty()) {
                item { SectionLabel("待完成", remaining.size.toString()) }
                items(remaining, key = { "pending:${it.item.id}:${it.originalStart}" }) { value ->
                    OccurrenceCard(value, now, false, { onDetails(value) }, { onComplete(value) }, showDate = true)
                }
            }
            if (pending.isEmpty()) item { Text("暂无待办", Modifier.padding(vertical = 28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
