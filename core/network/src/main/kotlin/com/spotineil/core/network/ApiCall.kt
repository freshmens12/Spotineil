package com.spotineil.core.network

import com.spotineil.core.model.SpotineilException
import com.spotineil.core.network.model.SpotifyErrorEnvelope
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** Executes [block], translating transport and HTTP failures into [SpotineilException]s. */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: SpotineilException) {
    throw e
} catch (e: HttpException) {
    throw e.toDomain()
} catch (e: IOException) {
    throw SpotineilException.Network(e)
} catch (e: Exception) {
    throw SpotineilException.Unknown(e)
}

private val errorJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun HttpException.toDomain(): SpotineilException {
    val resp = response()
    return when (val code = code()) {
        401 -> SpotineilException.Unauthorized()
        403 -> {
            val reason = runCatching {
                resp?.errorBody()?.string()?.let { errorJson.decodeFromString<SpotifyErrorEnvelope>(it).error?.reason }
            }.getOrNull()
            SpotineilException.Forbidden(reason)
        }
        404 -> SpotineilException.NotFound()
        429 -> SpotineilException.RateLimited(
            resp?.headers()?.get("Retry-After")?.toLongOrNull() ?: 1,
        )
        in 500..599 -> SpotineilException.Server(code)
        else -> SpotineilException.Unknown(this)
    }
}
