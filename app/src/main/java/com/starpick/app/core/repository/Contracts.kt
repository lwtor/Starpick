package com.starpick.app.core.repository

import com.starpick.app.core.model.SourceSite
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import kotlinx.coroutines.flow.Flow

/**
 * 只定义契约，不绑定任何存储实现。
 * 将来扩展桌面端时，换一个实现即可，core 层一行都不用改 —— 这是目前唯一预留的扩展缝。
 */
interface SourceRepository {
    val sources: Flow<List<SourceSite>>
    suspend fun add(name: String, baseUrl: String)
    suspend fun remove(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
}

interface LibraryRepository {
    val favorites: Flow<List<VodItem>>
    val progress: Flow<List<WatchProgress>>
    suspend fun isFavorite(key: String): Boolean
    suspend fun toggleFavorite(item: VodItem)
    suspend fun saveProgress(record: WatchProgress)
    suspend fun progressFor(key: String): WatchProgress?
    suspend fun clearProgress(key: String)
}
