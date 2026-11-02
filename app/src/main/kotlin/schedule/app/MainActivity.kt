package schedule.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import kotlinx.serialization.encodeToString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import schedule.core.*
import java.time.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { val model: AppModel = viewModel(); ScheduleApp(model) }
    }
}

@Composable fun Glyph(resource: Int, label: String?, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Icon(painterResource(resource), label, modifier.size(22.dp), tint = color)
}
@Composable fun GlyphButton(resource: Int, label: String, onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) .88f else 1f, label = "icon-press")
    IconButton(onClick, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, interactionSource = source) { Glyph(resource, label) }
}
val InkBlue = Color(0xFF3579E8)
inline fun <reified T : Any> jsonSaver() = Saver<T?, String>(save = { it?.let { value -> scheduleJson.encodeToString(value) } ?: "" }, restore = { it.takeIf(String::isNotEmpty)?.let { text -> scheduleJson.decodeFromString<T>(text) } })

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ScheduleApp(model: AppModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val preference = model.state?.settings?.theme ?: ThemePreference.SYSTEM
    val dark = preference == ThemePreference.DARK || preference == ThemePreference.SYSTEM && isSystemInDarkTheme()
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(dark) {
        activity?.enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }, navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark })
        onDispose { }
    }
    val colors = if (dark) darkColorScheme(primary = Color(0xFF98BBFF), onPrimary = Color(0xFF142B4F), primaryContainer = Color(0xFF243958), onPrimaryContainer = Color(0xFFD7E6FF), secondaryContainer = Color(0xFF28384D), onSecondaryContainer = Color(0xFFD7E6FF), background = Color(0xFF121518), surface = Color(0xFF1B2025), surfaceContainer = Color(0xFF20262D), surfaceContainerLow = Color(0xFF181D22), surfaceContainerHigh = Color(0xFF262D35), outlineVariant = Color(0xFF3D4856))
        else lightColorScheme(primary = InkBlue, onPrimary = Color.White, primaryContainer = Color(0xFFE5EEFC), onPrimaryContainer = Color(0xFF18345A), secondaryContainer = Color(0xFFE9EFF8), onSecondaryContainer = Color(0xFF264C7E), background = Color(0xFFF5F7FA), surface = Color.White, surfaceContainer = Color(0xFFEEF2F7), surfaceContainerLow = Color(0xFFF8FAFD), surfaceContainerHigh = Color(0xFFE7ECF2), outlineVariant = Color(0xFFD7DFE8))
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(26.dp))) {
        var page by rememberSaveable { mutableStateOf("schedule") }
        var selected by rememberSaveable(stateSaver = Saver(save = { it.toString() }, restore = { LocalDate.parse(it) })) { mutableStateOf(LocalDate.now()) }
        var details by rememberSaveable(stateSaver = jsonSaver<Occurrence>()) { mutableStateOf<Occurrence?>(null) }
        var editing by rememberSaveable(stateSaver = jsonSaver<ScheduleItem>()) { mutableStateOf<ScheduleItem?>(null) }
        var chatOpen by rememberSaveable { mutableStateOf(false) }
        var chatDraft by rememberSaveable { mutableStateOf("") }
        var contextId by rememberSaveable { mutableStateOf<String?>(null) }
        var coursesOpen by rememberSaveable { mutableStateOf(false) }
        var courseItemId by rememberSaveable { mutableStateOf<String?>(null) }
        var transcriptId by rememberSaveable { mutableStateOf<String?>(null) }
        BackHandler(editing != null || details != null || coursesOpen || transcriptId != null || page != "schedule") {
            when { editing != null -> editing = null; transcriptId != null -> transcriptId = null; coursesOpen -> coursesOpen = false; details != null -> details = null; else -> page = "schedule" }
        }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(model.error, model.notice) {
            val message = model.error ?: model.notice
            if (message != null) { snackbar.showSnackbar(message); model.error = null; model.notice = null }
        }
        fun openChat() { contextId = editing?.id ?: details?.item?.id; chatDraft = ""; chatOpen = true }
        fun createItem() {
            val state = model.state ?: return
            val start = LocalDate.now(ZoneId.of(state.settings.zoneId)).atStartOfDay(ZoneId.of(state.settings.zoneId)).toInstant().toEpochMilli()
            details = null; coursesOpen = false; transcriptId = null
            editing = ScheduleItem(title = "", kind = ItemKind.REMINDER, startAt = start + 18 * 3600000, zoneId = state.settings.zoneId, reminders = listOf(Reminder(TimeAnchor.START)))
        }
        Scaffold(containerColor = colors.background, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    NavigationBarItem(selected = page == "schedule", onClick = { page = "schedule"; editing = null; details = null; coursesOpen = false; transcriptId = null }, icon = { Glyph(R.drawable.ic_calendar_days, "日程") }, label = { Text("日程") })
                    NavigationBarItem(selected = page == "items", onClick = { page = "items"; editing = null; details = null; coursesOpen = false; transcriptId = null }, icon = { Glyph(R.drawable.ic_square_pen, "事项") }, label = { Text("事项") })
                    NavigationBarItem(selected = page == "settings", onClick = { page = "settings"; editing = null; details = null; coursesOpen = false; transcriptId = null }, icon = { Glyph(R.drawable.ic_settings_2, "设置") }, label = { Text("设置") })
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val state = model.state
                when {
                    state == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("正在读取日程"); TextButton(onClick = { model.refresh() }) { Text("重试") } }
                    editing != null -> ItemEditor(editing!!, model.working, onBack = { editing = null }, onSave = { item -> model.save(item) { editing = null; details = null } }, onError = { model.error = it })
                    transcriptId != null -> LessonTranscriptScreen(model, transcriptId!!, { transcriptId = null })
                    coursesOpen -> if (courseItemId != null) LessonMaterialsScreen(model, courseItemId!!, onBack = { coursesOpen = false }, onTranscript = { transcriptId = courseItemId }) else CourseScreen(model, onBack = { coursesOpen = false })
                    details != null -> {
                        val original = details!!
                        val current = state.items.find { it.id == original.item.id }
                        if (current == null) { LaunchedEffect(Unit) { details = null } }
                        else ItemDetails(original.copy(item = current), onBack = { details = null }, onEdit = { editing = current }, onDelete = { model.delete(current.id) { details = null } }, onCourses = { courseItemId = current.id; coursesOpen = true },
                            onSaveNotes = { notes -> model.save(current.copy(notes = notes)) {} },
                            onAiNotes = { contextId = current.id; chatDraft = "请根据当前事项及已有资料帮我填写备注；信息不够时先询问，仅修改备注并提交待审批草案。"; chatOpen = true })
                    }
                    page == "settings" -> SettingsScreen(model, onCourses = { courseItemId = null; coursesOpen = true; model.loadCourses() })
                    page == "items" -> ItemsScreen(state, onCreate = ::createItem, onDetails = { details = it }, onComplete = model::complete)
                    else -> CalendarScreen(state, selected, { selected = it }, onDetails = { details = it }, onComplete = model::complete)
                }
                if (model.working) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                DraggableAssistant(onClick = { openChat() })
            }
        }
        if (chatOpen) ModalBottomSheet(onDismissRequest = { chatOpen = false }, sheetGesturesEnabled = false, dragHandle = null, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ChatPanel(model, contextId, Modifier.fillMaxHeight(.9f), initialText = chatDraft, onClose = { chatOpen = false })
        }
        model.review?.let { review -> ModalBottomSheet(onDismissRequest = { model.review = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { ProposalPanel(model, review) } }
    }
}

@Composable fun PageTitle(title: String, onBack: (() -> Unit)? = null, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) GlyphButton(R.drawable.ic_arrow_left, "返回", onBack)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action()
    }
}
