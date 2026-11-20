package com.spotineil.app.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotineil.app.BuildConfig
import com.spotineil.app.R
import com.spotineil.app.update.UpdateUi
import com.spotineil.app.update.UpdateViewModel

/** "Update available" prompt shown when the app opens; nothing happens until the user agrees. */
@Composable
fun UpdateDialog(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.launch.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // No installer / settings page on this device: nothing more we can do.
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }

    when (val s = state) {
        UpdateUi.Hidden -> Unit
        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.update_title)) },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.update_message, s.update.versionName, BuildConfig.VERSION_NAME.substringBefore('-')))
                    if (s.update.notes.isNotBlank()) {
                        Text(s.update.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::accept) { Text(stringResource(R.string.update_now)) } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.update_later)) } },
        )
        is UpdateUi.NeedsPermission -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_message)) },
            confirmButton = { TextButton(onClick = viewModel::openInstallSettings) { Text(stringResource(R.string.update_open_settings)) } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.update_later)) } },
        )
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (s.progress >= 0f) {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(s.progress * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.update_cancel)) } },
        )
        is UpdateUi.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(stringResource(R.string.update_failed_message)) },
            confirmButton = { TextButton(onClick = viewModel::accept) { Text(stringResource(R.string.update_retry)) } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.update_later)) } },
        )
    }
}
