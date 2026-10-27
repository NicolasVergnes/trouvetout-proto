package app.trouvetout.android.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.trouvetout.android.R
import app.trouvetout.core.domain.model.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Two buttons: pick from the gallery (no permission, system picker) or take a photo (camera app, no permission). */
@Composable
fun AddPhotoButtons(
    onPicked: (PhotoAttachment) -> Unit,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    var cameraTarget by remember { mutableStateOf<Pair<Uri, File>?>(null) }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onPicked(PhotoAttachment(context, uri))
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val target = cameraTarget
            cameraTarget = null
            if (target != null) {
                if (saved) onPicked(PhotoAttachment(context, target.first, target.second)) else target.second.delete()
            }
        }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = enabled,
            onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ) { Text(stringResource(R.string.photo_pick)) }
        TextButton(
            enabled = enabled,
            onClick = {
                val dir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
                val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                cameraTarget = uri to file
                camera.launch(uri)
            },
        ) { Text(stringResource(R.string.photo_take)) }
    }
}

/** Thumbnails of the attached photos, each with a detach action (long names stay out of the UI, ids only). */
@Composable
fun PhotoStrip(
    photos: List<Photo>,
    pathOf: (fileName: String) -> String,
    onDetach: (photoId: String) -> Unit,
    enabled: Boolean = true,
) {
    if (photos.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(photos, key = { it.id }) { photo ->
            Box(contentAlignment = Alignment.BottomEnd) {
                Thumbnail(pathOf(photo.thumbName), modifier = Modifier.size(96.dp))
                TextButton(enabled = enabled, onClick = {
                    onDetach(photo.id)
                }) { Text(stringResource(R.string.photo_detach)) }
            }
        }
    }
}

/** Decodes a small file off the main thread; a placeholder while it loads or when the file is gone. */
@Composable
fun Thumbnail(
    path: String,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(),
            contentDescription = stringResource(R.string.photo_description),
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {}
    }
}

private const val CAMERA_DIR = "camera"
