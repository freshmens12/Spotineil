package com.spotineil.core.player.di

import com.spotineil.core.player.DefaultPlaybackController
import com.spotineil.core.player.PlaybackController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {
    @Binds
    abstract fun playbackController(impl: DefaultPlaybackController): PlaybackController
}
