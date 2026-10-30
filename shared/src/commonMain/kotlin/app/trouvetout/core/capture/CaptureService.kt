package app.trouvetout.core.capture

import app.trouvetout.core.data.db.TrouveToutDb
import app.trouvetout.core.data.db.toContainer
import app.trouvetout.core.data.db.toItem
import app.trouvetout.core.data.db.toPlace
import app.trouvetout.core.domain.model.Event
import app.trouvetout.core.domain.model.EventSource
import app.trouvetout.core.domain.model.PlaceKind
import app.trouvetout.core.domain.usecase.CommandResult
import app.trouvetout.core.domain.usecase.ContainerCommands
import app.trouvetout.core.domain.usecase.ItemCommands
import app.trouvetout.core.domain.usecase.ItemDraft
import app.trouvetout.core.domain.usecase.LocationRef
import app.trouvetout.core.domain.usecase.PlaceCommands
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** One object of the sentence and the existing items it may designate (A14 duplicates, movement targets). */
public data class ItemTarget(
    val mention: ItemMention,
    val matches: List<DuplicateMatch>,
) {
    /** An existing item close enough to act on without asking. */
    public val confident: DuplicateMatch? get() = matches.firstOrNull { it.score >= CaptureService.AUTO_ITEM }

    /** A14 / movements: the user must choose when a store has look-alikes or a movement has no confident target. */
    public fun needsDecision(movesExisting: Boolean): Boolean =
        if (movesExisting) confident == null else matches.isNotEmpty()
}

/** Everything the confirmation card needs before anything is written (B6, B7). */
public data class CaptureProposal(
    val parse: ParseResult,
    /** Null when the sentence names no place. */
    val place: PlaceResolution?,
    val targets: List<ItemTarget>,
    /** Set when the sentence cannot be captured at all (too long); [CaptureService.commit] refuses it. */
    val problem: CaptureProblem? = null,
) {
    public val movesExistingItems: Boolean get() = parse.intent in MOVEMENTS
    public val needsPlaceDecision: Boolean get() = place != null && place !is PlaceResolution.Resolved
    public val needsItemDecision: Boolean get() = targets.any { it.needsDecision(movesExistingItems) }

    /** Index of the next target to ask the user about, skipping the ones in [answered]; null when none is left. */
    public fun nextQuestion(answered: List<Int>): Int? =
        targets.indices.firstOrNull { it !in answered && targets[it].needsDecision(movesExistingItems) }

    private companion object {
        val MOVEMENTS = setOf(Intent.TAKE, Intent.RETURN, Intent.MOVE_TO, Intent.LEND, Intent.RETURN_FROM_LEND)
    }
}

/** The user's answer to « lieu inconnu : créer ou rattacher ? » (B7). */
public sealed interface PlaceChoice {
    public data class Existing(
        val id: String,
        val isContainer: Boolean,
        /** Remember the dictated text as an alias of that place. */
        val rememberAlias: Boolean = false,
    ) : PlaceChoice

    public data class CreateNew(
        /** Root first; the last name becomes a container when it reads like one (« carton 7 »). */
        val names: List<String>,
        val underId: String?,
        val underIsContainer: Boolean,
    ) : PlaceChoice
}

public enum class CaptureProblem {
    NOTHING_UNDERSTOOD,
    PLACE_UNDECIDED,
    ITEM_NOT_FOUND,
    PERSON_MISSING,
    COMMAND_REJECTED,
    INPUT_TOO_LONG,
}

/** The user's answer for one target of a proposal: act on [existingId], or null to create a new object. */
public data class ItemChoice(
    val index: Int,
    val existingId: String?,
)

public sealed interface CaptureOutcome {
    public data class Recorded(
        val events: List<Event>,
        val itemIds: List<String>,
        val location: LocationRef?,
    ) : CaptureOutcome

    /** A question: the host runs the search (lot 6). */
    public data class Search(
        val query: String,
    ) : CaptureOutcome

    public data class Refused(
        val problem: CaptureProblem,
    ) : CaptureOutcome
}

/**
 * Text capture pipeline (architecture §4): sentence → [Parser] → [PlaceResolver] / [DuplicateDetector] →
 * [CaptureProposal] shown on the confirmation card → [commit] turns it into events through the lot 1 commands.
 * Nothing is written before [commit].
 */
