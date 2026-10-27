package app.trouvetout.core.domain.usecase

import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventPayload
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.places.PlaceTemplate
import app.trouvetout.core.domain.usecase.CommandContext.Companion.cleanName
import app.trouvetout.core.domain.usecase.CommandContext.Companion.cleanTags
import app.trouvetout.core.domain.usecase.CommandContext.Companion.rejected

/** Place use cases (A2, D1, D6). */
public class PlaceCommands internal constructor(
    private val ctx: CommandContext,
) {
    public suspend fun create(
        name: String,
        parentId: String? = null,
        kind: PlaceKind = PlaceKind.SPOT,
        aliases: List<String> = emptyList(),
        source: EventSource,
        rawInput: String? = null,
    ): CommandResult {
        val cleanName = cleanName(name) ?: return rejected(Rejection.BLANK_NAME)
        parentId?.let { ctx.checkPlace(it) }?.let { return rejected(it) }
        val payload = EventPayload.PlaceCreated(ctx.newId(), cleanName, parentId, kind, cleanTags(aliases))
        return ctx.commit(listOf(ctx.event(payload, source, rawInput)))
    }

    /**
     * D2: creates a proposed place and its proposed rooms or spots in one go (one event per place, all committed
     * together). The user asked for the template; nothing is ever created without that tap.
     */
    public suspend fun createFromTemplate(
        template: PlaceTemplate,
        source: EventSource,
    ): CommandResult {
        val rootName = cleanName(template.name) ?: return rejected(Rejection.BLANK_NAME)
        val rootId = ctx.newId()
        val events = ArrayList<Event>()
        events += ctx.event(EventPayload.PlaceCreated(rootId, rootName, null, template.kind, emptyList()), source)
        for (child in template.children) {
            val childName = cleanName(child.name) ?: continue
            events +=
                ctx.event(EventPayload.PlaceCreated(ctx.newId(), childName, rootId, child.kind, emptyList()), source)
        }
        return ctx.commit(events)
    }

    /** New name and full alias list (B7 keeps the user's wording as an alias). */
    public suspend fun rename(
        placeId: String,
        name: String,
        aliases: List<String>,
        source: EventSource,
    ): CommandResult {
        val cleanName = cleanName(name) ?: return rejected(Rejection.BLANK_NAME)
        ctx.checkPlace(placeId)?.let { return rejected(it) }
        return ctx.commit(listOf(ctx.event(EventPayload.PlaceRenamed(placeId, cleanName, cleanTags(aliases)), source)))
    }

    /** Merges [placeId] into [intoPlaceId]; the target must not be a descendant of the source (D6). */
    public suspend fun merge(
        placeId: String,
        intoPlaceId: String,
        source: EventSource,
    ): CommandResult {
        val rejection =
            when {
                placeId == intoPlaceId -> Rejection.SAME_PLACE
                else ->
                    ctx.checkPlace(placeId)
                        ?: ctx.checkPlace(intoPlaceId)
                        ?: Rejection.PLACE_CYCLE.takeIf { isDescendant(candidate = intoPlaceId, of = placeId) }
            }
        if (rejection != null) return rejected(rejection)
        return ctx.commit(listOf(ctx.event(EventPayload.PlaceMerged(placeId, intoPlaceId), source)))
    }

    /** Only an empty place (no child place, container or item) can be deleted. */
    public suspend fun delete(
        placeId: String,
        source: EventSource,
    ): CommandResult {
        ctx.checkPlace(placeId)?.let { return rejected(it) }
        val empty =
            ctx.read { db ->
                db.placeQueries.selectChildren(placeId).executeAsList().isEmpty() &&
                    db.containerQueries.selectByPlace(placeId).executeAsList().isEmpty() &&
                    db.locationStateQueries.countInPlace(placeId).executeAsOne() == 0L
            }
        if (!empty) return rejected(Rejection.PLACE_NOT_EMPTY)
        return ctx.commit(listOf(ctx.event(EventPayload.PlaceDeleted(placeId), source)))
    }

    private suspend fun isDescendant(
        candidate: String,
        of: String,
    ): Boolean = ctx.read { db ->
        val seen = HashSet<String>()
        var current: String? = candidate
        var found = false
        while (current != null && seen.add(current) && !found) {
            val parent = db.placeQueries.selectById(current).executeAsOneOrNull()?.parent_id
            if (parent == of) found = true
            current = parent
        }
        found
    }
}
