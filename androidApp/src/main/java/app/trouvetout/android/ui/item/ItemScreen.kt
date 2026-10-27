package app.trouvetout.android.ui.item

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.android.ui.common.AddPhotoButtons
import app.trouvetout.android.ui.common.LocationPickerDialog
import app.trouvetout.android.ui.common.PhotoStrip
import app.trouvetout.android.ui.places.NameDialog
import app.trouvetout.android.ui.places.ProblemText
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.LocationStatus

/** The item sheet: name, description, tags, quantity, location, photos, pin, archive, history, bin. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemRoute(
    itemId: String,
    onBack: () -> Unit,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as App
    val viewModel: ItemViewModel = viewModel(key = "item/$itemId") { ItemViewModel(app, itemId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val view by viewModel.item.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val suggestions by viewModel.tagSuggestions.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val allPlaces by viewModel.allPlaces.collectAsStateWithLifecycle()
    val allContainers by viewModel.allContainers.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.gone) { if (state.gone) onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(view?.item?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { dialog = DIALOG_DELETE }) { Text(stringResource(R.string.item_delete)) }
                },
            )
        },
    ) { innerPadding ->
        val current = view
        if (current == null) return@Scaffold
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.problem?.let { ProblemText(problemText(it)) }
            LocationSection(current, onOpenPlace, onMove = {
                dialog = DIALOG_MOVE
            }, onTake = viewModel::take, onPutBack = viewModel::putBack, onLend = {
                dialog =
                    DIALOG_LEND
            })
            PhotoStrip(photos, pathOf = {
                app.shared.photoStore.absolutePath(it)
            }, onDetach = viewModel::detachPhoto, enabled = !state.busy)
            AddPhotoButtons(onPicked = viewModel::attachPhoto, enabled = !state.busy)
            EditForm(current, suggestions, state.busy, viewModel)
            SwitchRow(stringResource(R.string.item_pinned), current.item.pinned, !state.busy, viewModel::setPinned)
            SwitchRow(
                stringResource(R.string.item_archived),
                current.item.archived,
                !state.busy,
                viewModel::setArchived,
            )
            SwitchRow(stringResource(R.string.item_show_history), state.showHistory, true, viewModel::setShowHistory)
            if (state.showHistory) HistoryList(history)
        }
    }
    when (dialog) {
        DIALOG_MOVE ->
            LocationPickerDialog(
                title = stringResource(R.string.item_move_to),
                places = allPlaces,
                containers = allContainers,
                onPick = { ref ->
                    viewModel.moveTo(ref)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        DIALOG_LEND ->
            NameDialog(
                title = stringResource(R.string.item_lend_to),
                initial = "",
                onConfirm = { person ->
                    viewModel.lend(person)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        DIALOG_DELETE ->
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.item_delete_title)) },
                text = { Text(stringResource(R.string.item_delete_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dialog = null
                            viewModel.delete()
                        },
                    ) { Text(stringResource(R.string.item_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        else -> Unit
    }
}

@Composable
private fun LocationSection(
    view: ItemView,
    onOpenPlace: (String) -> Unit,
    onMove: () -> Unit,
    onTake: () -> Unit,
    onPutBack: () -> Unit,
    onLend: () -> Unit,
) {
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
    Text(where, style = MaterialTheme.typography.titleMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(view.path.places, key = {
            it.id
        }) { place -> SuggestionChip(onClick = { onOpenPlace(place.id) }, label = { Text(place.name) }) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onMove) { Text(stringResource(R.string.item_move)) }
        when (view.location.status) {
            LocationStatus.STORED -> {
                TextButton(onClick = onTake) { Text(stringResource(R.string.item_take)) }
                TextButton(onClick = onLend) { Text(stringResource(R.string.item_lend)) }
            }
            LocationStatus.TAKEN, LocationStatus.LENT -> TextButton(onClick = onPutBack) {
                Text(stringResource(R.string.item_put_back))
            }
        }
    }
}

@Composable
private fun EditForm(
    view: ItemView,
    suggestions: List<String>,
    busy: Boolean,
    viewModel: ItemViewModel,
) {
    // Keyed on the stored item: the fields follow every change written by the core (−1, undo, a move).
    val stored = view.item
    var name by rememberSaveable(stored) { mutableStateOf(stored.name) }
    var description by rememberSaveable(stored) { mutableStateOf(stored.description.orEmpty()) }
    var quantity by rememberSaveable(stored) { mutableStateOf(stored.quantity?.let { formatQuantity(it) }.orEmpty()) }
    var unit by rememberSaveable(stored) { mutableStateOf(stored.unit.orEmpty()) }
    var tags by rememberSaveable(stored, stateSaver = listSaver<List<String>, String>({ it }, { it })) {
        mutableStateOf(stored.tags)
    }
    var newTag by rememberSaveable { mutableStateOf("") }
    val tagList = tags
    val quantityValue = quantity.replace(',', '.').trim().toDoubleOrNull()
    val quantityValid = quantity.isBlank() || (quantityValue != null && quantityValue >= 0.0)

    OutlinedTextField(value = name, onValueChange = {
        name = it
    }, label = { Text(stringResource(R.string.item_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = description, onValueChange = {
        description = it
    }, label = { Text(stringResource(R.string.item_description)) }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it },
            label = { Text(stringResource(R.string.item_quantity)) },
            isError = !quantityValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(value = unit, onValueChange = {
            unit = it
        }, label = { Text(stringResource(R.string.item_unit)) }, singleLine = true, modifier = Modifier.weight(1f))
        val minusOne = stringResource(R.string.item_minus_one_description)
        TextButton(
            onClick = viewModel::decrement,
            enabled = !busy && (view.item.quantity ?: 0.0) > 0.0,
            modifier = Modifier.semantics { contentDescription = minusOne },
        ) { Text(stringResource(R.string.item_minus_one)) }
    }
    Text(stringResource(R.string.item_tags), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tagList, key = { it }) { tag ->
            InputChip(selected = true, onClick = { tags = tagList - tag }, label = { Text(tag) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = newTag, onValueChange = {
            newTag = it
        }, label = { Text(stringResource(R.string.item_tag_new)) }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(
            enabled = newTag.isNotBlank(),
            onClick = {
                tags = (tagList + newTag.trim()).distinct()
                newTag = ""
            },
        ) { Text(stringResource(R.string.item_tag_add)) }
    }
    val unused = suggestions.filter { it !in tagList }
    if (unused.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(unused, key = {
                it
            }) { tag -> SuggestionChip(onClick = { tags = tagList + tag }, label = { Text(tag) }) }
        }
    }
    Button(
        enabled = !busy && name.isNotBlank() && quantityValid,
        onClick = { viewModel.save(name.trim(), description, tagList, quantityValue, unit.trim()) },
    ) { Text(stringResource(R.string.action_save)) }
}

/** Label + switch as one TalkBack element. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun HistoryList(history: List<Event>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.item_history), style = MaterialTheme.typography.titleSmall)
        for (event in history.asReversed()) {
            val relative = DateUtils.getRelativeTimeSpanString(context, event.at.toEpochMilliseconds(), true)
            Text("${eventLabel(event.type)} · $relative", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun eventLabel(type: String): String = when (type) {
    "ItemCreated" -> stringResource(R.string.history_created)
    "ItemStored" -> stringResource(R.string.history_stored)
    "ItemMovedTo" -> stringResource(R.string.history_moved)
    "ItemTaken" -> stringResource(R.string.history_taken)
    "ItemReturned" -> stringResource(R.string.history_returned)
    "ItemLent" -> stringResource(R.string.history_lent)
    "ItemUpdated" -> stringResource(R.string.history_updated)
    "ItemArchived" -> stringResource(R.string.history_archived)
    "ItemDeleted" -> stringResource(R.string.history_deleted)
    "ItemRestored" -> stringResource(R.string.history_restored)
    "PhotoAttached" -> stringResource(R.string.history_photo_attached)
    "PhotoDetached" -> stringResource(R.string.history_photo_detached)
    else -> type
}

private fun formatQuantity(value: Double): String = if (value ==
    value.toLong().toDouble()
) {
    value.toLong().toString()
} else {
    value.toString()
}

@Composable
private fun problemText(problem: ItemProblem): String = stringResource(
    when (problem) {
        ItemProblem.REJECTED -> R.string.capture_problem_rejected
        ItemProblem.PHOTO_UNSUPPORTED -> R.string.photo_unsupported
        ItemProblem.PHOTO_UNREADABLE -> R.string.photo_unreadable
        ItemProblem.PHOTO_TOO_LARGE -> R.string.photo_too_large
    },
)

private const val DIALOG_MOVE = "move"
private const val DIALOG_LEND = "lend"
private const val DIALOG_DELETE = "delete"
