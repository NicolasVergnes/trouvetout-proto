package app.trouvetout.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.trouvetout.android.R
import app.trouvetout.core.domain.model.Container
import app.trouvetout.core.domain.model.Place
import app.trouvetout.core.domain.usecase.LocationRef

/** A place or a container the user picks as a destination (move an item, move a container, merge a place). */
@Composable
fun LocationPickerDialog(
    title: String,
    places: List<Place>,
    containers: List<Container>,
    /** Ids never offered (the place itself and its descendants when merging). */
    excludedPlaceIds: Set<String> = emptySet(),
    withContainers: Boolean = true,
    onPick: (LocationRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = flatten(places, excludedPlaceIds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (rows.isEmpty()) Text(stringResource(R.string.picker_no_place))
                for ((place, depth) in rows) {
                    TextButton(onClick = { onPick(LocationRef.InPlace(place.id)) }) {
                        Text(place.name, modifier = Modifier.padding(start = (depth * INDENT_DP).dp))
                    }
                    if (withContainers) {
                        for (container in containers.filter { it.placeId == place.id }) {
                            TextButton(onClick = { onPick(LocationRef.InContainer(container.id)) }) {
                                Text(
                                    "▢ ${container.label}",
                                    modifier = Modifier.padding(start = ((depth + 1) * INDENT_DP).dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Depth-first order of the active places (merged ones are hidden by the repository), with their depth. */
private fun flatten(
    places: List<Place>,
    excluded: Set<String>,
): List<Pair<Place, Int>> {
    val byParent = places.filter { it.mergedInto == null }.groupBy { it.parentId }
    val out = ArrayList<Pair<Place, Int>>()

    fun walk(
        parentId: String?,
        depth: Int,
    ) {
        for (place in byParent[parentId].orEmpty().sortedBy { it.name }) {
            if (place.id in excluded) continue
            out += place to depth
            if (depth < MAX_DEPTH) walk(place.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}

/** Ids of [placeId] and all its descendants (a place cannot be merged into itself or below itself). */
fun descendantsOf(
    placeId: String,
    places: List<Place>,
): Set<String> {
    val byParent = places.groupBy { it.parentId }
    val out = HashSet<String>()
    val queue = ArrayDeque(listOf(placeId))
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (out.add(id)) byParent[id]?.forEach { queue.addLast(it.id) }
    }
    return out
}

private const val INDENT_DP = 16

/** Guard against a cyclic tree only; the core accepts any depth (A2). */
private const val MAX_DEPTH = 64
