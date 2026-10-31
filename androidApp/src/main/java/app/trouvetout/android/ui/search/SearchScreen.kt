package app.trouvetout.android.ui.search

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.android.ui.capture.ItemRow
import app.trouvetout.android.ui.common.LocationPickerDialog
import app.trouvetout.android.ui.common.Thumbnail
import app.trouvetout.android.ui.theme.AppTheme
import app.trouvetout.core.data.repository.ItemView
import app.trouvetout.core.data.repository.LocationPath
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.Item
import app.trouvetout.core.domain.model.LocationState
import app.trouvetout.core.domain.model.LocationStatus
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.ml.KeywordOnlyReason
import app.trouvetout.core.search.AnswerLine
import app.trouvetout.core.search.AnswerPlace
import app.trouvetout.core.search.ItemStateFilter
import app.trouvetout.core.search.MatchKind
import app.trouvetout.core.search.SearchFilters
import app.trouvetout.core.search.SearchHit
import app.trouvetout.core.search.SearchMode
import app.trouvetout.core.search.SearchResult
import app.trouvetout.core.search.Suggestion
import kotlin.time.Clock

/** Where a result leads. */
data class SearchNavigation(
    val onBack: () -> Unit,
    val onOpenItem: (String) -> Unit,
    val onOpenPlace: (String) -> Unit,
    val onOpenContainer: (String) -> Unit,
)

@Composable
fun SearchRoute(
    initialQuery: String,
    navigation: SearchNavigation,
) {
    val app = LocalContext.current.applicationContext as App
    val viewModel: SearchViewModel = viewModel { SearchViewModel(app, initialQuery) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val containers by viewModel.containers.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val callbacks =
        remember(viewModel, navigation) {
            SearchCallbacks(
                onQueryChange = viewModel::onQueryChange,
                onPickSuggestion = viewModel::pickSuggestion,
                onPlace = viewModel::setPlace,
                onTag = viewModel::setTag,
                onState = viewModel::setState,
                onSince = viewModel::setSince,
                onClearFilters = viewModel::clearFilters,
                onOpenItem = { id ->
                    viewModel.opened(id)
                    navigation.onOpenItem(id)
                },
                onOpenPlace = navigation.onOpenPlace,
                onOpenContainer = navigation.onOpenContainer,
                onBack = navigation.onBack,
            )
        }
    SearchScreen(state, mode, callbacks, places, containers, tags, photoPath = {
        app.shared.photoStore.absolutePath(it)
    })
}

data class SearchCallbacks(
    val onQueryChange: (String) -> Unit,
    val onPickSuggestion: (Suggestion) -> Unit,
    val onPlace: (placeId: String?, containerId: String?) -> Unit,
    val onTag: (String?) -> Unit,
    val onState: (ItemStateFilter?) -> Unit,
    val onSince: (SincePeriod?) -> Unit,
    val onClearFilters: () -> Unit,
    val onOpenItem: (String) -> Unit,
    val onOpenPlace: (String) -> Unit,
    val onOpenContainer: (String) -> Unit,
    val onBack: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    mode: SearchMode,
    callbacks: SearchCallbacks,
    places: List<Place> = emptyList(),
    containers: List<Container> = emptyList(),
    tags: List<String> = emptyList(),
    photoPath: (String) -> String = { it },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QueryField(state.query, callbacks.onQueryChange)
            ModeBanner(mode)
            FilterRow(state, callbacks, places, containers, tags)
            val shownSuggestions = state.suggestions.filter { !it.text.equals(state.query.trim(), ignoreCase = true) }
            if (shownSuggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shownSuggestions, key = { it.kind.name + it.text }) { suggestion ->
                        SuggestionChip(onClick = {
                            callbacks.onPickSuggestion(suggestion)
                        }, label = { Text(suggestion.text) })
                    }
                }
            }
            Results(state, callbacks, photoPath)
        }
    }
}

@Composable
private fun QueryField(
    query: String,
    onChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search_field"),
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
    )
}

