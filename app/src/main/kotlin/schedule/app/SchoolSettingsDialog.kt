package schedule.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable internal fun SchoolSettingsDialog(model: AppModel, onClose: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try {
            val account = withContext(Dispatchers.IO) { model.runtime.schoolAccount() }
            username = account?.username.orEmpty(); password = account?.password.orEmpty()
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { error = "读取学校账号失败" }
        finally { loading = false }
    }
    fun submit() {
        if (loading || checking || username.isBlank() || password.isEmpty()) return
        checking = true; error = null
        val account = username.trim(); val secret = password
        scope.launch {
            try {
                withContext(Dispatchers.IO) { model.runtime.configureSchool(account, secret) }
                model.refresh(); model.notice = "学校账号验证通过，已保存"; onClose()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = e.message ?: "学校账号验证失败，当前输入未保存"
            } finally { checking = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!checking) onClose() }, title = { Text("学校账号") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("浙江大学")
            OutlinedTextField(username, { username = it; error = null }, Modifier.testTag("school_username"), enabled = !loading && !checking, label = { Text("学号") }, singleLine = true)
            OutlinedTextField(password, { password = it; error = null }, Modifier.testTag("school_password"), enabled = !loading && !checking,
                label = { Text("统一身份认证密码") }, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true,
                trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Glyph(if (passwordVisible) R.drawable.ic_eye_off else R.drawable.ic_eye, if (passwordVisible) "隐藏密码" else "显示密码") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
            if (checking) Text("正在验证…")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("school_error")) }
            if (model.schoolConfigured) TextButton(enabled = !loading && !checking, onClick = {
                scope.launch {
                    try { withContext(Dispatchers.IO) { model.runtime.clearSchoolAccount() }; model.refresh(); onClose() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "移除学校账号失败，请重试" }
                }
            }) { Text("移除账号") }
        }
    }, confirmButton = {
        TextButton(enabled = !loading && !checking && username.isNotBlank() && password.isNotEmpty(), onClick = { submit() }, modifier = Modifier.testTag("school_verify")) { Text("验证并保存") }
    }, dismissButton = { TextButton(enabled = !checking, onClick = onClose) { Text("取消") } })
}
