package app.trouvetout.android.ui.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.android.ui.common.LocationPickerDialog
import app.trouvetout.android.ui.theme.AppTheme
import app.trouvetout.core.capture.CaptureProblem
import app.trouvetout.core.capture.Intent
import app.trouvetout.core.capture.PlaceChoice
import app.trouvetout.core.capture.PlaceResolution
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.usecase.LocationRef
import kotlinx.coroutines.delay

/** Home screen since lot 3: one field to say where things go, a confirmation card, the objects below. */
@Composable
fun CaptureRoute(
    onOpenSettings: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A question asked from the quick capture (widget, tile), shown here as a search (C5). */
    searchRequest: String? = null,
    onSearchRequestHandled: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as App
    val viewModel: CaptureViewModel = viewModel { CaptureViewModel(app) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val containers by viewModel.containers.collectAsStateWithLifecycle()
    val mic = rememberMicActions(viewModel)
    LaunchedEffect(searchRequest) {
        searchRequest?.let {
            viewModel.showSearch(it)
            onSearchRequestHandled()
        }
    }
    val callbacks =
        remember(viewModel, mic, onOpenSettings, onOpenPlaces, onOpenItem) {
            CaptureCallbacks(
                onInputChange = viewModel::onInputChange,
                onSubmit = viewModel::submit,
                onPickSuggestion = viewModel::pickSuggestion,
                onChoosePlace = viewModel::choosePlace,
                onChooseItem = viewModel::chooseItem,
                onDismissDecision = viewModel::dismissDecision,
                onDismissCard = viewModel::dismissCard,
                onEditLast = viewModel::editLast,
                onUndo = viewModel::undo,
                onDismissMessages = viewModel::dismissMessages,
                onOpenSettings = onOpenSettings,
                onOpenPlaces = onOpenPlaces,
                onOpenItem = onOpenItem,
                mic = mic,
                onStartBatch = viewModel::startBatch,
                onStopBatch = viewModel::stopBatch,
            )
        }
    CaptureScreen(
        state = state,
        items = items,
        callbacks = callbacks,
        modifier = modifier,
        places = places,
        containers = containers,
    )
}

/** All the screen can do, grouped to keep the composable signature readable. */
data class CaptureCallbacks(
    val onInputChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onPickSuggestion: (app.trouvetout.core.capture.LocationCandidate) -> Unit,
    val onChoosePlace: (PlaceChoice) -> Unit,
    val onChooseItem: (String?) -> Unit,
    val onDismissDecision: () -> Unit,
    val onDismissCard: (Long) -> Unit,
    val onEditLast: () -> Unit,
    val onUndo: () -> Unit,
    val onDismissMessages: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenPlaces: () -> Unit,
    val onOpenItem: (String) -> Unit,
    val mic: MicActions = MicActions({}, {}, {}, {}),
    val onStartBatch: (LocationRef) -> Unit = {},
    val onStopBatch: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    items: List<ItemView>,
    callbacks: CaptureCallbacks,
    modifier: Modifier = Modifier,
    places: List<Place> = emptyList(),
    containers: List<Container> = emptyList(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = callbacks.onOpenPlaces) { Text(stringResource(R.string.places_title)) }
                    TextButton(onClick = callbacks.onOpenSettings) { Text(stringResource(R.string.settings_title)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CaptureField(state, callbacks)
            VoiceStatus(state, callbacks.mic.onOpenAppSettings)
            BatchRow(state, places, containers, callbacks)
            if (state.suggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.suggestions, key = { it.id }) { candidate ->
                        SuggestionChip(onClick = {
                            callbacks.onPickSuggestion(candidate)
                        }, label = { Text(candidate.name) })
                    }
                }
            }
            state.card?.let { card -> ConfirmationCardView(card, callbacks) }
            state.problem?.let { problem ->
                Text(
                    problemText(problem),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            ItemList(state, items, callbacks)
        }
    }
    CaptureDialogs(state, callbacks)
}

/** The questions of a capture (B7, A14) and the explanation before the microphone prompt (H9). */
@Composable
internal fun CaptureDialogs(
    state: CaptureUiState,
    callbacks: CaptureCallbacks,
) {
    state.pending?.let { pending ->
        when (pending) {
            is PendingDecision.Place -> PlaceDecisionDialog(pending, callbacks)
            is PendingDecision.Item -> ItemDecisionDialog(pending, callbacks)
        }
    }
    if (state.askMicPermission) MicRationaleDialog(callbacks.mic)
}

/** B10: « Je range… » picks a destination; every sentence without a place then goes there until closed. */
@Composable
private fun BatchRow(
    state: CaptureUiState,
    places: List<Place>,
    containers: List<Container>,
    callbacks: CaptureCallbacks,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val batch = state.batch
    if (batch == null) {
        if (places.isNotEmpty()) {
            FilterChip(
                selected = false,
                onClick = { picking = true },
                label = { Text(stringResource(R.string.batch_start)) },
            )
        }
    } else {
        val stop = stringResource(R.string.batch_stop)
        InputChip(
            selected = true,
            onClick = callbacks.onStopBatch,
            label = { Text(stringResource(R.string.batch_active, batch.label)) },
            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stop) },
        )
    }
    if (picking) {
        LocationPickerDialog(
            title = stringResource(R.string.batch_picker_title),
            places = places,
            containers = containers,
            onPick = {
                picking = false
                callbacks.onStartBatch(it)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
internal fun CaptureField(
    state: CaptureUiState,
    callbacks: CaptureCallbacks,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.input,
        onValueChange = callbacks.onInputChange,
        modifier = modifier.fillMaxWidth().testTag(CAPTURE_FIELD_TAG),
        placeholder = { Text(stringResource(R.string.capture_hint)) },
        singleLine = true,
        readOnly = state.listening,
        trailingIcon = {
            Row {
                MicButton(state.listening, enabled = !state.busy, onMic = callbacks.mic.onMic)
                IconButton(
                    onClick = callbacks.onSubmit,
                    enabled = state.input.isNotBlank() && !state.busy && !state.listening,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.capture_send))
                }
            }
        },
        // Object and place names are not dictionary words: no autocorrection, no capitalisation.
        keyboardOptions =
            KeyboardOptions(
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
        keyboardActions = KeyboardActions(onDone = { callbacks.onSubmit() }),
    )
}

@Composable
internal fun ConfirmationCardView(
    card: ConfirmationCard,
    callbacks: CaptureCallbacks,
) {
    LaunchedEffect(card.token) {
        delay(CARD_MILLIS)
        callbacks.onDismissCard(card.token)
    }
    val editLabel = stringResource(R.string.capture_card_edit)
    Card(
        onClick = callbacks.onEditLast,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    onClick(label = editLabel, action = null)
                },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(card.itemNames.joinToString(", "), style = MaterialTheme.typography.titleMedium)
                Text(cardDetail(card), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = callbacks.onUndo) { Text(stringResource(R.string.action_undo)) }
        }
    }
}

