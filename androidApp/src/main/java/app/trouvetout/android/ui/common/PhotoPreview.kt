package app.trouvetout.android.ui.common

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.trouvetout.core.platform.AndroidImageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small, oriented preview of a photo that is not stored yet (capture with photo, search by photo); null when the
 * bytes are not an image. Built in memory, never written.
 */
suspend fun previewOf(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    val small = AndroidImageCodec().transcodeJpeg(bytes, PREVIEW_SIDE, PREVIEW_QUALITY) ?: return@withContext null
    BitmapFactory.decodeByteArray(small.bytes, 0, small.bytes.size)?.asImageBitmap()
}

private const val PREVIEW_SIDE = 320
private const val PREVIEW_QUALITY = 80
