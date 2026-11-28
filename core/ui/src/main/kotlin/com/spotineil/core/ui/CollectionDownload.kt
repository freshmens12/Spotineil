package com.spotineil.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.spotineil.core.designsystem.theme.dialogContainer
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.player.youtube.YouTubeDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CollectionDownloadViewModel @Inject constructor(private val downloader: YouTubeDownloader) : ViewModel() {
    fun downloadable(songs: List<PlayableMedia>): List<PlayableMedia> = songs.filter(downloader::canDownload)
    fun download(name: String, songs: List<PlayableMedia>): Int = downloader.downloadCollection(name, songs)
}

/**
 * Downloads a whole album or playlist into its own folder named [name] (inside the downloads folder),
 * the songs numbered in order and with a playlist file. Asks first; hidden when none can be saved.
 */
@Composable
fun DownloadCollectionButton(
    name: String,
    songs: List<PlayableMedia>,
    modifier: Modifier = Modifier,
    viewModel: CollectionDownloadViewModel = hiltViewModel(),
) {
    val downloadable = remember(songs) { viewModel.downloadable(songs) }
    if (downloadable.isEmpty() || name.isBlank()) return
    var asking by remember { mutableStateOf(false) }
    IconButton(onClick = { asking = true }, modifier = modifier.testTag("download_collection")) {
        Icon(Icons.Rounded.Download, stringResource(R.string.ui_download_all))
    }
    if (asking) {
        AlertDialog(
            containerColor = dialogContainer(),
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.ui_download_all_title, name)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.ui_download_all_body,
                        downloadable.size,
                        downloadable.size,
                        com.spotineil.core.player.youtube.YouTubeDownloader.folderName(name),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { asking = false; viewModel.download(name, downloadable) }, modifier = Modifier.testTag("download_collection_confirm")) {
                    Text(stringResource(R.string.ui_download))
                }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
}
