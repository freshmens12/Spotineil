package com.spotineil.feature.player

import com.spotineil.core.designsystem.theme.sheetContainer
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.model.QueueItem
import com.spotineil.core.player.SleepTimerOption
import com.spotineil.core.player.SleepTimerState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val error by viewModel.queueError.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadQueue() }

    ModalBottomSheet(containerColor = sheetContainer(), onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().heightIn(min = 240.dp)) {
            Text(stringResource(R.string.player_queue), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            val q = queue
            when {
                error != null -> ErrorState(error!!, onRetry = { viewModel.loadQueue() })
                q == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    q.current?.let {
                        Text(stringResource(R.string.player_now_playing), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                        QueueRow(it, dragHandle = null)
                    }
                    Text(
                        stringResource(if (q.isReorderable) R.string.player_next_up_reorder else R.string.player_next_up),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    )
                    if (q.upcoming.isEmpty()) {
                        Text(stringResource(R.string.player_queue_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val listState = rememberLazyListState()
                    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                        viewModel.moveQueueItem(from.index, to.index)
                    }
                    LazyColumn(state = listState) {
                        items(q.upcoming, key = { it.key }) { item ->
                            if (q.isReorderable) {
                                ReorderableItem(reorderState, key = item.key) { dragging ->
                                    Surface(tonalElevation = if (dragging) 8.dp else 0.dp) {
                                        QueueRow(item, dragHandle = {
                                            Icon(Icons.Rounded.DragHandle, stringResource(R.string.player_drag), Modifier.draggableHandle().padding(12.dp))
                                        })
                                    }
                                }
                            } else {
                                QueueRow(item, dragHandle = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, dragHandle: (@Composable () -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImage(item.imageUrl, null, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artists, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        dragHandle?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val outputs = rememberAudioOutputs()
    ModalBottomSheet(containerColor = sheetContainer(), onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.player_connect_device), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                stringResource(R.string.player_connect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(stringResource(R.string.player_available_now), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp))
            outputs.forEach { out ->
                DeviceRow(iconFor(out.type), out.name ?: stringResource(R.string.player_this_phone)) { openOutputSwitcher(context) }
            }
            if (outputs.size <= 1) {
                Text(stringResource(R.string.player_no_outputs), Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DeviceRow(Icons.Rounded.Speaker, stringResource(R.string.player_choose_output), highlight = true) { openOutputSwitcher(context) }
            DeviceRow(Icons.Rounded.Bluetooth, stringResource(R.string.player_pair_device)) { openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) }
            DeviceRow(Icons.Rounded.Cast, stringResource(R.string.player_cast)) { openSettings(context, Settings.ACTION_CAST_SETTINGS) }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, label: String, highlight: Boolean = false, onClick: () -> Unit) {
    val color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(16.dp))
        Text(label, color = color)
    }
}

/** A sound output the phone can play to right now. [name] is null for the phone's own speaker. */
private data class AudioOutput(val type: Int, val name: String?)

private val shownOutputTypes = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HDMI,
)

private fun readAudioOutputs(context: Context): List<AudioOutput> {
    val audio = context.getSystemService(AudioManager::class.java) ?: return emptyList()
    return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type in shownOutputTypes }
        .map { AudioOutput(it.type, if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) null else it.productName?.toString()?.takeIf(String::isNotBlank)) }
        .distinct()
        .sortedBy { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
}

/** The connected outputs (phone speaker, Bluetooth, wired, USB, HDMI), updated as they come and go. */
@Composable
private fun rememberAudioOutputs(): List<AudioOutput> {
    val context = LocalContext.current
    var outputs by remember { mutableStateOf(readAudioOutputs(context)) }
    DisposableEffect(context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { outputs = readAudioOutputs(context) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { outputs = readAudioOutputs(context) }
        }
        audio?.registerAudioDeviceCallback(callback, null)
        onDispose { audio?.unregisterAudioDeviceCallback(callback) }
    }
    return outputs
}

private fun iconFor(type: Int): ImageVector = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> Icons.Rounded.Bluetooth
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Icons.Rounded.Headphones
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> Icons.Rounded.Usb
    AudioDeviceInfo.TYPE_HDMI -> Icons.Rounded.Tv
    else -> Icons.Rounded.PhoneAndroid
}

/** Android's own "Play on" panel: pick a Bluetooth speaker, headphones or cast device to route the sound to. */
private fun openOutputSwitcher(context: Context) {
    val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
        .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
    if (!tryStart(context, panel)) openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
}

private fun openSettings(context: Context, action: String) {
    tryStart(context, Intent(action))
}

private fun tryStart(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(current: SleepTimerState, onSelect: (SleepTimerOption?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(containerColor = sheetContainer(), onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.player_sleep_timer), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            current.endsAtMs?.let {
                val minutesLeft = ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
                Text(stringResource(R.string.player_sleep_remaining, minutesLeft), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary)
            }
            if (current.endOfTrack) {
                Text(stringResource(R.string.player_sleep_end_of_track_active), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary)
            }
            listOf(5, 15, 30, 45, 60).forEach { m ->
                SheetOption(stringResource(R.string.player_sleep_minutes, m)) { onSelect(SleepTimerOption.Minutes(m)); onDismiss() }
            }
            SheetOption(stringResource(R.string.player_sleep_end_of_track)) { onSelect(SleepTimerOption.EndOfTrack); onDismiss() }
            if (current.isActive) {
                SheetOption(stringResource(R.string.player_sleep_off)) { onSelect(null); onDismiss() }
            }
        }
    }
}

@Composable
private fun SheetOption(label: String, onClick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
}
