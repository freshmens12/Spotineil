package com.spotineil.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.spotineil.core.designsystem.theme.LocalDataSaver
import com.spotineil.core.model.Image
import com.spotineil.core.model.bestFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picks an image URL for a size, honoring Data saver. */
@Composable
fun rememberImageUrl(images: List<Image>, targetPx: Int): String? {
    val dataSaver = LocalDataSaver.current
    return remember(images, targetPx, dataSaver) { images.bestFor(targetPx, dataSaver) }
}

@Composable
fun CoverImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    placeholderIcon: ImageVector = if (shape == CircleShape) Icons.Rounded.Person else Icons.Rounded.MusicNote,
) {
    val placeholder = @Composable {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(placeholderIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Box(modifier.clip(shape)) {
        if (url == null) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Box(Modifier.fillMaxSize().shimmer()) },
                error = { placeholder() },
                success = { SubcomposeAsyncImageContent() },
            )
        }
    }
}

/**
 * Extracts a dark, vibrant color from artwork with the Palette API, for detail-page headers
 * and the full-screen player background. Animates between colors.
 */
@Composable
fun rememberDominantColor(url: String?, fallback: Color = MaterialTheme.colorScheme.surfaceContainerHigh): State<Color> {
    val context = LocalContext.current
    var target by remember { mutableStateOf(fallback) }
    LaunchedEffect(url) {
        if (url == null) { target = fallback; return@LaunchedEffect }
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(128).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@LaunchedEffect
        val color = withContext(Dispatchers.Default) {
            val palette = Palette.from(result.drawable.toBitmap()).maximumColorCount(16).generate()
            (palette.darkVibrantSwatch ?: palette.vibrantSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch)?.rgb
        }
        if (color != null) target = Color(color)
    }
    return animateColorAsState(target, animationSpec = spring(stiffness = 120f), label = "dominantColor")
}
