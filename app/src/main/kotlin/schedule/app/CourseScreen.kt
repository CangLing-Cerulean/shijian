package schedule.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import schedule.school.*
import schedule.android.MaterialStore
import schedule.android.SavedMaterial
import java.io.File

@Composable fun CourseScreen(model: AppModel, onBack: () -> Unit, itemId: String? = null) {
    var course by remember { mutableStateOf<SchoolCourse?>(null) }
    var selectedMaterial by remember { mutableStateOf<SavedMaterial?>(null) }
    var exportMaterial by remember { mutableStateOf<SavedMaterial?>(null) }
    var downloaded by remember { mutableStateOf(false) }
    var summaries by remember { mutableStateOf(false) }
    if (summaries) { SummaryLibrary(model, onBack = { summaries = false }); return }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var savedMaterials by remember { mutableStateOf(emptyList<SavedMaterial>()) }
    var pdf by remember { mutableStateOf<File?>(null) }
    var pdfTitle by remember { mutableStateOf("") }
    val context = LocalContext.current
    val store = remember(context) { MaterialStore(context) }
    var linkedCourses by remember(itemId) { mutableStateOf<List<SchoolCourse>?>(null) }
    var linking by remember(itemId) { mutableStateOf(itemId != null) }
    var linkError by remember(itemId) { mutableStateOf<String?>(null) }
    LaunchedEffect(itemId) {
        if (itemId != null) try {
            val candidates = withContext(Dispatchers.IO) { model.runtime.coursesForLesson(itemId) }
            linkedCourses = candidates
            if (candidates.size == 1) { course = candidates.single(); model.loadMaterials(candidates.single().courseId) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { linkError = e.message ?: "课程关联查询失败" }
        finally { linking = false }
    }
    LaunchedEffect(Unit) { savedMaterials = withContext(Dispatchers.IO) { store.list() } }
    DisposableEffect(Unit) { onDispose { model.studyContext = null } }
    LaunchedEffect(course, pdf, downloaded) { if (pdf == null) model.studyContext = course?.takeUnless { downloaded }?.let { "学在浙大课程 ${it.name}，courseId=${it.courseId}，本地课程itemId=$itemId" } }
    BackHandler(pdf != null || (!downloaded && course != null)) { if (pdf != null) pdf = null else course = null }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val selected = exportMaterial
        if (uri != null && selected != null) model.launchWork {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> store.file(selected).inputStream().use { it.copyTo(output) } } }
            model.notice = "已导出 ${selected.originalName}"
        }
    }
    if (pdf != null) {
        PdfScreen(pdf!!, pdfTitle, onBack = { pdf = null }, onError = { model.error = it }, onPage = { number -> model.studyContext = "LMS课程 ${selectedMaterial?.courseName}，courseId=${selectedMaterial?.courseId}；资料 $pdfTitle，uploadId=${selectedMaterial?.material?.uploadId}，PDF第${number}页" })
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        PageTitle(if (itemId == null) "资料" else "选择课件", { if (downloaded || course == null || (itemId != null && linkedCourses?.size == 1)) onBack() else course = null }) {
            GlyphButton(R.drawable.ic_refresh_cw, "刷新") { if (downloaded) model.launchWork { savedMaterials = withContext(Dispatchers.IO) { store.list() } } else if (course == null) model.loadCourses() else model.loadMaterials(course!!.courseId) }
        }
        if (itemId == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !downloaded, onClick = { downloaded = false }, label = { Text("学在浙大") })
            FilterChip(selected = downloaded, onClick = { downloaded = true }, label = { Text("已下载") })
            FilterChip(selected = false, onClick = { summaries = true }, label = { Text("总结") })
        }
        if (!downloaded) course?.let { Text(it.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp)) }
        LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (downloaded) {
                if (savedMaterials.isEmpty()) item { Text("暂无已下载资料", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(savedMaterials, key = { it.id }) { saved ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(saved.originalName, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(saved.courseName, saved.activityTitle).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                            Row {
                                if (saved.originalName.endsWith(".pdf", true)) TextButton(onClick = { selectedMaterial = saved; pdfTitle = saved.exportName; pdf = store.file(saved) }) { Text("阅读") }
                                TextButton(onClick = { exportMaterial = saved; save.launch(saved.exportName) }) { Text("导出") }
                                TextButton(onClick = {
                                    deletingId = saved.id
                                    model.launchWork {
                                        try { withContext(Dispatchers.IO) { store.delete(saved) }; savedMaterials = withContext(Dispatchers.IO) { store.list() } }
                                        finally { deletingId = null }
                                    }
                                }, enabled = deletingId == null) { Text("删除") }
                            }
                        }
                    }
                }
            } else if (course == null) {
                if (linking) item { Text("正在查找对应课程") }
                linkError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (!linking && linkedCourses?.isEmpty() == true) item { Text("未找到对应的本学期课程"); TextButton(onClick = { linkedCourses = null }) { Text("选择本学期课程") } }
                if (!linking && !model.working && linkedCourses == null && model.courses.isEmpty()) item { Text("暂无课程", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(if (linking) emptyList() else linkedCourses ?: model.courses, key = { it.courseId }) { item ->
                    Card(onClick = { course = item; model.loadMaterials(item.courseId) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) { Text(item.name, style = MaterialTheme.typography.titleMedium); Text(item.teachers.joinToString("、"), modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall); Text("${item.academicYear} ${item.semester}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            } else {
                if (!model.working && model.materials.isEmpty()) item { Text("暂无可用资料", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(model.materials, key = { "${it.activityId}:${it.uploadId}:${it.referenceId}" }) { item ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            if (item.activityTitle.isNotBlank()) Text(item.activityTitle, style = MaterialTheme.typography.labelMedium)
                            Row {
                                if (itemId != null) TextButton(onClick = { val owner = course ?: return@TextButton; model.launchWork {
                                    withContext(Dispatchers.IO) { model.runtime.linkLessonMaterial(itemId, owner.courseId, item) }
                                    model.notice = "已加入当堂资料"; onBack()
                                } }, enabled = !model.working) { Text("加入当堂") }
                                if (item.name.endsWith(".pdf", true)) TextButton(onClick = { val owner = course ?: return@TextButton; model.launchWork {
                                    val saved = withContext(Dispatchers.IO) { savedMaterials.find { it.courseId == owner.courseId && it.material.activityId == item.activityId && it.material.uploadId == item.uploadId && it.material.referenceId == item.referenceId } ?: store.save(owner.courseId, owner.name, item, model.runtime.downloadCourseMaterialByUser(item)) }
                                    savedMaterials = withContext(Dispatchers.IO) { store.list() }
                                    selectedMaterial = saved; pdfTitle = saved.exportName; pdf = store.file(saved)
                                } }, enabled = !model.working) { Text("阅读") }
                                TextButton(onClick = { val owner = course ?: return@TextButton; model.launchWork {
                                    withContext(Dispatchers.IO) { store.save(owner.courseId, owner.name, item, model.runtime.downloadCourseMaterialByUser(item)) }
                                    savedMaterials = withContext(Dispatchers.IO) { store.list() }; model.notice = "已下载 ${item.name}"
                                } }, enabled = !model.working) { Glyph(R.drawable.ic_download, null); Spacer(Modifier.width(6.dp)); Text("下载") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun LessonTranscriptScreen(model: AppModel, itemId: String, onBack: () -> Unit) {
    var lectures by remember(itemId) { mutableStateOf<List<SchoolLesson>>(emptyList()) }
    var selected by remember(itemId) { mutableStateOf<SchoolLesson?>(null) }
    var segments by remember(itemId) { mutableStateOf<List<TranscriptSegment>>(emptyList()) }
    var loading by remember(itemId) { mutableStateOf(true) }
    var failure by remember(itemId) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(itemId, refresh) {
        loading = true; failure = null; selected = null; segments = emptyList()
        try {
            lectures = withContext(Dispatchers.IO) { model.runtime.lecturesForLesson(itemId) }
            if (lectures.size == 1) selected = lectures.single()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { failure = e.message ?: "讲次查询失败" }
        finally { loading = false }
    }
    LaunchedEffect(selected, refresh) {
        val lecture = selected ?: return@LaunchedEffect
        loading = true; failure = null; segments = emptyList()
        try { segments = withContext(Dispatchers.IO) { model.runtime.fetchTranscriptByUser(lecture.subId) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "转录读取失败" }
        finally { loading = false }
    }
    DisposableEffect(itemId, selected) { model.studyContext = "智云课堂转录，本地课程itemId=$itemId，lectureId=${selected?.subId}"; onDispose { model.studyContext = null } }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        PageTitle("课堂转录", onBack) { GlyphButton(R.drawable.ic_refresh_cw, "重新获取") { refresh++ } }
        Text("智云课堂", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(top = 18.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            failure?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (!loading && failure == null && lectures.isEmpty()) item { Text("智云未找到对应讲次，可能未录播或尚未发布。") }
            if (lectures.size > 1) items(lectures, key = { it.subId }) { lecture ->
                OutlinedCard(onClick = { selected = lecture }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) { Text(lecture.title); Text("${dateTimeText(lecture.startMillis)} · ${lecture.teacher} · ${lecture.status}", style = MaterialTheme.typography.labelMedium) }
                }
            }
            selected?.let { lecture -> item { Text("${lecture.title}\n${dateTimeText(lecture.startMillis)} · ${lecture.teacher}", style = MaterialTheme.typography.titleSmall) } }
            if (!loading && failure == null && selected != null && segments.isEmpty()) item { Text("该讲次尚无可用转录；上过课不代表智云已发布转录。") }
            items(segments) { segment ->
                Column {
                    val seconds = segment.beginSeconds.toLong()
                    Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(segment.text, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

@Composable fun PdfScreen(file: File, title: String, onBack: () -> Unit, onError: (String) -> Unit, onPage: (Int) -> Unit) {
    var number by remember(file) { mutableIntStateOf(1) }; var total by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(file, number) {
        onPage(number)
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                    val count = renderer.pageCount
                    renderer.openPage(number - 1).use { page ->
                        val scale = minOf(1600f / page.width, 2200f / page.height)
                        val image = createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1))
                        image.eraseColor(android.graphics.Color.WHITE); page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        count to image
                    }
                } }
            }
            total = result.first; bitmap = result.second
        } catch (e: Exception) { onError(e.message ?: "PDF读取失败") } finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        PageTitle("资料", onBack)
        Text(title, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { number-- }, enabled = number > 1 && !loading) { Glyph(R.drawable.ic_chevron_left, "上一页") }
            Text("$number / $total")
            IconButton(onClick = { number++ }, enabled = number < total && !loading) { Glyph(R.drawable.ic_chevron_right, "下一页") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { bitmap?.let { Image(it.asImageBitmap(), "第${number}页", Modifier.fillMaxWidth()) }; Spacer(Modifier.height(90.dp)) }
    }
}
