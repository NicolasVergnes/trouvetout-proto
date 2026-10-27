package app.trouvetout.android.ui.common

import android.content.Context
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    // Survives the process death that the camera app often causes on small devices: the capture is not lost
    // and its temporary file is read then deleted instead of staying in the cache with its EXIF.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onPicked(PhotoAttachment(context, uri))
        }
    val hasCamera =
        remember(context) {
            val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            intent.resolveActivity(context.packageManager) != null
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val file = cameraPath?.let(::File)
            cameraPath = null
            if (file != null) {
                if (saved) onPicked(PhotoAttachment(context, cameraUri(context, file), file)) else file.delete()
            }
        }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = enabled,
            onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ) { Text(stringResource(R.string.photo_pick)) }
        if (hasCamera) {
            TextButton(
                enabled = enabled,
                onClick = {
                    val dir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
                    val file = File(dir, "capture-${java.util.UUID.randomUUID()}.jpg")
                    cameraPath = file.absolutePath
                    try {
                        camera.launch(cameraUri(context, file))
                    } catch (_: android.content.ActivityNotFoundException) {
                        cameraPath = null
                        file.delete()
                    }
                },
            ) { Text(stringResource(R.string.photo_take)) }
        }
    }
}

private fun cameraUri(
    context: Context,
    file: File,
): Uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)

/** Leftover capture files (process killed before the result came back); called at startup. */
fun clearCameraCaptures(context: Context) {
    File(context.cacheDir, CAMERA_DIR).deleteRecursively()
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
