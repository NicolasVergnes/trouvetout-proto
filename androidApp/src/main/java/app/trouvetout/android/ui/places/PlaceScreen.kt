package app.trouvetout.android.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.android.ui.common.AddPhotoButtons
import app.trouvetout.android.ui.common.LocationPickerDialog
import app.trouvetout.android.ui.common.PhotoStrip
import app.trouvetout.android.ui.common.descendantsOf
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.LocationRef

/** Root level, one place, or one container (C6, C7, D1–D6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseRoute(
    target: BrowseTarget,
    navigation: BrowseNavigation,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as App
    val viewModel: BrowseViewModel = viewModel(key = target.toString()) { BrowseViewModel(app, target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val place by viewModel.place.collectAsStateWithLifecycle()
    val container by viewModel.container.collectAsStateWithLifecycle()
    val allPlaces by viewModel.allPlaces.collectAsStateWithLifecycle()
    val allContainers by viewModel.allContainers.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.gone) { if (state.gone) navigation.onBack() }

    val title =
        when (target) {
            BrowseTarget.Root -> stringResource(R.string.places_title)
            is BrowseTarget.Place -> place?.place?.name ?: ""
            is BrowseTarget.Container -> container?.container?.label ?: ""
        }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = navigation.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = { BrowseActions(target, onDialog = { dialog = it }) },
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
            val path = place?.path ?: container?.path.orEmpty()
            if (path.isNotEmpty()) Breadcrumb(path, navigation.onOpenPlace)
            state.problem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error) }
            when (target) {
                BrowseTarget.Root -> RootContent(viewModel, place?.children.orEmpty(), navigation, onAdd = {
                    dialog =
                        DIALOG_ADD_PLACE
                })
                is BrowseTarget.Place ->
                    PlaceBody(viewModel, place, state.busy, navigation, onAddPlace = {
                        dialog = DIALOG_ADD_PLACE
                    }, onAddContainer = {
                        dialog =
                            DIALOG_ADD_CONTAINER
                    })
                is BrowseTarget.Container -> ContainerBody(viewModel, container, state.busy, navigation)
            }
        }
    }
    BrowseDialogs(dialog, viewModel, allPlaces, allContainers, onClose = { dialog = null })
}

@Composable
private fun BrowseActions(
    target: BrowseTarget,
    onDialog: (String) -> Unit,
) {
    when (target) {
        is BrowseTarget.Place -> {
            TextButton(onClick = { onDialog(DIALOG_RENAME) }) { Text(stringResource(R.string.place_rename)) }
            TextButton(onClick = { onDialog(DIALOG_MERGE) }) { Text(stringResource(R.string.place_merge)) }
        }
        is BrowseTarget.Container -> TextButton(onClick = {
            onDialog(DIALOG_MOVE)
        }) { Text(stringResource(R.string.container_move)) }
        BrowseTarget.Root -> Unit
    }
}

@Composable
private fun Breadcrumb(
    path: List<Place>,
    onOpenPlace: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(path, key = { it.id }) { place ->
            TextButton(onClick = { onOpenPlace(place.id) }) { Text(place.name) }
            if (place !=
                path.last()
            ) {
                Text(stringResource(R.string.path_separator), modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun RootContent(
    viewModel: BrowseViewModel,
    roots: List<Place>,
    navigation: BrowseNavigation,
    onAdd: () -> Unit,
) {
    if (roots.isEmpty()) {
        Text(stringResource(R.string.places_empty), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.places_templates), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(viewModel.templates, key = { it.name }) { template ->
                SuggestionChip(onClick = { viewModel.applyTemplate(template) }, label = { Text(template.name) })
            }
        }
    }
    TextButton(onClick = onAdd) { Text(stringResource(R.string.place_add_root)) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(roots, key = { it.id }) { place ->
            ListItem(
                headlineContent = { Text(place.name) },
                modifier = Modifier.fillMaxWidth(),
                trailingContent = {
                    TextButton(onClick = {
                        navigation.onOpenPlace(place.id)
                    }) { Text(stringResource(R.string.action_open)) }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PlaceBody(
    viewModel: BrowseViewModel,
    content: app.trouvetout.core.data.repository.PlaceContent?,
    busy: Boolean,
    navigation: BrowseNavigation,
    onAddPlace: () -> Unit,
    onAddContainer: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as App
    if (content == null) return
    PhotoStrip(content.photos, pathOf = {
        app.shared.photoStore.absolutePath(it)
    }, onDetach = viewModel::detachPhoto, enabled = !busy)
    AddPhotoButtons(onPicked = viewModel::attachPhoto, enabled = !busy)
    // Two rows: four text buttons do not fit a phone width (seen on the API 29 emulator).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onAddPlace) { Text(stringResource(R.string.place_add_child)) }
        TextButton(onClick = onAddContainer) { Text(stringResource(R.string.container_add)) }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(content.children, key = { "p" + it.id }) { child ->
            ListItem(
                headlineContent = { Text(child.name) },
                supportingContent = { Text(kindLabel(child.kind)) },
                trailingContent = {
                    TextButton(onClick = {
                        navigation.onOpenPlace(child.id)
                    }) { Text(stringResource(R.string.action_open)) }
                },
            )
            HorizontalDivider()
        }
        items(content.containers, key = { "c" + it.id }) { box ->
            ListItem(
                headlineContent = { Text(box.label) },
                supportingContent = { Text(stringResource(R.string.container_kind)) },
                trailingContent = {
                    TextButton(onClick = {
                        navigation.onOpenContainer(box.id)
                    }) { Text(stringResource(R.string.action_open)) }
                },
            )
            HorizontalDivider()
        }
        items(content.items, key = { "i" + it.item.id }) { view ->
            ItemRow(view, onOpen = { navigation.onOpenItem(view.item.id) })
            HorizontalDivider()
        }
        if (content.children.isEmpty() && content.containers.isEmpty() && content.items.isEmpty()) {
            item { Text(stringResource(R.string.place_empty), modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}

@Composable
private fun ContainerBody(
    viewModel: BrowseViewModel,
    content: app.trouvetout.core.data.repository.ContainerContent?,
    busy: Boolean,
    navigation: BrowseNavigation,
) {
    val app = LocalContext.current.applicationContext as App
    if (content == null) return
    PhotoStrip(content.photos, pathOf = {
        app.shared.photoStore.absolutePath(it)
    }, onDetach = viewModel::detachPhoto, enabled = !busy)
    AddPhotoButtons(onPicked = viewModel::attachPhoto, enabled = !busy)
    if (content.items.isEmpty()) {
        TextButton(onClick = viewModel::deleteContainer) { Text(stringResource(R.string.container_delete)) }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(content.items, key = { it.item.id }) { view ->
            ItemRow(view, onOpen = { navigation.onOpenItem(view.item.id) })
            HorizontalDivider()
        }
        if (content.items.isEmpty()) {
            item {
                Text(stringResource(R.string.container_empty), modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable
fun ItemRow(
    view: ItemView,
    onOpen: () -> Unit,
) {
    val status =
        when (view.location.status) {
            LocationStatus.TAKEN -> stringResource(R.string.state_taken)
            LocationStatus.LENT -> stringResource(R.string.state_lent, view.location.lentTo.orEmpty())
            LocationStatus.STORED -> null
        }
    ListItem(
        headlineContent = { Text((if (view.item.pinned) "★ " else "") + view.item.name) },
        supportingContent = status?.let { { Text(it) } },
        trailingContent = { TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) } },
    )
}

@Composable
private fun BrowseDialogs(
    dialog: String?,
    viewModel: BrowseViewModel,
    allPlaces: List<Place>,
    allContainers: List<app.trouvetout.core.domain.model.Container>,
    onClose: () -> Unit,
) {
    val current = viewModel.place.collectAsStateWithLifecycle().value?.place
    when (dialog) {
        DIALOG_ADD_PLACE ->
            NameDialog(
                title = stringResource(
                    if (viewModel.target ==
                        BrowseTarget.Root
                    ) {
                        R.string.place_add_root
                    } else {
                        R.string.place_add_child
                    },
                ),
                initial = "",
                onConfirm = { name ->
                    viewModel.addPlace(
                        name,
                        if (viewModel.target ==
                            BrowseTarget.Root
                        ) {
                            PlaceKind.ROOT
                        } else {
                            PlaceKind.SPOT
                        },
                    )
                    onClose()
                },
                onDismiss = onClose,
            )
        DIALOG_ADD_CONTAINER ->
            NameDialog(
                title = stringResource(R.string.container_add),
                initial = "",
                onConfirm = { label ->
                    viewModel.addContainer(label, label.filter { it.isDigit() }.toLongOrNull())
                    onClose()
                },
                onDismiss = onClose,
            )
        DIALOG_RENAME ->
            NameDialog(
                title = stringResource(R.string.place_rename),
                initial = current?.name.orEmpty(),
                onConfirm = { name ->
                    viewModel.rename(name, current?.aliases.orEmpty())
                    onClose()
                },
                onDismiss = onClose,
            )
        DIALOG_MERGE ->
            LocationPickerDialog(
                title = stringResource(R.string.place_merge_into),
                places = allPlaces,
                containers = emptyList(),
                excludedPlaceIds = current?.let { descendantsOf(it.id, allPlaces) }.orEmpty(),
                withContainers = false,
                onPick = { ref ->
                    (ref as? LocationRef.InPlace)?.let { viewModel.mergeInto(it.placeId) }
                    onClose()
                },
                onDismiss = onClose,
            )
        DIALOG_MOVE ->
            LocationPickerDialog(
                title = stringResource(R.string.container_move_to),
                places = allPlaces,
                containers = allContainers,
                withContainers = false,
                onPick = { ref ->
                    (ref as? LocationRef.InPlace)?.let { viewModel.moveContainer(it.placeId) }
                    onClose()
                },
                onDismiss = onClose,
            )
        else -> Unit
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = {
                text = it
            }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                onConfirm(text.trim())
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun kindLabel(kind: PlaceKind): String = stringResource(
    when (kind) {
        PlaceKind.ROOT -> R.string.place_kind_root
        PlaceKind.ROOM -> R.string.place_kind_room
        PlaceKind.FURNITURE -> R.string.place_kind_furniture
        PlaceKind.SPOT -> R.string.place_kind_spot
    },
)

@Composable
private fun problemText(problem: BrowseProblem): String = stringResource(
    when (problem) {
        BrowseProblem.REJECTED -> R.string.capture_problem_rejected
        BrowseProblem.PHOTO_UNSUPPORTED -> R.string.photo_unsupported
        BrowseProblem.PHOTO_UNREADABLE -> R.string.photo_unreadable
    },
)

private const val DIALOG_ADD_PLACE = "add_place"
private const val DIALOG_ADD_CONTAINER = "add_container"
private const val DIALOG_RENAME = "rename"
private const val DIALOG_MERGE = "merge"
private const val DIALOG_MOVE = "move"