/** Says when the search runs on keywords only (C1 fallback, rule 7) or is still computing its vectors. */
@Composable
private fun ModeBanner(mode: SearchMode) {
    val text =
        when (mode) {
            is SearchMode.Hybrid -> return
            is SearchMode.Indexing -> stringResource(R.string.search_mode_indexing, mode.done, mode.total)
            is SearchMode.KeywordOnly ->
                when (mode.reason) {
                    KeywordOnlyReason.NO_MODEL -> stringResource(R.string.search_mode_no_model)
                    KeywordOnlyReason.UNSUPPORTED_DEVICE -> stringResource(R.string.search_mode_unsupported)
                    KeywordOnlyReason.LOAD_FAILED -> stringResource(R.string.search_mode_failed)
                }
        }
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (mode is SearchMode.Indexing && mode.total > 0) {
            LinearProgressIndicator(
                progress = { mode.done.toFloat() / mode.total },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/** C11: place (or container), state, tag, recent moves. */
@Composable
private fun FilterRow(
    state: SearchUiState,
    callbacks: SearchCallbacks,
    places: List<Place>,
    containers: List<Container>,
    tags: List<String>,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val placeLabel =
        state.containerId?.let { id -> containers.firstOrNull { it.id == id }?.label }
            ?: state.placeId?.let { id -> places.firstOrNull { it.id == id }?.name }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = placeLabel != null,
                onClick = { if (placeLabel != null) callbacks.onPlace(null, null) else picking = true },
                label = { Text(placeLabel ?: stringResource(R.string.search_filter_place)) },
                trailingIcon = if (placeLabel != null) closeIcon() else null,
            )
        }
        item {
            MenuChip(
                label = stringResource(R.string.search_filter_state),
                selected = state.state?.let { stateLabel(it) },
                options = ItemStateFilter.entries.map { it to stateLabel(it) },
                onSelect = callbacks.onState,
            )
        }
        if (tags.isNotEmpty() || state.tag != null) {
            item {
                MenuChip(
                    label = stringResource(R.string.search_filter_tag),
                    selected = state.tag,
                    options = tags.map { it to it },
                    onSelect = callbacks.onTag,
                )
            }
        }
        item {
            MenuChip(
                label = stringResource(R.string.search_filter_since),
                selected = state.since?.let { sinceLabel(it) },
                options = SincePeriod.entries.map { it to sinceLabel(it) },
                onSelect = callbacks.onSince,
            )
        }
    }
    if (picking) {
        LocationPickerDialog(
            title = stringResource(R.string.search_filter_place),
            places = places,
            containers = containers,
            onPick = { ref ->
                picking = false
                when (ref) {
                    is LocationRef.InPlace -> callbacks.onPlace(ref.placeId, null)
                    is LocationRef.InContainer -> callbacks.onPlace(null, ref.containerId)
                }
            },
            onDismiss = { picking = false },
        )
    }
}

