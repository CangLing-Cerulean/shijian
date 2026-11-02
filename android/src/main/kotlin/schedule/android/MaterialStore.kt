package schedule.android

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import schedule.school.CourseMaterial
import java.io.File
import java.security.MessageDigest

data class SavedMaterial(val id: String, val courseId: String, val courseName: String,
    val activityTitle: String, val originalName: String, val storedName: String, val material: CourseMaterial) {
    val exportName: String get() = listOf(courseName, activityTitle, originalName)
        .filter { it.isNotBlank() }.joinToString(" - ").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
}

/** App-private downloads. Only hash-named files described by our own metadata are accessible. */
class MaterialStore(context: Context) {
    private val root = File(context.filesDir, "schedule/materials").apply { mkdirs() }

    @Synchronized fun list(): List<SavedMaterial> = root.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[0-9a-f]{64}\\.json")) }
        .mapNotNull { runCatching { read(it.name.removeSuffix(".json")) }.getOrNull() }
        .filter { file(it).isFile }.sortedWith(compareBy({ it.courseName }, { it.activityTitle }, { it.originalName }))

    @Synchronized fun save(courseId: String, courseName: String, material: CourseMaterial, bytes: ByteArray): SavedMaterial {
        val id = key(courseId, material)
        val saved = SavedMaterial(id, courseId, courseName, material.activityTitle, material.name, "$id.bin", material)
        write(file(saved), bytes)
        val json = JSONObject().put("courseId", courseId).put("courseName", courseName)
            .put("activityTitle", material.activityTitle).put("originalName", material.name)
            .put("storedName", saved.storedName).put("activityId", material.activityId)
            .put("uploadId", material.uploadId).put("referenceId", material.referenceId ?: JSONObject.NULL)
            .put("expectedBytes", material.expectedBytes)
        write(internal("$id.json"), json.toString().toByteArray(Charsets.UTF_8))
        return saved
    }

    fun file(saved: SavedMaterial): File {
        require(saved.id.matches(Regex("[0-9a-f]{64}")) && saved.id == key(saved.courseId, saved.material)) { "无效资料标识" }
        require(saved.storedName == "${saved.id}.bin") { "无效资料路径" }
        return internal(saved.storedName)
    }

    @Synchronized fun delete(saved: SavedMaterial) {
        val target = file(saved)
        val existing = read(saved.id)
        require(existing.storedName == saved.storedName) { "资料记录不匹配" }
        check(!target.exists() || target.delete()) { "删除资料失败" }
        AtomicFile(internal("${saved.id}.json")).delete()
    }

    private fun read(id: String): SavedMaterial {
        val json = JSONObject(AtomicFile(internal("$id.json")).readFully().toString(Charsets.UTF_8))
        val material = CourseMaterial(json.getString("activityId"), json.getLong("uploadId"),
            if (json.isNull("referenceId")) null else json.getLong("referenceId"),
            json.getString("originalName"), json.getLong("expectedBytes"), json.getString("activityTitle"))
        return SavedMaterial(id, json.getString("courseId"), json.getString("courseName"),
            material.activityTitle, material.name, json.getString("storedName"), material).also { file(it) }
    }

    private fun internal(name: String): File {
        val file = File(root, name)
        require(file.canonicalFile.parentFile == root.canonicalFile) { "资料路径越界" }
        return file
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    private fun key(courseId: String, material: CourseMaterial): String {
        val identity = org.json.JSONArray().put(courseId).put(material.activityId).put(material.uploadId)
            .put(material.referenceId ?: JSONObject.NULL).toString()
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
