package ph.chefdonn.receipts.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.donnColors

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data object Missing : PhotoLoad
    data class Ready(val bitmap: ImageBitmap) : PhotoLoad
}

/**
 * One receipt page. [load] returns the JPEG bytes from wherever they are (draft,
 * outbox, cache, hub) or null; a photo not on this device or the hub yet says so.
 */
@Composable
internal fun ReceiptPhoto(
    photoId: String,
    load: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    targetEdge: Int = 1600,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var state by remember(photoId) { mutableStateOf<PhotoLoad>(PhotoLoad.Loading) }
    LaunchedEffect(photoId) {
        val bitmap = withContext(Dispatchers.IO) {
            load(photoId)?.let { ReceiptImages.decode(it, targetEdge) }
        }
        state = if (bitmap != null) PhotoLoad.Ready(bitmap.asImageBitmap()) else PhotoLoad.Missing
    }
    val c = donnColors
    Box(
        modifier
            .background(c.surfaceSunken, RoundedCornerShape(DonnDimens.radiusMd))
            .border(DonnDimens.borderThin, c.border, RoundedCornerShape(DonnDimens.radiusMd)),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            PhotoLoad.Loading -> DonnText("Loading photo…", style = DonnType.caption, color = c.textSecondary)
            PhotoLoad.Missing -> DonnText("Photo not on the hub yet", style = DonnType.caption, color = c.textSecondary)
            is PhotoLoad.Ready -> Image(s.bitmap, contentDescription = "Receipt photo", contentScale = contentScale)
        }
    }
}
