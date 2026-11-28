package com.spotineil.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.SettingsRepository
import com.spotineil.core.model.CoverStyle
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CoverStyleViewModel @Inject constructor(
    private val settings: SettingsRepository,
    player: PlaybackController,
) : ViewModel() {
    val style: StateFlow<CoverStyle?> = settings.settings.map { it.coverStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The cover of the song playing now, to preview the styles with. */
    val nowPlayingImage: StateFlow<String?> = player.state.map { it.track?.imageUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(style: CoverStyle) = viewModelScope.launch { settings.setCoverStyle(style) }
}

/** Picks how the Now playing cover moves, each style shown live on the cover of the song playing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverStyleSheet(
    previewImageUrl: String?,
    onDismiss: () -> Unit,
    viewModel: CoverStyleViewModel = hiltViewModel(),
) {
    val current by viewModel.style.collectAsStateWithLifecycle()
    val playingImage by viewModel.nowPlayingImage.collectAsStateWithLifecycle()
    val image = previewImageUrl ?: playingImage
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .testTag("cover_style_sheet"),
        ) {
            Text(stringResource(R.string.ui_cover_style), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.ui_cover_style_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            CoverStyle.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { style ->
                        StyleTile(style, selected = style == current, image, Modifier.weight(1f)) { viewModel.select(style) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StyleTile(style: CoverStyle, selected: Boolean, imageUrl: String?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag("cover_style_${style.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
            LivingCover(imageUrl, null, isPlaying = true, Modifier.matchParentSize(), RoundedCornerShape(8.dp), style = style)
        }
        Text(
            stringResource(style.labelRes()),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@StringRes
fun CoverStyle.labelRes(): Int = when (this) {
    CoverStyle.Still -> R.string.ui_cover_still
    CoverStyle.Living -> R.string.ui_cover_living
    CoverStyle.Vinyl -> R.string.ui_cover_vinyl
    CoverStyle.Disc -> R.string.ui_cover_disc
    CoverStyle.Beat -> R.string.ui_cover_beat
    CoverStyle.Tilt -> R.string.ui_cover_tilt
    CoverStyle.Ripples -> R.string.ui_cover_ripples
}

@StringRes
fun CoverStyle.descriptionRes(): Int = when (this) {
    CoverStyle.Still -> R.string.ui_cover_still_desc
    CoverStyle.Living -> R.string.ui_cover_living_desc
    CoverStyle.Vinyl -> R.string.ui_cover_vinyl_desc
    CoverStyle.Disc -> R.string.ui_cover_disc_desc
    CoverStyle.Beat -> R.string.ui_cover_beat_desc
    CoverStyle.Tilt -> R.string.ui_cover_tilt_desc
    CoverStyle.Ripples -> R.string.ui_cover_ripples_desc
}
