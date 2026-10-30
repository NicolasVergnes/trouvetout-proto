package app.trouvetout.android.ui.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.android.ui.theme.AppTheme

/**
 * Quick capture over whatever was on screen (B5): opened by the widget and the tile, it records one sentence,
 * shows the card for three seconds (B6) and closes. A question opens the home screen with its results (C5).
 */
@Composable
fun QuickCaptureRoute(
    startWithVoice: Boolean,
    onClose: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as App
    val viewModel: CaptureViewModel = viewModel { CaptureViewModel(app) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mic = rememberMicActions(viewModel)
    LaunchedEffect(Unit) { if (startWithVoice) mic.onMic() }
    LaunchedEffect(state.searchQuery) { state.searchQuery?.let(onSearch) }
    val callbacks =
        remember(viewModel, mic, onClose) {
            CaptureCallbacks(
                onInputChange = viewModel::onInputChange,
                onSubmit = viewModel::submit,
                onPickSuggestion = viewModel::pickSuggestion,
                onChoosePlace = viewModel::choosePlace,
                onChooseItem = viewModel::chooseItem,
                onDismissDecision = viewModel::dismissDecision,
                // The card went away on its own: the capture is done.
                onDismissCard = { token ->
                    viewModel.dismissCard(token)
                    onClose()
                },
                onEditLast = viewModel::editLast,
                onUndo = viewModel::undo,
                onDismissMessages = viewModel::dismissMessages,
                onOpenSettings = {},
                onOpenPlaces = {},
                onOpenItem = {},
                mic = mic,
            )
        }
    QuickCaptureScreen(state, callbacks, focusField = !startWithVoice, onClose = onClose)
}

@Composable
fun QuickCaptureScreen(
    state: CaptureUiState,
    callbacks: CaptureCallbacks,
    focusField: Boolean,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusField) { if (focusField) focus.requestFocus() }
    Column(modifier = Modifier.fillMaxSize()) {
        // Tapping outside the panel closes it; the panel has its own close button for TalkBack.
        Spacer(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null, onClick = onClose),
        )
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier =
                    Modifier
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                }
                CaptureField(state, callbacks, Modifier.focusRequester(focus))
                VoiceStatus(state, callbacks.mic.onOpenAppSettings)
                state.card?.let { ConfirmationCardView(it, callbacks) }
                state.problem?.let { problem ->
                    Text(
                        problemText(problem),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
    CaptureDialogs(state, callbacks)
}

@Preview(showBackground = true)
@PreviewLightDark
@Composable
private fun QuickCapturePreview() {
    val noop = {}
    AppTheme {
        QuickCaptureScreen(
            state = CaptureUiState(input = "passeport dans le tiroir", listening = true),
            callbacks =
                CaptureCallbacks(
                    onInputChange = {},
                    onSubmit = noop,
                    onPickSuggestion = {},
                    onChoosePlace = {},
                    onChooseItem = {},
                    onDismissDecision = noop,
                    onDismissCard = {},
                    onEditLast = noop,
                    onUndo = noop,
                    onDismissMessages = noop,
                    onOpenSettings = noop,
                    onOpenPlaces = noop,
                    onOpenItem = {},
                ),
            focusField = false,
            onClose = noop,
        )
    }
}