@Composable
private fun cardDetail(card: ConfirmationCard): String = when (card.intent) {
    Intent.TAKE -> stringResource(R.string.capture_card_taken)
    Intent.LEND -> stringResource(R.string.capture_card_lent, card.person.orEmpty())
    Intent.RETURN_FROM_LEND -> stringResource(R.string.capture_card_returned_from_lend)
    Intent.RETURN -> card.placeText?.let { stringResource(R.string.capture_card_stored, it) }
        ?: stringResource(R.string.capture_card_put_back)
    else -> card.placeText?.let { stringResource(R.string.capture_card_stored, it) }
        ?: stringResource(R.string.capture_card_no_place)
}

@Composable
private fun ItemList(
    state: CaptureUiState,
    items: List<ItemView>,
    callbacks: CaptureCallbacks,
) {
    val query = state.searchQuery
    val shown = remember(items, query) {
        if (query ==
            null
        ) {
            items
        } else {
            items.filter { it.item.name.contains(query, ignoreCase = true) }
        }
    }
    if (query != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.capture_search_title, query),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = callbacks.onDismissMessages) { Text(stringResource(R.string.action_close)) }
        }
    }
    if (shown.isEmpty()) {
        Text(
            stringResource(if (query == null) R.string.capture_empty else R.string.capture_search_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it.item.id }) { view ->
            ItemRow(view, onOpen = { callbacks.onOpenItem(view.item.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ItemRow(
    view: ItemView,
    onOpen: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen, role = Role.Button)
                .defaultMinSize(minHeight = 48.dp)
                .padding(vertical = 8.dp),
    ) {
        Text(view.item.name, style = MaterialTheme.typography.titleMedium)
        val where =
            when (view.location.status) {
                LocationStatus.TAKEN -> stringResource(R.string.state_taken)
                LocationStatus.LENT -> stringResource(R.string.state_lent, view.location.lentTo.orEmpty())
                LocationStatus.STORED ->
                    if (view.path.isUnknown) {
                        stringResource(
                            R.string.state_unknown_place,
                        )
                    } else {
                        view.path.labels.joinToString(stringResource(R.string.path_separator))
                    }
            }
        Text(where, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaceDecisionDialog(
    pending: PendingDecision.Place,
    callbacks: CaptureCallbacks,
) {
    val placeText = pending.proposal.parse.place.orEmpty()
    AlertDialog(
        onDismissRequest = callbacks.onDismissDecision,
        title = { Text(stringResource(R.string.capture_place_title, placeText)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val resolution = pending.resolution) {
                    is PlaceResolution.Ambiguous ->
                        resolution.suggestions.forEach { match ->
                            TextButton(
                                onClick = {
                                    callbacks.onChoosePlace(
                                        PlaceChoice.Existing(
                                            match.candidate.id,
                                            match.candidate.isContainer,
                                            rememberAlias = true,
                                        ),
                                    )
                                },
                            ) { Text(stringResource(R.string.capture_place_attach, match.candidate.name)) }
                        }
                    is PlaceResolution.Unknown ->
                        resolution.under?.let { under ->
                            Text(
                                stringResource(R.string.capture_place_under, under.candidate.name),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    is PlaceResolution.Resolved -> Unit
                }
                val creation =
                    when (val resolution = pending.resolution) {
                        is PlaceResolution.Unknown ->
                            PlaceChoice.CreateNew(
                                resolution.names,
                                resolution.under?.candidate?.id,
                                resolution.under?.candidate?.isContainer ?: false,
                            )
                        else -> PlaceChoice.CreateNew(listOf(placeText), null, false)
                    }
                TextButton(onClick = { callbacks.onChoosePlace(creation) }) {
                    Text(
                        stringResource(
                            R.string.capture_place_create,
                            creation.names.joinToString(stringResource(R.string.path_separator)),
                        ),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = callbacks.onDismissDecision) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ItemDecisionDialog(
    pending: PendingDecision.Item,
    callbacks: CaptureCallbacks,
) {
    val moving = pending.proposal.movesExistingItems
    val name = pending.target.mention.name
    AlertDialog(
        onDismissRequest = callbacks.onDismissDecision,
        title = {
            Text(stringResource(if (moving) R.string.capture_item_which else R.string.capture_item_exists, name))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                pending.target.matches.forEach { match ->
                    TextButton(onClick = { callbacks.onChooseItem(match.itemId) }) {
                        Text(
                            stringResource(
                                if (moving) R.string.capture_item_use else R.string.capture_item_move_existing,
                                match.name,
                            ),
                        )
                    }
                }
                if (moving && pending.target.matches.isEmpty()) {
                    Text(stringResource(R.string.capture_item_none), style = MaterialTheme.typography.bodyMedium)
                }
                if (!moving) {
                    TextButton(onClick = {
                        callbacks.onChooseItem(null)
                    }) { Text(stringResource(R.string.capture_item_create_new)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = callbacks.onDismissDecision) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun problemText(problem: CaptureProblem): String = stringResource(
    when (problem) {
        CaptureProblem.NOTHING_UNDERSTOOD -> R.string.capture_problem_nothing
        CaptureProblem.PLACE_UNDECIDED -> R.string.capture_problem_place
        CaptureProblem.ITEM_NOT_FOUND -> R.string.capture_problem_item
        CaptureProblem.PERSON_MISSING -> R.string.capture_problem_person
        CaptureProblem.COMMAND_REJECTED -> R.string.capture_problem_rejected
        CaptureProblem.INPUT_TOO_LONG -> R.string.capture_problem_too_long
    },
)

private const val CARD_MILLIS = 3_000L

@Preview(showBackground = true)
@PreviewLightDark
@PreviewFontScale
@Composable
private fun CaptureScreenPreview() {
    val noop = {}
    AppTheme {
        CaptureScreen(
            state = CaptureUiState(input = "clés @ ent"),
            items = emptyList(),
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
        )
    }
}

/** Test hook for the instrumented smoke test. */
const val CAPTURE_FIELD_TAG = "capture_field"
