package com.starpick.app.platform.storage

import android.content.Context
import com.starpick.app.core.model.SourceSite
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.contentKeyOf
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.core.repository.LibraryRepository
import com.starpick.app.core.repository.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * 基础形态的本地存储：一个 JSON 文件 + 内存 StateFlow。
 * 没有引入 Room / KSP，也不写 @Serializable，纯粹是为了让工程现在就能编译跑起来；
 * 数据量变大后换成 SQLDelight 只需替换本文件，core 层契约不动。
 */
class StarpickStore(context: Context) : SourceRepository, LibraryRepository {

    private val file = File(context.applicationContext.filesDir, "starpick.json")
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())

    /**
     * 对外暴露 StateFlow 而不是 Flow.map：
     *
     * 普通 Flow + `collectAsState(initial = emptyList())` 的首帧一定取 initial，第二帧才拿到真实值，
     * 于是每个页面都会先渲染一次「没有可用的采集源 / 片匣是空的」再跳成正常内容。
     * StateFlow 可以让 Compose 用它的 value 当首帧，从根上消掉这次闪烁。
     */
    private val _sources = MutableStateFlow(state.value.sources)
    private val _favorites = MutableStateFlow(state.value.favorites)
    private val _progress = MutableStateFlow(state.value.progress.sortedByDescending { it.updatedAt })
    private val _recent = MutableStateFlow(state.value.recent)
    private val _searchHistory = MutableStateFlow(state.value.searchHistory)

    override val sources: StateFlow<List<SourceSite>> = _sources.asStateFlow()
    override val favorites: StateFlow<List<VodItem>> = _favorites.asStateFlow()
    override val progress: StateFlow<List<WatchProgress>> = _progress.asStateFlow()
    val recent: StateFlow<List<VodItem>> = _recent.asStateFlow()
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    override suspend fun add(name: String, baseUrl: String) {
        val site = SourceSite(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
        )
        mutate { it.copy(sources = it.sources + site) }
    }

    override suspend fun remove(id: String) = mutate { snapshot ->
        snapshot.copy(sources = snapshot.sources.filterNot { it.id == id })
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = mutate { snapshot ->
        snapshot.copy(
            sources = snapshot.sources.map { if (it.id == id) it.copy(enabled = enabled) else it },
        )
    }

    override suspend fun isFavorite(key: String): Boolean =
        state.value.favorites.any { it.key == key }

    override suspend fun toggleFavorite(item: VodItem) = mutate { snapshot ->
        val exists = snapshot.favorites.any { it.key == item.key }
        snapshot.copy(
            favorites = if (exists) {
                snapshot.favorites.filterNot { it.key == item.key }
            } else {
                snapshot.favorites + item
            },
        )
    }

    /** 打开详情时缓存一份完整条目，供「继续观看」重新进入播放页。同一部剧的不同源只留最新一份。 */
    suspend fun rememberItem(item: VodItem) = mutate { snapshot ->
        snapshot.copy(
            recent = (
                listOf(item) + snapshot.recent.filterNot { it.contentKey == item.contentKey }
                ).take(RECENT_LIMIT),
        )
    }

    fun itemFor(key: String): VodItem? =
        state.value.recent.firstOrNull { it.contentKey == key }

    override suspend fun progressFor(key: String): WatchProgress? =
        state.value.progress.firstOrNull { it.key == key }

    override suspend fun saveProgress(record: WatchProgress) = mutate { snapshot ->
        snapshot.copy(
            progress = snapshot.progress.filterNot { it.key == record.key } + record,
        )
    }

    override suspend fun clearProgress(key: String) = clearProgress(setOf(key))

    /** 批量删除观看记录：一次改内存 + 一次落盘，编辑模式下多选删除走这里。 */
    suspend fun clearProgress(keys: Collection<String>) = mutate { snapshot ->
        snapshot.copy(progress = snapshot.progress.filterNot { it.key in keys })
    }

    /** 搜索历史：最近搜过的排前面，重复关键词去重后提升到首位。 */
    suspend fun addSearchHistory(keyword: String) {
        val key = keyword.trim()
        if (key.isEmpty()) return
        mutate { snapshot ->
            snapshot.copy(
                searchHistory = (
                    listOf(key) + snapshot.searchHistory.filterNot { it.equals(key, ignoreCase = true) }
                    ).take(SEARCH_HISTORY_LIMIT),
            )
        }
    }

    suspend fun clearSearchHistory() = mutate { it.copy(searchHistory = emptyList()) }

    private suspend fun mutate(transform: (State) -> State) {
        mutex.withLock {
            val next = transform(state.value)
            state.value = next
            publish(next)
            withContext(Dispatchers.IO) {
                file.writeText(next.toJson().toString())
            }
        }
    }

    /** 内存状态变更后同步刷新对外的 StateFlow，保证 UI 立刻拿到新值。 */
    private fun publish(snapshot: State) {
        _sources.value = snapshot.sources
        _favorites.value = snapshot.favorites
        _progress.value = snapshot.progress.sortedByDescending { it.updatedAt }
        _recent.value = snapshot.recent
        _searchHistory.value = snapshot.searchHistory
    }

    private fun load(): State = runCatching {
        if (!file.exists()) return State()
        val text = file.readText()
        if (text.isBlank()) State() else Json.parseToJsonElement(text).jsonObject.toState()
    }.getOrDefault(State())

    private data class State(
        val sources: List<SourceSite> = emptyList(),
        val favorites: List<VodItem> = emptyList(),
        val progress: List<WatchProgress> = emptyList(),
        val recent: List<VodItem> = emptyList(),
        val searchHistory: List<String> = emptyList(),
    )

    private fun State.toJson(): JsonObject = buildJsonObject {
        put("sources", JsonArray(sources.map { it.toJson() }))
        put("favorites", JsonArray(favorites.map { it.toJson() }))
        put("progress", JsonArray(progress.map { it.toJson() }))
        put("recent", JsonArray(recent.map { it.toJson() }))
        put("searchHistory", JsonArray(searchHistory.map { JsonPrimitive(it) }))
    }

    private fun JsonObject.toState(): State = State(
        sources = array("sources").mapNotNull { (it as? JsonObject)?.toSourceSite() },
        favorites = array("favorites").mapNotNull { (it as? JsonObject)?.toVodItem() },
        // 老版本把 key 存成 sourceId:vodId，同一部剧的每个源各一条。
        // 加载时统一折算成内容 key 并按最新一条去重，用户不需要手动清理旧记录。
        progress = array("progress")
            .mapNotNull { (it as? JsonObject)?.toProgress() }
            .filter { it.title.isNotBlank() }
            .map { it.copy(key = contentKeyOf(it.title)) }
            .groupBy { it.key }
            .values.mapNotNull { group -> group.maxByOrNull { it.updatedAt } },
        recent = array("recent").mapNotNull { (it as? JsonObject)?.toVodItem() },
        searchHistory = array("searchHistory")
            .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
            .take(SEARCH_HISTORY_LIMIT),
    )

    private fun SourceSite.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("enabled", JsonPrimitive(enabled))
    }

    private fun JsonObject.toSourceSite(): SourceSite = SourceSite(
        id = str("id"),
        name = str("name"),
        baseUrl = str("baseUrl"),
        enabled = bool("enabled", fallback = true),
    )

    private fun VodItem.toJson(): JsonObject = buildJsonObject {
        put("sourceId", sourceId)
        put("sourceName", sourceName)
        put("vodId", vodId)
        put("title", title)
        put("cover", cover)
        put("remark", remark)
        put("category", category)
        put("area", area)
        put("year", year)
        put("description", description)
        put("playFrom", playFrom)
        put("playUrl", playUrl)
    }

    private fun JsonObject.toVodItem(): VodItem = VodItem(
        sourceId = str("sourceId"),
        sourceName = str("sourceName"),
        vodId = str("vodId"),
        title = str("title"),
        cover = str("cover"),
        remark = str("remark"),
        category = str("category"),
        area = str("area"),
        year = str("year"),
        description = str("description"),
        playFrom = str("playFrom"),
        playUrl = str("playUrl"),
    )

    private fun WatchProgress.toJson(): JsonObject = buildJsonObject {
        put("key", key)
        put("title", title)
        put("cover", cover)
        put("groupIndex", JsonPrimitive(groupIndex))
        put("episodeIndex", JsonPrimitive(episodeIndex))
        put("episodeName", episodeName)
        put("positionMs", JsonPrimitive(positionMs))
        put("durationMs", JsonPrimitive(durationMs))
        put("updatedAt", JsonPrimitive(updatedAt))
    }

    private fun JsonObject.toProgress(): WatchProgress = WatchProgress(
        key = str("key"),
        title = str("title"),
        cover = str("cover"),
        groupIndex = int("groupIndex"),
        episodeIndex = int("episodeIndex"),
        episodeName = str("episodeName"),
        positionMs = long("positionMs"),
        durationMs = long("durationMs"),
        updatedAt = long("updatedAt"),
    )

    private fun JsonObject.array(key: String): List<JsonElement> =
        (get(key) as? JsonArray)?.toList() ?: emptyList()

    private fun JsonObject.str(key: String): String {
        val element = get(key) ?: return ""
        if (element === JsonNull) return ""
        return (element as? JsonPrimitive)?.content.orEmpty()
    }

    private fun JsonObject.long(key: String): Long = str(key).toLongOrNull() ?: 0L
    private fun JsonObject.int(key: String): Int = str(key).toIntOrNull() ?: 0
    private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
        str(key).toBooleanStrictOrNull() ?: fallback

    companion object {
        private const val RECENT_LIMIT = 30
        private const val SEARCH_HISTORY_LIMIT = 12
    }
}
