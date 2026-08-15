package me.rerere.rikkahub.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import kotlin.uuid.Uuid

// UUID 格式检测：兼容旧版上游数据（subtitle 字段曾经存储的是 nodeId UUID 字符串）
private val UUID_REGEX = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
private fun String.isUuid(): Boolean = UUID_REGEX.matches(this)


data class NodeFavoriteListItem(
    val id: String,
    val refKey: String,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val conversationTitle: String,
    val preview: String,
    val questionPreview: String?,
    val createdAt: Long,
)

class FavoriteVM(
    private val favoriteRepository: FavoriteRepository,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    val nodeFavorites = combine(
        favoriteRepository.listByType(FavoriteType.NODE),
        conversationRepository.observeExistingConversationIds(),
    ) { favorites, existingConvIds ->
        favorites.mapNotNull { entity ->
            // 兜底：conversationId 已不存在的项直接过滤掉，避免死链
            val convId = entity.refKey
                .removePrefix("node:")
                .substringBefore(":")
            if (convId !in existingConvIds) return@mapNotNull null

            val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return@mapNotNull null
            val meta = NodeFavoriteAdapter.decodeMeta(entity)

            NodeFavoriteListItem(
                id = entity.id,
                refKey = entity.refKey,
                conversationId = ref.conversationId,
                nodeId = ref.nodeId,
                conversationTitle = meta?.title.orEmpty(),
                preview = meta?.previewText ?: "",
                // 过滤旧版上游数据：subtitle 曾经存的是 nodeId (UUID)，不是提问文本
                questionPreview = meta?.subtitle?.takeUnless { it.isUuid() },
                createdAt = entity.createdAt,
            )
        }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun removeFavorite(refKey: String) {
        viewModelScope.launch {
            favoriteRepository.deleteByRefKey(refKey)
        }
    }

    suspend fun getEntityByRefKey(refKey: String): FavoriteEntity? {
        return favoriteRepository.getByRefKey(refKey)
    }

    fun restoreFavorite(entity: FavoriteEntity) {
        viewModelScope.launch {
            favoriteRepository.upsert(entity)
        }
    }
}
