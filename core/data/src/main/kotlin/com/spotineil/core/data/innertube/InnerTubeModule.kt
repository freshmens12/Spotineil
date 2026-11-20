package com.spotineil.core.data.innertube

import android.content.Context
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeLocale
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.util.Locale
import javax.inject.Singleton
import com.metrolist.innertubex.InnerTubeLogger
import com.spotineil.core.data.BuildConfig

@Singleton
class InnerTubeHttpClientOwner private constructor(
    val httpClient: HttpClient,
    val innerTube: InnerTube,
    val logger: InnerTubeLogger,
) {
    fun close() {
        innerTube.close()
        httpClient.close()
    }

    companion object {
        fun create(locale: YouTubeLocale): InnerTubeHttpClientOwner {
            val httpClient = HttpClient(OkHttp) {
                install(ContentNegotiation) {
                    json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 20_000
                }
            }
            val debugLogger = if (BuildConfig.DEBUG) InnerTubeLogger { event ->
                android.util.Log.d("InnerTubeX", "${event.tag}: ${event.message}")
            } else InnerTubeLogger.NONE
            val innerTube = InnerTube(httpClient = httpClient, logger = debugLogger)
            innerTube.locale = locale
            return InnerTubeHttpClientOwner(httpClient, innerTube, debugLogger)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object InnerTubeModule {
    @Provides
    @Singleton
    fun owner(@ApplicationContext context: Context): InnerTubeHttpClientOwner {
        val deviceLocale = Locale.getDefault()
        val locale = YouTubeLocale(
            gl = deviceLocale.country.uppercase(Locale.ROOT).takeIf { it.length == 2 } ?: "US",
            hl = deviceLocale.toLanguageTag(),
        )
        return InnerTubeHttpClientOwner.create(locale)
    }

    @Provides fun httpClient(owner: InnerTubeHttpClientOwner): HttpClient = owner.httpClient

    @Provides fun innerTube(owner: InnerTubeHttpClientOwner): InnerTube = owner.innerTube
    @Provides fun innerTubeLogger(owner: InnerTubeHttpClientOwner): InnerTubeLogger = owner.logger

    @Provides
    @Singleton
    fun extractor(httpClient: HttpClient, innerTube: InnerTube, logger: InnerTubeLogger): InnerTubeExtractor {
        val configStore = RemotePlayerConfigStore(
            httpClient = httpClient,
            repository = PlayerConfigRepository.disabled(),
            logger = logger,
        )
        val cipher = YouTubeCipherService(httpClient, configStore, logger)
        val parser = YtConfigParserImpl(httpClient, innerTube, configStore, logger, cipher)
        return InnerTubeExtractor(
            configParser = parser,
            cipherService = cipher,
            innerTube = innerTube,
            logger = logger,
        )
    }
}