public class CaptureService internal constructor(
    private val db: TrouveToutDb,
    private val items: ItemCommands,
    private val places: PlaceCommands,
    private val containers: ContainerCommands,
    private val dispatcher: CoroutineDispatcher,
    private val defaultLang: Lang = Lang.FR,
) {
    /** [lang] is the UI language, used only when a sentence gives no clue about its own. */
    public suspend fun propose(
        text: String,
        lang: Lang? = null,
    ): CaptureProposal = withContext(dispatcher) {
        val language = lang ?: defaultLang
        // A sentence is a few words: anything longer is refused before any quadratic comparison runs (and before
        // a rawInput that no export line could hold is written).
        if (text.length > MAX_INPUT_CHARS || Tokens.tokenize(text).size > MAX_INPUT_TOKENS) {
            return@withContext tooLong(text, language)
        }
        val snapshot = snapshot()
        // The host's language only breaks ties: a French sentence typed under an English UI still parses as French.
        val parse = Parser(snapshot.itemNames.map { it.second }, language).parse(text, lang = null)
        if (parse.items.size > MAX_MENTIONS) return@withContext tooLong(text, language)
        val place = parse.place?.let { PlaceResolver(snapshot.locations, parse.lang).resolve(it) }
        val detector = DuplicateDetector(snapshot.itemNames)
        val targets = parse.items.map { mention -> ItemTarget(mention, detector.find(mention.name)) }
        CaptureProposal(parse, place, targets)
    }

    private fun tooLong(
        text: String,
        language: Lang,
    ): CaptureProposal {
        val parse = ParseResult(Intent.UNKNOWN, emptyList(), null, null, null, language, 0f, text)
        return CaptureProposal(parse, null, emptyList(), CaptureProblem.INPUT_TOO_LONG)
    }

    /** Autocomplete of places and containers while typing (B4). */
    public suspend fun suggestPlaces(
        prefix: String,
        limit: Int = PlaceResolver.MAX_SUGGESTIONS,
    ): List<LocationCandidate> = withContext(dispatcher) { PlaceResolver(locations()).suggest(prefix, limit) }

    /**
     * B10 « je range » : the sentence goes into [location] unless it names a place itself; a bare list of
     * objects (« marteau, tournevis ») is a store there. Movements and questions are unchanged. When [location]
     * no longer exists the plain proposal is returned (the host then asks, as for any sentence without a place).
     */
    public suspend fun proposeIn(
        text: String,
        location: LocationRef,
        lang: Lang? = null,
    ): CaptureProposal {
        val proposal = propose(text, lang)
        if (proposal.problem != null || proposal.parse.intent != Intent.STORE || proposal.place != null) return proposal
        val candidate = withContext(dispatcher) { candidateOf(location) } ?: return proposal
        return proposal.copy(place = PlaceResolution.Resolved(LocationMatch(candidate, 1.0)))
    }

    private fun candidateOf(location: LocationRef): LocationCandidate? = when (location) {
        is LocationRef.InPlace ->
            db.placeQueries.selectById(location.placeId).executeAsOneOrNull()?.toPlace()
                ?.takeIf { it.deletedAt == null && it.mergedInto == null }
                ?.let { LocationCandidate(it.id, it.name, it.aliases, it.parentId, isContainer = false) }
        is LocationRef.InContainer ->
            db.containerQueries.selectById(location.containerId).executeAsOneOrNull()?.toContainer()
                ?.takeIf { it.deletedAt == null }
                ?.let { LocationCandidate(it.id, it.label, emptyList(), it.placeId, isContainer = true) }
    }

    /**
     * Writes the proposal. [placeChoice] is required when [CaptureProposal.needsPlaceDecision]; [itemChoices] gives,
     * per target, the existing item to act on (null = create for STORE, = ask for movements).
     */
    public suspend fun commit(
        proposal: CaptureProposal,
        placeChoice: PlaceChoice? = null,
        itemChoices: List<ItemChoice> = emptyList(),
        source: EventSource = EventSource.TEXT,
    ): CaptureOutcome = withContext(dispatcher) {
        val parse = proposal.parse
        proposal.problem?.let { return@withContext CaptureOutcome.Refused(it) }
        when (parse.intent) {
            Intent.UNKNOWN -> return@withContext CaptureOutcome.Refused(CaptureProblem.NOTHING_UNDERSTOOD)
            Intent.QUERY -> return@withContext CaptureOutcome.Search(parse.query ?: parse.input)
            else -> Unit
        }
        // Every refusal that can be known up front is raised before the first write (the commit is not transactional).
        val existingFor =
            proposal.targets.mapIndexed { index, target ->
                itemChoices.firstOrNull { it.index == index }?.existingId
                    ?: target.confident?.itemId?.takeIf { proposal.movesExistingItems }
            }
        val upFront = refusalBeforeWriting(proposal, placeChoice, existingFor)
        if (upFront != null) return@withContext CaptureOutcome.Refused(upFront)
        val writer = Writer(parse, source)
        val location =
            writer.location(proposal, placeChoice)
                ?: return@withContext CaptureOutcome.Refused(CaptureProblem.PLACE_UNDECIDED)
        for ((index, target) in proposal.targets.withIndex()) {
            val problem = writer.apply(target.mention, existingFor[index], location.ref)
            if (problem != null) return@withContext CaptureOutcome.Refused(problem)
        }
        CaptureOutcome.Recorded(writer.events, writer.itemIds, location.ref)
    }

    private fun refusalBeforeWriting(
        proposal: CaptureProposal,
        placeChoice: PlaceChoice?,
        existingFor: List<String?>,
    ): CaptureProblem? {
        val parse = proposal.parse
        return when {
            parse.intent == Intent.LEND && parse.person.isNullOrBlank() -> CaptureProblem.PERSON_MISSING
            proposal.movesExistingItems && existingFor.any { it == null } -> CaptureProblem.ITEM_NOT_FOUND
            proposal.needsPlaceDecision && placeChoice == null -> CaptureProblem.PLACE_UNDECIDED
            parse.intent == Intent.MOVE_TO && proposal.place == null -> CaptureProblem.PLACE_UNDECIDED
            else -> null
        }
    }

    private class Located(
        val ref: LocationRef?,
    )

    /** Accumulates the events of one commit. */
    private inner class Writer(
        private val parse: ParseResult,
        private val source: EventSource,
    ) {
        val events = ArrayList<Event>()
        val itemIds = ArrayList<String>()

        suspend fun location(
            proposal: CaptureProposal,
            choice: PlaceChoice?,
        ): Located? {
            val resolution = proposal.place ?: return Located(null)
            val decided =
                choice ?: when (resolution) {
                    is PlaceResolution.Resolved -> PlaceChoice.Existing(
                        resolution.match.candidate.id,
                        resolution.match.candidate.isContainer,
                    )
                    else -> return null
                }
            return when (decided) {
                is PlaceChoice.Existing -> {
                    if (decided.rememberAlias && !decided.isContainer) {
                        rememberAlias(decided.id, PlaceResolver.placeName(parse.place.orEmpty(), parse.lang))
                    }
                    Located(
                        if (decided.isContainer) {
                            LocationRef.InContainer(
                                decided.id,
                            )
                        } else {
                            LocationRef.InPlace(decided.id)
                        },
                    )
                }
                is PlaceChoice.CreateNew -> create(decided)
            }
        }

        private suspend fun rememberAlias(
            placeId: String,
            alias: String,
        ) {
            val row = db.placeQueries.selectById(placeId).executeAsOneOrNull()?.toPlace() ?: return
            if (alias.isBlank() ||
                row.aliases.any { it.equals(alias, ignoreCase = true) } ||
                row.name.equals(alias, ignoreCase = true)
            ) {
                return
            }
            record(places.rename(placeId, row.name, row.aliases + alias, source))
        }

        private suspend fun create(choice: PlaceChoice.CreateNew): Located? {
            var parentPlaceId: String? =
                if (choice.underIsContainer) {
                    choice.underId?.let {
                        db.containerQueries.selectById(it).executeAsOneOrNull()?.toContainer()?.placeId
                    }
                } else {
                    choice.underId
                }
            var ref: LocationRef? = parentPlaceId?.let { LocationRef.InPlace(it) }
            for ((index, name) in choice.names.withIndex()) {
                val last = index == choice.names.lastIndex
                val container = if (last) containerNumber(name) else null
                if (container != null && parentPlaceId != null) {
                    val created = containers.create(
                        name,
                        parentPlaceId,
                        container.takeIf {
                            it >= 0
                        },
                        source,
                        parse.input,
                    )
                    val id = record(created)?.let { ContainerIdOf(it) } ?: return null
                    ref = LocationRef.InContainer(id.value)
                } else {
                    val kind = if (parentPlaceId == null) PlaceKind.ROOT else PlaceKind.SPOT
                    val created = places.create(name, parentPlaceId, kind, emptyList(), source, parse.input)
                    val id = record(created)?.let { PlaceIdOf(it) } ?: return null
                    parentPlaceId = id.value
                    ref = LocationRef.InPlace(id.value)
                }
            }
            return Located(ref)
        }

        suspend fun apply(
            mention: ItemMention,
            existing: String?,
            ref: LocationRef?,
        ): CaptureProblem? {
            val result =
                command(mention, existing, ref)
                    ?: return if (existing == null) CaptureProblem.ITEM_NOT_FOUND else CaptureProblem.PLACE_UNDECIDED
            val done = record(result)
            val id = existing ?: done?.events?.firstNotNullOfOrNull { it.subjectItemId() }
            if (done == null || id == null) return CaptureProblem.COMMAND_REJECTED
            itemIds += id
            return null
        }

        private suspend fun command(
            mention: ItemMention,
            existing: String?,
            ref: LocationRef?,
        ): CommandResult? = when (parse.intent) {
            Intent.STORE -> storeCommand(mention, existing, ref)
            Intent.TAKE -> existing?.let { items.take(it, source, parse.input) }
            Intent.RETURN -> existing?.let { putBackCommand(it, ref) }
            Intent.MOVE_TO -> if (existing != null &&
                ref != null
            ) {
                items.move(existing, ref, source, parse.input)
            } else {
                null
            }
            Intent.LEND -> existing?.let { items.lend(it, parse.person.orEmpty(), source, parse.input) }
            Intent.RETURN_FROM_LEND -> existing?.let { items.putBack(it, source, parse.input) }
            Intent.QUERY, Intent.UNKNOWN -> null
        }

        private suspend fun storeCommand(
            mention: ItemMention,
            existing: String?,
            ref: LocationRef?,
        ): CommandResult = when {
            existing != null && ref != null -> items.store(existing, ref, source, parse.input)
            existing != null -> CommandResult.Done(emptyList()) // already there: nothing to write
            else -> items.create(
                ItemDraft(mention.name, quantity = mention.quantity, unit = mention.unit),
                ref,
                source,
                parse.input,
            )
        }

        private suspend fun putBackCommand(
            itemId: String,
            ref: LocationRef?,
        ): CommandResult = if (ref !=
            null
        ) {
            items.store(itemId, ref, source, parse.input)
        } else {
            items.putBack(itemId, source, parse.input)
        }

        private fun record(result: CommandResult): CommandResult.Done? {
            val done = result as? CommandResult.Done ?: return null
            events += done.events
            return done
        }
    }

    private class Snapshot(
        val locations: List<LocationCandidate>,
        val itemNames: List<Pair<String, String>>,
    )

    private fun snapshot(): Snapshot = Snapshot(
        locations = locations(),
        itemNames = db.itemQueries.selectActive().executeAsList().map { it.toItem() }.map { it.id to it.name },
    )

    private fun locations(): List<LocationCandidate> {
        val placeRows = db.placeQueries.selectActive().executeAsList().map { it.toPlace() }
        val containerRows = db.containerQueries.selectActive().executeAsList().map { it.toContainer() }
        return placeRows.map { LocationCandidate(it.id, it.name, it.aliases, it.parentId, isContainer = false) } +
            containerRows.map { LocationCandidate(it.id, it.label, emptyList(), it.placeId, isContainer = true) }
    }

    public companion object {
        /** Similarity from which an existing item is acted on without asking (« je prends la perceuse »). */
        public const val AUTO_ITEM: Double = 0.85

        /** Bounds of one captured sentence; well under the 64 KiB an export line may hold. */
        public const val MAX_INPUT_CHARS: Int = 500
        public const val MAX_INPUT_TOKENS: Int = 80
        public const val MAX_MENTIONS: Int = 20

        private val CONTAINER_WORDS =
            setOf(
                "carton",
                "cartons",
                "boite",
                "boites",
                "bac",
                "bacs",
                "caisse",
                "caisses",
                "valise",
                "valises",
                "sac",
                "sacs",
                "malle",
                "coffre",
                "panier",
                "box",
                "boxes",
                "bin",
                "bins",
                "crate",
                "crates",
                "bag",
                "bags",
                "suitcase",
                "basket",
                "trunk",
                "case",
                "pouch",
                "pochette",
                "trousse",
            )

        /**
         * -1 when the name reads like a movable container without a number, its number when it has one, null when
         * it is a plain place.
         */
        internal fun containerNumber(name: String): Long? {
            val toks = Tokens.tokenize(name)
            if (toks.isEmpty() || toks[0].key !in CONTAINER_WORDS) return null
            val number = toks.firstOrNull { it.isNumber }?.key?.toLongOrNull()
            return number ?: -1
        }
    }
}

private class PlaceIdOf(
    done: CommandResult.Done,
) {
    val value: String =
        done.events.firstNotNullOf {
            (it.payload as? app.trouvetout.core.domain.model.EventPayload.PlaceCreated)?.placeId
        }
}

private class ContainerIdOf(
    done: CommandResult.Done,
) {
    val value: String =
        done.events.firstNotNullOf {
            (it.payload as? app.trouvetout.core.domain.model.EventPayload.ContainerCreated)?.containerId
        }
}

private fun Event.subjectItemId(): String? = when (val p = payload) {
    is app.trouvetout.core.domain.model.EventPayload.ItemCreated -> p.itemId
    is app.trouvetout.core.domain.model.EventPayload.ItemStored -> p.itemId
    else -> null
}
