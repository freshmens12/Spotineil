package com.spotineil.feature.player

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
import androidx.compose.material.icons.rounded.Computer
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.model.Device
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
fun DevicesSheet(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadDevices() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.player_connect_device), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                stringResource(R.string.player_connect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val list = devices
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(stringResource(R.string.player_no_devices), Modifier.padding(16.dp))
                else -> list.forEach { d -> DeviceRow(d) { viewModel.transferTo(d); onDismiss() } }
            }
            TextButton(onClick = { viewModel.loadDevices() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.player_refresh))
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, onClick: () -> Unit) {
    val icon = when (device.type.lowercase()) {
        "computer" -> Icons.Rounded.Computer
        "smartphone" -> Icons.Rounded.PhoneAndroid
        "tv", "castvideo" -> Icons.Rounded.Tv
        else -> Icons.Rounded.Speaker
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !device.isRestricted && device.id != null, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (device.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(device.name, color = if (device.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (device.isActive) Text(stringResource(R.string.player_listening_on), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(current: SleepTimerState, onSelect: (SleepTimerOption?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
