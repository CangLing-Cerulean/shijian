package schedule.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import schedule.ai.*
import schedule.android.AiProfile

@Composable internal fun AiSettingsDialog(model: AppModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(emptyList<AiProfile>()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf(false) }
    var profileId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptyList<String>()) }
    var retainedImages by remember { mutableStateOf(emptySet<String>()) }
    var available by remember { mutableStateOf(emptyList<String>()) }
    var results by remember { mutableStateOf(emptyMap<String, AiProbeResult>()) }
    var discoveryStatus by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var discovering by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    var testingModel by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val normalized = runCatching { AiEndpoints.chat(endpoint) }.getOrNull()
    val validCredentials = normalized != null && key.isNotBlank() && !key.contains('\n') && !key.contains('\r')
    val locked = busy || probing || loading

    fun clearConnection() {
        results = emptyMap(); retainedImages = emptySet(); available = emptyList(); discoveryStatus = ""
    }
    fun edit(profile: AiProfile?) {
        revision++
        profileId = profile?.id
        name = profile?.name.orEmpty()
        endpoint = profile?.config?.endpoint.orEmpty()
        key = profile?.key.orEmpty()
        keyVisible = false
        selected = profile?.config?.models?.map { it.id }.orEmpty()
        retainedImages = profile?.config?.models?.filter { it.supportsImages }?.map { it.id }?.toSet().orEmpty()
        results = emptyMap(); available = emptyList(); manual = ""; search = ""; error = ""; discoveryStatus = ""
        editing = true
    }
    LaunchedEffect(Unit) {
        try {
            val loaded = withContext(Dispatchers.IO) { model.runtime.aiProfiles() to model.runtime.activeAiProfileId() }
            profiles = loaded.first; activeId = loaded.second
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { error = "接口配置读取失败"
        } finally { loading = false }
    }
    LaunchedEffect(editing, revision, endpoint, key, refresh) {
        available = emptyList(); discoveryStatus = ""; discovering = false
        if (!editing || !validCredentials) return@LaunchedEffect
        val target = normalized!!
        val secret = key.trim()
        delay(700)
        discovering = true
        try {
            val names = withContext(Dispatchers.IO) { AiDiscovery().discover(target, secret) }
            ensureActive()
            available = names.distinct()
            discoveryStatus = if (names.isEmpty()) "未发现模型，可手动添加" else ""
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { discoveryStatus = "模型列表获取失败，可手动添加"
        } finally { if (currentCoroutineContext().isActive) discovering = false }
    }
    AlertDialog(onDismissRequest = { if (!busy && !loading) onClose() }, title = { Text(if (editing) "编辑 AI 接口" else "AI 接口") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (loading) Text("读取中…")
            if (!editing) {
                profiles.forEach { profile ->
                    Text(profile.name + if (profile.id == activeId) " · 当前使用" else "")
                    Row {
                        TextButton(enabled = !locked && profile.id != activeId, onClick = {
                            busy = true; error = ""
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { model.runtime.selectAiProfile(profile.id) }
                                    activeId = profile.id; model.refresh()
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) { error = "切换失败，请重试"
                                } finally { busy = false }
                            }
                        }) { Text("使用") }
                        TextButton(enabled = !locked, onClick = { edit(profile) }) { Text("编辑") }
                        TextButton(enabled = !locked, onClick = {
                            busy = true; error = ""
                            scope.launch {
                                try {
                                    val loaded = withContext(Dispatchers.IO) {
                                        model.runtime.deleteAiProfile(profile.id)
                                        model.runtime.aiProfiles() to model.runtime.activeAiProfileId()
                                    }
                                    profiles = loaded.first; activeId = loaded.second; model.refresh()
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) { error = "删除失败，请重试"
                                } finally { busy = false }
                            }
                        }) { Text("删除") }
                    }
                }
                TextButton(enabled = !locked, onClick = { edit(null) }) { Text("新建接口") }
            } else {
                OutlinedTextField(name, { name = it }, enabled = !locked, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(endpoint, { if (endpoint != it) key = ""; endpoint = it; clearConnection() }, Modifier.testTag("ai_endpoint"), enabled = !locked, label = { Text("HTTPS 接口地址") }, singleLine = true)
                OutlinedTextField(key, { key = it; clearConnection() }, Modifier.testTag("ai_key"), enabled = !locked, label = { Text("API 密钥") }, visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true,
                    trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }) { Glyph(if (keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye, if (keyVisible) "隐藏密钥" else "显示密钥") } })
                Row {
                    TextButton(enabled = validCredentials && !locked && !discovering, onClick = { refresh++ }) { Text(if (discovering) "获取中…" else "刷新模型") }
                    TextButton(enabled = validCredentials && selected.isNotEmpty() && !locked, modifier = Modifier.testTag("ai_test"), onClick = {
                        val target = normalized!!
                        val secret = key.trim()
                        val names = selected.toList()
                        probing = true; results = emptyMap(); error = ""
                        scope.launch {
                            try {
                                for (candidate in names) {
                                    testingModel = candidate
                                    val result = try {
                                        withContext(Dispatchers.IO) { AiDiscovery().probe(target, secret, candidate) }
                                    } catch (e: CancellationException) { throw e
                                    } catch (_: Exception) { AiProbeResult(false, false, "连接测试失败") }
                                    ensureActive()
                                    results = results + (candidate to result)
                                }
                            } finally { testingModel = null; probing = false }
                        }
                    }) { Text("测试全部") }
                }
                if (discoveryStatus.isNotEmpty()) Text(discoveryStatus, style = MaterialTheme.typography.bodySmall)
                selected.forEachIndexed { index, candidate ->
                    Column(Modifier.fillMaxWidth().testTag("ai_selected_$index")) {
                        Text(if (index == 0) "$candidate · 主模型" else candidate)
                        val result = results[candidate]
                        Text(when {
                            testingModel == candidate -> "正在测试…"
                            result == null && probing -> "等待测试"
                            result == null && candidate in retainedImages -> "图片已验证"
                            result == null -> "尚未测试"
                            result.connected && result.supportsImages -> "文本已验证 · 图片已验证"
                            result.connected -> "文本已验证 · 图片未验证"
                            else -> "连接失败 · 图片未验证"
                        }, style = MaterialTheme.typography.bodySmall)
                        if (result != null && result.detail.isNotBlank()) Text(result.detail, style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (index > 0) {
                                TextButton(enabled = !locked, onClick = { selected = listOf(candidate) + selected.filter { it != candidate } }) { Text("设为主模型") }
                                TextButton(enabled = !locked, onClick = { selected = selected.toMutableList().apply { removeAt(index); add(index - 1, candidate) } }) { Text("上移") }
                            }
                            TextButton(enabled = !locked, onClick = { selected = selected.filter { it != candidate }; results = results - candidate }) { Text("移除") }
                        }
                    }
                }
                if (available.isNotEmpty()) {
                    OutlinedTextField(search, { search = it }, Modifier.testTag("ai_model_search"), enabled = !locked, label = { Text("筛选模型") }, singleLine = true)
                    TextButton(enabled = !locked, onClick = { selected = (selected + available.filter { it.contains(search.trim(), ignoreCase = true) }).distinct() }) { Text("全选") }
                }
                available.filter { it !in selected && it.contains(search.trim(), ignoreCase = true) }.take(30).forEach { candidate ->
                    TextButton(enabled = !locked, onClick = { selected = selected + candidate }) { Text(candidate) }
                }
                OutlinedTextField(manual, { manual = it }, Modifier.testTag("ai_manual_model"), enabled = !locked, label = { Text("手动输入模型名称") }, singleLine = true)
                TextButton(enabled = !locked && manual.trim().isNotEmpty() && manual.trim() !in selected, onClick = { selected = selected + manual.trim(); manual = "" }) { Text("添加模型") }
            }
        }
    }, confirmButton = {
        if (editing) TextButton(enabled = validCredentials && name.isNotBlank() && selected.isNotEmpty() && !locked && !model.working, modifier = Modifier.testTag("ai_save"), onClick = {
            val ordered = selected.map { candidate ->
                AiModel(candidate, results[candidate]?.let { it.connected && it.supportsImages } ?: (candidate in retainedImages))
            }
            val config = AiConfiguration(normalized!!, ordered.first().id, ordered.first().supportsImages, ordered.drop(1))
            val id = profileId; val title = name.trim(); val secret = key.trim()
            busy = true; error = ""
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { model.runtime.saveAiProfile(id, title, config, secret) }
                    model.refresh(); onClose()
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { error = "保存失败，请检查配置"
                } finally { busy = false }
            }
        }) { Text("保存") }
    }, dismissButton = {
        TextButton(enabled = !busy && !loading, onClick = { if (probing) onClose() else if (editing) { editing = false; revision++ } else onClose() }) { Text(if (probing) "取消测试" else if (editing) "返回" else "关闭") }
    })
}
