package schedule.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

val scheduleJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

interface StateStore {
    fun read(): ScheduleState
    /** Persist completely or leave the old state untouched; transformations must not have side effects. */
    fun update(transform: (ScheduleState) -> ScheduleState): ScheduleState
}

class MemoryStateStore(initial: ScheduleState = ScheduleState()) : StateStore {
    private var state = initial
    @Synchronized override fun read() = scheduleJson.decodeFromString<ScheduleState>(scheduleJson.encodeToString(state))
    @Synchronized override fun update(transform: (ScheduleState) -> ScheduleState): ScheduleState {
        val next = transform(read())
        state = scheduleJson.decodeFromString(scheduleJson.encodeToString(next))
        return read()
    }
}

/** Android API 26+. A corrupt file raises an error and is never silently replaced by an empty calendar. */
class FileStateStore(file: Path) : StateStore {
    private val path = file.toAbsolutePath().normalize()
    private val mutex = mutexes.computeIfAbsent(path) { ReentrantLock() }
    init { Files.createDirectories(path.parent) }
    private fun <T> locked(block: () -> T): T = mutex.withLock {
        FileChannel.open(path.resolveSibling(path.fileName.toString() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }
    private fun load(): ScheduleState {
        if (!Files.exists(path)) return ScheduleState()
        require(Files.size(path) <= MAX_BACKUP_BYTES) { "日程文件过大" }
        return scheduleJson.decodeFromString<ScheduleState>(String(Files.readAllBytes(path), Charsets.UTF_8)).also { validateState(it) }
    }
    override fun read(): ScheduleState = locked { load() }
    override fun update(transform: (ScheduleState) -> ScheduleState): ScheduleState = locked {
        val next = transform(load()).also { validateState(it) }
        val bytes = scheduleJson.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "日程文件过大" }
        val temporary = Files.createTempFile(path.parent, "schedule-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        next
    }
    companion object { private val mutexes = ConcurrentHashMap<Path, ReentrantLock>() }
}

const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

fun validateState(state: ScheduleState) {
    require(state.formatVersion == 2 && state.revision >= 0) { "不支持的数据格式" }
    java.time.ZoneId.of(state.settings.zoneId)
    require(state.items.size <= 10000 && state.proposals.size <= 2000) { "数据条数超过限制" }
    require(state.items.map { it.id }.distinct().size == state.items.size) { "日程编号重复" }
    state.items.forEach { it.validate() }
    val byId = state.items.associateBy { it.id }
    state.items.forEach { item ->
        val seen = mutableSetOf(item.id)
        var parent = item.parentId
        while (parent != null) {
            require(seen.add(parent)) { "子任务关系出现循环" }
            val parentItem = requireNotNull(byId[parent]) { "父任务不存在" }
            require(item.kind == ItemKind.TASK && parentItem.kind == ItemKind.TASK) { "仅任务支持子任务" }
            parent = parentItem.parentId
        }
    }
    require(state.proposals.map { it.id }.distinct().size == state.proposals.size) { "提议编号重复" }
    state.proposals.forEach { require(it.changes.isNotEmpty() && it.basedOnRevision >= 0) { "无效提议" } }
}
