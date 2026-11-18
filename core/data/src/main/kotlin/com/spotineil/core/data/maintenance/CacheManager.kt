package com.spotineil.core.data.maintenance

import com.spotineil.core.common.AppDispatcher
import com.spotineil.core.common.Dispatcher
import com.spotineil.core.data.db.SpotineilDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Cache

/** Clears cached data. Image caches (Coil) are cleared by :app, which owns the ImageLoader. */
@Singleton
class CacheManager @Inject constructor(
    private val db: SpotineilDatabase,
    private val httpCache: Cache,
    @Dispatcher(AppDispatcher.IO) private val io: CoroutineDispatcher,
) {
    suspend fun clearAll(includeUserData: Boolean) = withContext(io) {
        if (includeUserData) {
            db.clearAllTables()
        } else {
            // Keep recent searches; everything else is re-fetchable.
            db.openHelper.writableDatabase.apply {
                execSQL("DELETE FROM library_items")
                execSQL("DELETE FROM library_remote_keys")
                execSQL("DELETE FROM cached_responses")
                execSQL("DELETE FROM lyrics_cache")
            }
        }
        runCatching { httpCache.evictAll() }
        Unit
    }
}
