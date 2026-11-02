package schedule.study

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import schedule.ai.ChatEntry
import schedule.core.scheduleJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable data class SavedConversation(val id: String = UUID.randomUUID().toString(), val updatedAt: Long = System.currentTimeMillis(), val messages: List<ChatEntry>) {
    val title: String get() = messages.firstOrNull { it.role == "user" }?.text?.take(50) ?: "对话"
}
@Serializable data class SavedSummary(val id: String = UUID.randomUUID().toString(), val courseName: String,
    val lessonStart: Long?, val lessonEnd: Long? = null, val createdAt: Long = System.currentTimeMillis(),
    val lessonId: String? = null, val lmsCourseId: String? = null, val sections: List<SummarySection>)
@Serializable data class LessonMaterial(val itemId: String, val courseId: String, val courseName: String, val material: schedule.school.CourseMaterial) {
    val id: String get() = "$itemId:$courseId:${material.activityId}:${material.uploadId}:${material.referenceId}"
}
@Serializable data class StudyLibrary(val conversations: List<SavedConversation> = emptyList(), val summaries: List<SavedSummary> = emptyList(), val lessonMaterials: List<LessonMaterial> = emptyList())

/** Atomic local library; deletion is explicit and saving never silently truncates history. */
class LibraryStore(private val path: Path) {
    fun read(): StudyLibrary = synchronized(lock) { load() }
    private fun load(): StudyLibrary = if (Files.exists(path)) scheduleJson.decodeFromString(String(Files.readAllBytes(path), Charsets.UTF_8)) else StudyLibrary()
    fun update(change: (StudyLibrary) -> StudyLibrary): StudyLibrary = synchronized(lock) {
        val next = change(load())
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "library-", ".tmp")
        try {
            Files.write(temporary, scheduleJson.encodeToString(next).toByteArray(Charsets.UTF_8))
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        next
    }
    fun saveSummary(summary: SavedSummary, materials: List<LessonMaterial> = emptyList()) = update {
        it.copy(summaries = it.summaries.filterNot { old -> old.id == summary.id } + summary,
            lessonMaterials = (it.lessonMaterials + materials).distinctBy { material -> material.id })
    }
    fun linkMaterial(material: LessonMaterial) = update { it.copy(lessonMaterials = (it.lessonMaterials + material).distinctBy { linked -> linked.id }) }
    fun unlinkMaterial(id: String) = update { it.copy(lessonMaterials = it.lessonMaterials.filterNot { linked -> linked.id == id }) }
    fun deleteSummaries(ids: Set<String>) = update { it.copy(summaries = it.summaries.filterNot { s -> s.id in ids }) }
    companion object { private val lock = Any() }
}
