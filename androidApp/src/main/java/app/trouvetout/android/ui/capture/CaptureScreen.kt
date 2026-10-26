package app.trouvetout.android.ui.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.core.capture.CaptureProblem
import app.trouvetout.core.capture.Intent
import app.trouvetout.core.capture.PlaceChoice
import app.trouvetout.core.capture.PlaceResolution
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.domain.model.LocationStatus
import kotlinx.coroutines.delay

/** Home screen since lot 3: one field to say where things go, a confirmation card, the objects below. */
@Composable
fun CaptureRoute(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as App
    val viewModel: CaptureViewModel = viewModel { CaptureViewModel(app) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    CaptureScreen(
        state = state,
        items = items,
        callbacks =
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
            ),
        modifier = modifier,
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    items: List<ItemView>,
    callbacks: CaptureCallbacks,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
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
                )
            }
            ItemList(state, items, callbacks)
        }
    }
    state.pending?.let { pending ->
        when (pending) {
            is PendingDecision.Place -> PlaceDecisionDialog(pending, callbacks)
            is PendingDecision.Item -> ItemDecisionDialog(pending, callbacks)
        }
    }
}

@Composable
private fun CaptureField(
    state: CaptureUiState,
    callbacks: CaptureCallbacks,
) {
    OutlinedTextField(
        value = state.input,
        onValueChange = callbacks.onInputChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.capture_hint)) },
        singleLine = true,
        enabled = !state.busy,
        trailingIcon = {
            IconButton(onClick = callbacks.onSubmit, enabled = state.input.isNotBlank() && !state.busy) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.capture_send))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { callbacks.onSubmit() }),
    )
}

@Composable
private fun ConfirmationCardView(
    card: ConfirmationCard,
    callbacks: CaptureCallbacks,
) {
    LaunchedEffect(card.token) {
        delay(CARD_MILLIS)
        callbacks.onDismissCard(card.token)
    }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = callbacks.onEditLast),
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
    val shown = if (query == null) items else items.filter { it.item.name.contains(query, ignoreCase = true) }
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
            ItemRow(view)
            HorizontalDivider()
        }
    }
}

@Composable
private fun ItemRow(view: ItemView) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
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
                        view.path.labels.joinToString(" › ")
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
            Column {
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
                    Text(stringResource(R.string.capture_place_create, creation.names.joinToString(" › ")))
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
            Column {
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
private fun problemText(problem: CaptureProblem): String = stringResource(
    when (problem) {
        CaptureProblem.NOTHING_UNDERSTOOD -> R.string.capture_problem_nothing
        CaptureProblem.PLACE_UNDECIDED -> R.string.capture_problem_place
        CaptureProblem.ITEM_NOT_FOUND -> R.string.capture_problem_item
        CaptureProblem.PERSON_MISSING -> R.string.capture_problem_person
        CaptureProblem.COMMAND_REJECTED -> R.string.capture_problem_rejected
    },
)

private const val CARD_MILLIS = 3_000L
