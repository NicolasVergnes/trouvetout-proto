package app.trouvetout.android.ui.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trouvetout.android.R
import app.trouvetout.android.ui.common.AddPhotoButtons
import app.trouvetout.core.ml.PhotoModelState

/**
 * B8: a photo for the next sentence. Without one, the two photo buttons; with one, its preview, the names the model
 * proposes (a tap starts the sentence) or why there are none, and a button to drop it.
 */
@Composable
internal fun CapturePhotoRow(
    state: CaptureUiState,
    photoModel: PhotoModelState?,
    callbacks: CaptureCallbacks,
    onNameUsed: () -> Unit = {},
) {
    val photo = state.photo
    if (photo == null) {
        AddPhotoButtons(onPicked = callbacks.onAddPhoto, enabled = !state.busy && !state.listening)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                photo.preview,
                contentDescription = stringResource(R.string.photo_description),
                modifier = Modifier.size(PREVIEW_DP.dp),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PhotoNames(photo, photoModel) { name ->
                    callbacks.onUseName(name)
                    onNameUsed()
                }
                Text(stringResource(R.string.capture_photo_hint), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = callbacks.onRemovePhoto) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.capture_photo_remove))
            }
        }
    }
    state.photoNotice?.let { notice ->
        Text(
            stringResource(photoNoticeText(notice)),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun PhotoNames(
    photo: CapturePhoto,
    photoModel: PhotoModelState?,
    onUseName: (String) -> Unit,
) {
    when {
        photo.naming -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(PROGRESS_DP.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.capture_photo_naming), style = MaterialTheme.typography.bodyMedium)
        }
        // Wraps rather than scrolls: the third name stays visible, also with large text.
        photo.names.isNotEmpty() -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag(PHOTO_NAMES_TAG),
        ) {
            photo.names.forEach { suggestion ->
                SuggestionChip(onClick = { onUseName(suggestion.name) }, label = { Text(suggestion.name) })
            }
        }
        else -> Text(stringResource(noNamesText(photoModel)), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Why no name is proposed: the phone, the user's setting, or the model found nothing close enough. */
private fun noNamesText(model: PhotoModelState?): Int = when {
    model == null || !model.bundled -> R.string.capture_photo_no_names_build
    !model.deviceCapable -> R.string.capture_photo_no_names_memory
    model.forceTextOnly -> R.string.capture_photo_no_names_off
    else -> R.string.capture_photo_no_names_found
}

private fun photoNoticeText(notice: PhotoNotice): Int = when (notice) {
    PhotoNotice.TOO_LARGE -> R.string.photo_too_large
    PhotoNotice.UNREADABLE -> R.string.photo_unreadable
    PhotoNotice.UNSUPPORTED -> R.string.photo_unsupported
    PhotoNotice.SEVERAL_ITEMS -> R.string.capture_photo_several
}

const val PHOTO_NAMES_TAG = "capture_photo_names"
private const val PREVIEW_DP = 72
private const val PROGRESS_DP = 16
