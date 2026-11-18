package com.spotineil.core.data.paging

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.spotineil.core.model.Page
import com.spotineil.core.network.apiCall
import kotlinx.coroutines.flow.Flow

/** PagingSource over Spotify's offset/limit pagination. */
class OffsetPagingSource<T : Any>(
    private val pageSize: Int,
    /** Spotify caps some endpoints (e.g. search) at offset+limit <= 1000. */
    private val maxOffset: Int = Int.MAX_VALUE,
    private val fetch: suspend (offset: Int, limit: Int) -> Page<T>,
) : PagingSource<Int, T>() {

    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val offset = params.key ?: 0
        val limit = minOf(params.loadSize, pageSize, maxOffset - offset)
        if (limit <= 0) return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        return try {
            val page = apiCall { fetch(offset, limit) }
            val next = page.nextOffset?.takeIf { it < maxOffset && page.items.isNotEmpty() }
            LoadResult.Page(
                data = page.items,
                prevKey = null,
                nextKey = next,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

fun <T : Any> offsetPager(
    pageSize: Int = 50,
    maxOffset: Int = Int.MAX_VALUE,
    fetch: suspend (offset: Int, limit: Int) -> Page<T>,
): Flow<PagingData<T>> = Pager(
    config = PagingConfig(pageSize = pageSize, initialLoadSize = pageSize, enablePlaceholders = false),
    pagingSourceFactory = { OffsetPagingSource(pageSize, maxOffset, fetch) },
).flow
