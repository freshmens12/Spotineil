package com.spotineil.core.data

import com.spotineil.core.data.db.CachedResponseDao
import com.spotineil.core.data.db.CachedResponseEntity
import com.spotineil.core.data.db.LyricsDao
import com.spotineil.core.data.db.LyricsEntity
import com.spotineil.core.data.db.RecentSearchDao
import com.spotineil.core.data.db.RecentSearchEntity
import com.spotineil.core.network.auth.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeLyricsDao : LyricsDao {
    val rows = mutableMapOf<String, LyricsEntity>()
    override suspend fun get(key: String) = rows[key]
    override suspend fun upsert(entity: LyricsEntity) { rows[entity.trackKey] = entity }
}

class FakeRecentSearchDao : RecentSearchDao {
    private val rows = MutableStateFlow<Map<String, Long>>(emptyMap())
    override fun observe(limit: Int): Flow<List<RecentSearchEntity>> = rows.map { m ->
        m.entries.sortedByDescending { it.value }.take(limit).map { RecentSearchEntity(it.key, it.value) }
    }
    override suspend fun upsert(entity: RecentSearchEntity) { rows.value = rows.value + (entity.query to entity.timestamp) }
    override suspend fun delete(query: String) { rows.value = rows.value - query }
    override suspend fun trim(keep: Int) {
        rows.value = rows.value.entries.sortedByDescending { it.value }.take(keep).associate { it.key to it.value }
    }
    override suspend fun clear() { rows.value = emptyMap() }
}

class FakeCachedResponseDao : CachedResponseDao {
    private val rows = MutableStateFlow<Map<String, CachedResponseEntity>>(emptyMap())
    override suspend fun get(key: String) = rows.value[key]
    override fun observe(key: String): Flow<CachedResponseEntity?> = rows.map { it[key] }
    override suspend fun upsert(entity: CachedResponseEntity) { rows.value = rows.value + (entity.key to entity) }
    override suspend fun delete(key: String) { rows.value = rows.value - key }
}

class TickingClock(var now: Long = 1_000L) : Clock {
    override fun now(): Long = now++
}
