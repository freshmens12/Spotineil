package com.spotineil.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spotineil.core.designsystem.R
import com.spotineil.core.model.SpotineilException

@Composable
fun errorMessage(error: Throwable): String = when (error) {
    is SpotineilException.Network -> stringResource(R.string.ds_error_offline)
    is SpotineilException.RateLimited -> stringResource(R.string.ds_error_rate_limited, error.retryAfterSeconds)
    is SpotineilException.Forbidden ->
        if (error.isPremiumRequired) stringResource(R.string.ds_error_premium) else stringResource(R.string.ds_error_unavailable)
    is SpotineilException.NotFound -> stringResource(R.string.ds_error_not_found)
    is SpotineilException.Unauthorized -> stringResource(R.string.ds_error_session)
    is SpotineilException.Server -> stringResource(R.string.ds_error_server)
    is SpotineilException.QuotaExceeded -> stringResource(R.string.ds_error_youtube_quota)
    else -> stringResource(R.string.ds_error_generic)
}

@Composable
fun ErrorState(error: Throwable, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    MessageState(
        icon = if (error is SpotineilException.Network) Icons.Rounded.CloudOff else Icons.Rounded.ErrorOutline,
        title = errorMessage(error),
        body = null,
        modifier = modifier,
        actionLabel = onRetry?.let { stringResource(R.string.ds_retry) },
        onAction = onRetry,
    )
}

@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
