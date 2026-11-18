package com.spotineil.core.data.cache

import com.spotineil.core.data.db.CachedResponseDao
import com.spotineil.core.data.db.CachedResponseEntity
import com.spotineil.core.network.auth.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Small key/value cache of serialized domain models, used for offline-first reads. */
@Singleton
class JsonCache @Inject constructor(
    private val dao: CachedResponseDao,
    private val json: Json,
    private val clock: Clock,
) {
    fun <T> observe(key: String, serializer: KSerializer<T>): Flow<T?> =
        dao.observe(key).map { entity -> entity?.let { decode(it.json, serializer) } }

    suspend fun <T> get(key: String, serializer: KSerializer<T>, maxAgeMs: Long = Long.MAX_VALUE): T? {
        val entity = dao.get(key) ?: return null
        if (clock.now() - entity.updatedAt > maxAgeMs) return null
        return decode(entity.json, serializer)
    }

    suspend fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        dao.upsert(CachedResponseEntity(key, json.encodeToString(serializer, value), clock.now()))
    }

    suspend fun remove(key: String) = dao.delete(key)

    private fun <T> decode(raw: String, serializer: KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
}
