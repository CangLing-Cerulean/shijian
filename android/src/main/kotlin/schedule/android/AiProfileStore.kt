package schedule.android

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import schedule.ai.AiConfiguration
import schedule.ai.AiEndpoints
import schedule.core.scheduleJson
import java.util.UUID

@Serializable
data class AiProfile(val id: String, val name: String, val config: AiConfiguration, val key: String) {
    override fun toString() = "AiProfile(id=$id, name=$name, key=redacted)"
}

/** Both the registry and the legacy entry are accessed exclusively through the encrypted vault. */
internal class AiProfileStore(private val read: (String) -> String?, private val write: (String, String?) -> Unit) {
    @Serializable
    private data class Registry(val profiles: List<AiProfile> = emptyList(), val activeId: String? = null)

    fun profiles(): List<AiProfile> = synchronized(lock) { load().profiles }
    fun active(): AiProfile? = synchronized(lock) { load().let { state -> state.profiles.find { it.id == state.activeId } } }

    fun save(id: String?, name: String, config: AiConfiguration, key: String): String = synchronized(lock) {
        config.validate()
        require(name.isNotBlank()) { "请输入接口名称" }
        val state = load()
        val previous = id?.let { selected -> requireNotNull(state.profiles.find { it.id == selected }) { "AI接口不存在" } }
        val resolvedKey = key.ifBlank {
            previous?.takeIf { AiEndpoints.chat(it.config.endpoint) == AiEndpoints.chat(config.endpoint) }?.key.orEmpty()
        }
        require(resolvedKey.isNotBlank() && '\r' !in resolvedKey && '\n' !in resolvedKey) { "API密钥无效" }
        val profile = AiProfile(previous?.id ?: UUID.randomUUID().toString(), name.trim(), config, resolvedKey)
        val profiles = if (previous == null) state.profiles + profile else state.profiles.map { if (it.id == profile.id) profile else it }
        persist(Registry(profiles, profile.id))
        profile.id
    }

    fun select(id: String) = synchronized(lock) {
        val state = load()
        require(state.profiles.any { it.id == id }) { "AI接口不存在" }
        persist(state.copy(activeId = id))
    }

    fun delete(id: String) = synchronized(lock) {
        val state = load()
        require(state.profiles.any { it.id == id }) { "AI接口不存在" }
        val profiles = state.profiles.filterNot { it.id == id }
        persist(Registry(profiles, if (state.activeId == id) profiles.firstOrNull()?.id else state.activeId))
    }

    private fun load(): Registry {
        read("ai.profiles")?.let { return scheduleJson.decodeFromString<Registry>(it) }
        val legacy = read("ai.configuration") ?: return Registry()
        val json = scheduleJson.parseToJsonElement(legacy).jsonObject
        val profile = AiProfile("default", "默认接口", scheduleJson.decodeFromJsonElement<AiConfiguration>(JsonObject(json.filterKeys { it != "key" })), json.getValue("key").jsonPrimitive.content)
        return Registry(listOf(profile), profile.id).also(::persist)
    }

    private fun persist(state: Registry) {
        write("ai.profiles", scheduleJson.encodeToString(state))
        write("ai.configuration", null)
    }

    private companion object { val lock = Any() }
}
