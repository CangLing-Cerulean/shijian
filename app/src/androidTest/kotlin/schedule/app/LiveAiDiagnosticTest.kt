package schedule.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import schedule.android.ScheduleRuntime
import schedule.ai.*
import schedule.core.*

/** Explicit opt-in: uses the configured provider, but writes proposals only to an isolated cache. */
class LiveAiDiagnosticTest {
    @Test fun replayLastRequestInIsolatedSchedule() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAi") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = ScheduleRuntime(context)
        val config = requireNotNull(runtime.aiConfiguration())
        val output = context.cacheDir.resolve("ai-live-diagnostic.jsonl")
        output.writeText("", Charsets.UTF_8)
        val client = AiClient(config.copy(fallbackModels = emptyList()), { runtime.aiKeyForEndpoint(config.endpoint) }, diagnostics = { output.appendText(it + "\n", Charsets.UTF_8) })
        val isolated = context.cacheDir.resolve("ai-diagnostic-state.json")
        isolated.delete()
        val service = ScheduleService(FileStateStore(isolated.toPath()))
        service.restoreBackupByUser(runtime.exportBackup())
        try {
            ScheduleAgent(service, client, StudyTools { _, _ -> error("诊断不执行学校或资料工具") }).respond("生成一个重复日程基地志愿打卡时间是周中晚上和周末全天，具体时间上午9:00-12:00，下午12:00-17:00，晚上18:30-22:30")
            output.appendText("{\"result\":\"success\"}\n", Charsets.UTF_8)
        } finally { isolated.delete() }
    }
}
