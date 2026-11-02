package schedule.app

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import schedule.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
fun clockText(at: Long?, zone: String = "Asia/Shanghai"): String = at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: ""
fun dateTimeText(at: Long?, zone: String = "Asia/Shanghai"): String = at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) } ?: ""
private fun occurrenceMoment(value: Occurrence): Long = if (value.item.kind == ItemKind.TASK) value.deadlineAt ?: value.startAt!! else value.startAt ?: value.deadlineAt!!
fun timeColor(at: Long, zone: String): Color {
    val hour = Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).hour
    return Color(when { hour <= 8 -> 0xFFF44336; hour <= 12 -> 0xFFFFC107; hour == 13 -> 0xFFA3E800; hour <= 15 -> 0xFF4CAF50; hour <= 17 -> 0xFF03A9F4; hour <= 19 -> 0xFF2600FF; else -> 0xFFC300FF })
}
@Composable fun CalendarScreen(state: ScheduleState, selected: LocalDate, onDate: (LocalDate) -> Unit, onDetails: (Occurrence) -> Unit, onComplete: (Occurrence) -> Unit) {
    var monthView by remember { mutableStateOf(false) }
    var calendarCollapsed by remember { mutableStateOf(false) }
    val agendaState = rememberLazyListState()
    val agendaScroll = remember(monthView, calendarCollapsed) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (monthView && source == NestedScrollSource.UserInput) {
                if (available.y < -6) calendarCollapsed = true
                else if (available.y > 6 && agendaState.firstVisibleItemIndex == 0 && agendaState.firstVisibleItemScrollOffset == 0) calendarCollapsed = false
            }
            return Offset.Zero
        }
    } }
    LaunchedEffect(selected, monthView) { agendaState.scrollToItem(0) }
    val zone = ZoneId.of(state.settings.zoneId)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30000) } }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val start = selected.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = selected.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val occurrences = remember(state.revision, selected) { state.items.flatMap { TimeEngine.occurrences(it, start, end, true) }.sortedBy(::occurrenceMoment) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable {
                DatePickerDialog(context, { _, y, m, d -> onDate(LocalDate.of(y, m + 1, d)) }, selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
            }) {
                Text("${selected.monthValue}月${selected.dayOfMonth}日", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("${if (selected == today) "今天 · " else ""}星期${weekdays[selected.dayOfWeek.value - 1]}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(selected = !monthView, onClick = { monthView = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("日") }
                SegmentedButton(selected = monthView, onClick = { monthView = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("月") }
            }
        }
        if (monthView) MonthCalendar(state, selected, today, onDate, calendarCollapsed, { calendarCollapsed = it }) else DateRoller(state, selected, today, onDate)
        LazyColumn(Modifier.fillMaxSize().nestedScroll(agendaScroll), state = agendaState, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (monthView) {
                item { SectionLabel("当天安排", occurrences.size.toString()) }
                items(occurrences, key = { "month:${it.item.id}:${it.originalStart}" }) { occurrence -> OccurrenceCard(occurrence, now, selected == today && occurrence.item.kind == ItemKind.COURSE, { onDetails(occurrence) }, { onComplete(occurrence) }) }
            } else {
            val lessons = occurrences.filter { it.item.kind == ItemKind.COURSE }
            if (lessons.isNotEmpty()) {
                item { SectionLabel("课程", lessons.size.toString()) }
                items(lessons, key = { "course:${it.item.id}:${it.originalStart}" }) { occurrence -> OccurrenceCard(occurrence, now, selected == today, { onDetails(occurrence) }, { onComplete(occurrence) }) }
            }
            val other = occurrences.filter { it.item.kind != ItemKind.COURSE }
            if (other.isNotEmpty()) {
                item { SectionLabel("当天安排", other.size.toString()) }
                items(other, key = { "item:${it.item.id}:${it.originalStart}" }) { occurrence -> OccurrenceCard(occurrence, now, false, { onDetails(occurrence) }, { onComplete(occurrence) }) }
            }
            }
            if (occurrences.isEmpty()) item { Text("这一天暂无安排", Modifier.padding(vertical = 28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!monthView) {
                val shown = occurrences.map { it.item.id to it.originalStart }.toSet()
                val unfinished = pendingItems(state.items, selected, zone).filter { it.item.kind == ItemKind.TASK && (it.item.id to it.originalStart) !in shown }
                if (unfinished.isNotEmpty()) {
                    item { SectionLabel("待完成", unfinished.size.toString()) }
                    items(unfinished, key = { "pending:${it.item.id}:${it.originalStart}" }) { occurrence ->
                        OccurrenceCard(occurrence, now, false, { onDetails(occurrence) }, { onComplete(occurrence) }, showDate = true)
                    }
                }
            }
        }
    }
}
@Composable private fun DateRoller(state: ScheduleState, selected: LocalDate, today: LocalDate, onDate: (LocalDate) -> Unit) {
    val base = remember { today.minusDays(3650) }
    val index = java.time.temporal.ChronoUnit.DAYS.between(base, selected).toInt().coerceIn(0, 7300)
    val scroll = rememberLazyListState(maxOf(0, index - 3))
    val density = LocalDensity.current
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var touching by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val todayIndex = java.time.temporal.ChronoUnit.DAYS.between(base, today).toInt()
    val animationScope = rememberCoroutineScope()
    LaunchedEffect(scroll.isScrollInProgress, dragged, touching, interaction, today) {
        if (!scroll.isScrollInProgress && !dragged && !touching) {
            delay(5000)
            val layout = scroll.layoutInfo
            if (layout.visibleItemsInfo.isNotEmpty()) {
                val step = with(density) { 56.dp.toPx() }
                val width = with(density) { 48.dp.toPx() }
                val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                val first = layout.visibleItemsInfo.first()
                val distance = first.offset + (todayIndex - first.index) * step + width / 2 - center
                if (kotlin.math.abs(distance) > 1f) animationScope.launch { scroll.animateScrollBy(distance, tween(650, easing = FastOutSlowInEasing)) }
            }
        }
    }
    LaunchedEffect(selected) { if (scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.animateScrollToItem(maxOf(0, index - 3)) }
    LazyRow(modifier = Modifier.testTag("date-roller"), state = scroll, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(7301) { i ->
            val date = base.plusDays(i.toLong()); val active = date == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            LaunchedEffect(pressed) { touching = pressed; if (pressed) interaction++ }
            val scale by animateFloatAsState(if (pressed) .93f else 1f, tween(160), label = "date-press")
            val markers = remember(state.revision, date, state.settings.zoneId) {
                val zone = ZoneId.of(state.settings.zoneId)
                val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                state.items.flatMap { TimeEngine.occurrences(it, from, until, true) }
                    .filter { occurrenceMoment(it) in from until until || (it.item.kind != ItemKind.TASK && it.startAt != null && it.endAt != null && it.startAt!! < until && it.endAt!! > from) }
                    .sortedBy(::occurrenceMoment).take(5)
            }
            val isToday = date == today
            Column(Modifier.testTag("roller-date-$date").width(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(18.dp))
                .background(when { isToday -> MaterialTheme.colorScheme.onSurface; active -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surface })
                .clickable(interactionSource = source, indication = ripple()) { interaction++; onDate(date) }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val color = when { isToday -> MaterialTheme.colorScheme.surface; active -> MaterialTheme.colorScheme.onPrimaryContainer; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                Text(weekdays[date.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = color)
                Spacer(Modifier.height(8.dp)); Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = color)
                Row(Modifier.padding(top = 5.dp).height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    markers.forEach { marker -> Box(Modifier.size(4.5.dp).background(timeColor(occurrenceMoment(marker), state.settings.zoneId), CircleShape)) }
                }
            }
        }
    }
}
@Composable private fun MonthCalendar(state: ScheduleState, selected: LocalDate, today: LocalDate, onDate: (LocalDate) -> Unit, collapsed: Boolean, onCollapse: (Boolean) -> Unit) {
    val month = YearMonth.from(selected)
    val first = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value - 1).toLong())
    val totalWeeks = ceil((month.lengthOfMonth() + month.atDay(1).dayOfWeek.value - 1) / 7.0).toInt()
    val zone = ZoneId.of(state.settings.zoneId)
    val monthItems = remember(state.revision, month) {
        state.items.flatMap { TimeEngine.occurrences(it, first.atStartOfDay(zone).toInstant().toEpochMilli(), first.plusDays(totalWeeks * 7L).atStartOfDay(zone).toInstant().toEpochMilli(), true) }.sortedBy(::occurrenceMoment)
    }
    var dragX by remember { mutableFloatStateOf(0f) }; var dragY by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 18.dp).pointerInput(month, collapsed) {
        detectDragGestures(onDragStart = { dragX = 0f; dragY = 0f }, onDragEnd = {
            if (kotlin.math.abs(dragX) > kotlin.math.abs(dragY) && kotlin.math.abs(dragX) > 70) onDate(selected.plusMonths(if (dragX < 0) 1 else -1))
            else if (kotlin.math.abs(dragY) > 35) onCollapse(dragY < 0)
        }) { change, amount -> change.consume(); dragX += amount.x; dragY += amount.y }
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(R.drawable.ic_chevron_left, "上个月") { onDate(selected.minusMonths(1)) }
            Spacer(Modifier.weight(1f)); Text("${month.year} / ${month.monthValue}", style = MaterialTheme.typography.labelLarge); Spacer(Modifier.weight(1f))
            GlyphButton(R.drawable.ic_chevron_right, "下个月") { onDate(selected.plusMonths(1)) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) { weekdays.forEach { Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        val weeks = if (collapsed) listOf((java.time.temporal.ChronoUnit.DAYS.between(first, selected) / 7).toInt()) else (0 until totalWeeks).toList()
        weeks.forEach { week -> Row(Modifier.fillMaxWidth()) {
            repeat(7) { day ->
                val date = first.plusDays(week * 7L + day)
                val active = date == selected
                val dateStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dateEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val markers = monthItems.filter { occurrenceMoment(it) in dateStart until dateEnd || (it.item.kind != ItemKind.TASK && it.startAt != null && it.endAt != null && it.startAt!! < dateEnd && it.endAt!! > dateStart) }.take(5)
                Column(Modifier.weight(1f).height(48.dp).clickable { onDate(date) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(32.dp).background(when { active -> InkBlue.copy(alpha = .5f); date == today -> MaterialTheme.colorScheme.onSurface.copy(alpha = .1f); else -> Color.Transparent }, CircleShape), contentAlignment = Alignment.Center) {
                        Text(date.dayOfMonth.toString(), color = if (date.month == month.month) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { markers.forEach { marker -> Box(Modifier.size(4.5.dp).background(timeColor(occurrenceMoment(marker), state.settings.zoneId), CircleShape)) } }
                }
            }
        } }
        Box(Modifier.fillMaxWidth().height(22.dp).testTag("month-fold").clickable { onCollapse(!collapsed) }, contentAlignment = Alignment.Center) { Box(Modifier.width(34.dp).height(3.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape)) }
    }
}
@Composable fun SectionLabel(title: String, count: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
@Composable fun OccurrenceCard(occurrence: Occurrence, now: Long, countdown: Boolean, onClick: () -> Unit, onComplete: () -> Unit, showDate: Boolean = false) {
    val item = occurrence.item; val point = occurrenceMoment(occurrence)
    val accent = timeColor(point, item.zoneId)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(if (item.kind == ItemKind.COURSE) 78.dp else 54.dp).background(accent, CircleShape))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (occurrence.completed) .5f else 1f))
                Spacer(Modifier.height(6.dp))
                val time = if (showDate) dateTimeText(occurrence.deadlineAt ?: point, item.zoneId) else clockText(point, item.zoneId) + (occurrence.endAt?.let { " — ${clockText(it, item.zoneId)}" } ?: if (occurrence.deadlineAt != null) " 截止" else "")
                Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.location.isNotBlank() || item.teacher.isNotBlank()) Text(listOf(item.location, item.teacher).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (countdown && occurrence.endAt != null && now < occurrence.endAt!!) {
                    val minutes = ((point - now).coerceAtLeast(0) + 59999) / 60000
                    Text(if (now >= point) "正在上课" else "还有 ${minutes / 60}小时 ${minutes % 60}分钟", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                }
                if (!occurrence.completed && item.kind == ItemKind.TASK && occurrence.deadlineAt != null) {
                    Text(deadlineStatus(occurrence.deadlineAt!!, now), color = if (now > occurrence.deadlineAt!!) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (item.kind != ItemKind.COURSE) Checkbox(checked = occurrence.completed, onCheckedChange = { onComplete() })
        }
    }
}