/** A filter chip that opens a menu when empty and clears itself when set. */
@Composable
private fun <T> MenuChip(
    label: String,
    selected: String?,
    options: List<Pair<T, String>>,
    onSelect: (T?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { if (selected != null) onSelect(null) else open = true },
            label = { Text(selected ?: label) },
            trailingIcon = if (selected != null) closeIcon() else null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}

private fun closeIcon(): @Composable () -> Unit = {
    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
}

@Composable
private fun stateLabel(state: ItemStateFilter): String = stringResource(
    when (state) {
        ItemStateFilter.STORED -> R.string.search_state_stored
        ItemStateFilter.TAKEN -> R.string.search_state_taken
        ItemStateFilter.LENT -> R.string.search_state_lent
        ItemStateFilter.ARCHIVED -> R.string.search_state_archived
    },
)

@Composable
private fun sinceLabel(since: SincePeriod): String = stringResource(
    when (since) {
        SincePeriod.WEEK -> R.string.search_since_week
        SincePeriod.MONTH -> R.string.search_since_month
        SincePeriod.YEAR -> R.string.search_since_year
    },
)

@Composable
private fun Results(
    state: SearchUiState,
    callbacks: SearchCallbacks,
    photoPath: (String) -> String,
) {
    val result = state.result ?: return
    if (result.hits.isEmpty()) {
        val message =
            when {
                result.query.isBlank() && result.filters.isEmpty -> stringResource(R.string.search_start)
                result.query.isBlank() -> stringResource(R.string.search_empty_filters)
                else -> stringResource(R.string.search_empty, result.query.trim())
            }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        val answer = result.answer
        val rest = if (answer != null) result.hits.drop(1) else result.hits
        if (answer != null) {
            item(key = "answer") { AnswerCard(answer, callbacks, photoPath) }
        }
        items(rest, key = { it.view.item.id }) { hit ->
            ItemRow(hit.view, onOpen = { callbacks.onOpenItem(hit.view.item.id) })
            HorizontalDivider()
        }
    }
}

/** C4 and C6: « Passeport — Maison › Chambre › Commode, rangé il y a 3 jours », each place a link. */
@Composable
private fun AnswerCard(
    answer: AnswerLine,
    callbacks: SearchCallbacks,
    photoPath: (String) -> String,
) {
    Card(
        onClick = { callbacks.onOpenItem(answer.itemId) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("search_answer"),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            answer.photoThumbName?.let { Thumbnail(photoPath(it), modifier = Modifier.size(72.dp)) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(answer.itemName, style = MaterialTheme.typography.titleLarge)
                Breadcrumb(answer, callbacks)
                Text(
                    answerState(answer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Breadcrumb(
    answer: AnswerLine,
    callbacks: SearchCallbacks,
) {
    if (answer.places.isEmpty() && answer.containerId == null) {
        Text(stringResource(R.string.state_unknown_place), style = MaterialTheme.typography.bodyLarge)
        return
    }
    val separator = stringResource(R.string.path_separator)
    val linkStyle =
        TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val text =
        buildAnnotatedString {
            answer.places.forEachIndexed { i, place ->
                if (i > 0) append(separator)
                withLink(LinkAnnotation.Clickable(place.id, linkStyle) { callbacks.onOpenPlace(place.id) }) {
                    append(place.name)
                }
            }
            val containerId = answer.containerId
            val label = answer.containerLabel
            if (containerId != null && label != null) {
                if (answer.places.isNotEmpty()) append(separator)
                withLink(LinkAnnotation.Clickable(containerId, linkStyle) { callbacks.onOpenContainer(containerId) }) {
                    append(label)
                }
            }
        }
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun answerState(answer: AnswerLine): String {
    val context = LocalContext.current
    val relative =
        DateUtils
            .getRelativeTimeSpanString(
                answer.since.toEpochMilliseconds(),
                System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS,
            )
            .toString()
            .lowercase()
    val state =
        when (answer.status) {
            LocationStatus.STORED -> context.getString(R.string.search_answer_stored, relative)
            LocationStatus.TAKEN -> context.getString(R.string.search_answer_taken, relative)
            LocationStatus.LENT -> context.getString(R.string.search_answer_lent, answer.lentTo.orEmpty(), relative)
        }
    return if (answer.archived) context.getString(R.string.search_answer_archived, state) else state
}

@Preview
@Composable
private fun SearchScreenPreview() {
    val now = Clock.System.now()
    val item = Item("i1", "Passeport", null, emptyList(), null, null, false, false, false, now, null)
    val view =
        ItemView(
            item,
            LocationState("i1", "p3", null, LocationStatus.STORED, null, now),
            LocationPath(emptyList(), null),
        )
    val answer =
        AnswerLine(
            itemId = "i1",
            itemName = "Passeport",
            places = listOf(AnswerPlace("p1", "Maison"), AnswerPlace("p2", "Chambre"), AnswerPlace("p3", "Commode")),
            containerId = null,
            containerLabel = null,
            status = LocationStatus.STORED,
            archived = false,
            lentTo = null,
            since = now - kotlin.time.Duration.parse("3d"),
        )
    AppTheme {
        SearchScreen(
            state =
                SearchUiState(
                    query = "passport",
                    result = SearchResult("passport", SearchFilters(), listOf(SearchHit(view, MatchKind.BOTH)), answer),
                ),
            mode = SearchMode.Indexing(12, 40),
            callbacks = SearchCallbacks({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}),
        )
    }
}

@Preview
@Composable
private fun SearchEmptyPreview() {
    AppTheme {
        SearchScreen(
            state = SearchUiState(
                query = "xylophone",
                result = SearchResult("xylophone", SearchFilters(), emptyList(), null),
            ),
            mode = SearchMode.KeywordOnly(KeywordOnlyReason.NO_MODEL),
            callbacks = SearchCallbacks({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}),
        )
    }
}
