package com.wyattfleming.touchframe.home

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One open item on a Home Assistant to-do list (Todoist, Local To-do, Shopping list…). */
data class TouchTodoItem(val entityId: String, val uid: String, val summary: String, val due: LocalDate?) {
    /** Unique across lists: the same uid may exist on two lists. */
    val key: String get() = "$entityId\u0000$uid"
}

/** A Home Assistant `todo.*` entity and the name people know it by. */
data class TouchTodoListRef(val entityId: String, val name: String)

/**
 * The lists Home shows. Nothing is shown until someone chooses, so a personal list never
 * lands on the family wall by itself. Groceries is one list shown as a single summary line.
 */
data class TouchTodoSelection(val taskLists: List<TouchTodoListRef>, val groceries: TouchTodoListRef?) {
    val chosen: Boolean get() = taskLists.isNotEmpty() || groceries != null
    val entityIds: List<String> get() = (taskLists + listOfNotNull(groceries)).map(TouchTodoListRef::entityId).distinct()

    fun encode(): String = JSONObject()
        .put("tasks", JSONArray().apply { taskLists.forEach { put(it.toJson()) } })
        .put("groceries", groceries?.toJson() ?: JSONObject.NULL)
        .toString()

    companion object {
        val NONE = TouchTodoSelection(emptyList(), null)
        const val MAX_TASK_LISTS = 4

        /** Drops invalid ids, repeats and any task list that is also the groceries list. */
        fun of(taskLists: List<TouchTodoListRef>, groceries: TouchTodoListRef?): TouchTodoSelection {
            val grocery = groceries?.takeIf { TouchTodoParser.isEntityId(it.entityId) }
            val tasks = taskLists.filter { TouchTodoParser.isEntityId(it.entityId) && it.entityId != grocery?.entityId }
                .distinctBy(TouchTodoListRef::entityId).take(MAX_TASK_LISTS)
            return TouchTodoSelection(tasks, grocery)
        }

        fun decode(value: String?): TouchTodoSelection = runCatching {
            val root = JSONObject(value ?: return NONE)
            val tasks = root.optJSONArray("tasks")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toRef() } }.orEmpty()
            of(tasks, root.optJSONObject("groceries")?.toRef())
        }.getOrDefault(NONE)

        private fun TouchTodoListRef.toJson() = JSONObject().put("entity_id", entityId).put("name", name)

        private fun JSONObject.toRef(): TouchTodoListRef? {
            val id = optString("entity_id").takeIf(TouchTodoParser::isEntityId) ?: return null
            return TouchTodoListRef(id, TouchTodoParser.cleanName(optString("name"), id))
        }
    }
}

enum class TouchTodoState { NOT_CHOSEN, LOADING, READY, OFFLINE, SIGN_IN_REQUIRED, LIST_MISSING }

/** What Home renders for To do and Groceries; items are open items only, in list order. */
data class TouchTodoBoard(
    val selection: TouchTodoSelection,
    val items: Map<String, List<TouchTodoItem>>,
    val state: TouchTodoState,
    val updatedEpochMillis: Long?,
) {
    val tasks: List<TouchTodoItem> get() = selection.taskLists.flatMap { items[it.entityId].orEmpty() }
    val groceries: List<TouchTodoItem> get() = selection.groceries?.let { items[it.entityId] }.orEmpty()

    companion object {
        fun notChosen() = TouchTodoBoard(TouchTodoSelection.NONE, emptyMap(), TouchTodoState.NOT_CHOSEN, null)
    }
}

data class TouchTodoDiscovery(val lists: List<TouchTodoListRef>, val state: TouchTodoState)

/** Parses only the bounded fields Home needs from `/api/states` and `todo.get_items`. */
object TouchTodoParser {
    const val MAX_LISTS = 40
    const val MAX_ITEMS_PER_LIST = 100
    const val MAX_SUMMARY_CHARS = 200
    private const val MAX_NAME_CHARS = 60
    private val ENTITY_ID = Regex("todo\\.[a-z0-9_]{1,120}")

    fun isEntityId(value: String?): Boolean = value != null && ENTITY_ID.matches(value)

    fun cleanName(value: String?, entityId: String): String =
        value?.trim()?.filter { !it.isISOControl() }?.take(MAX_NAME_CHARS)?.takeIf(String::isNotBlank)
            ?: entityId.removePrefix("todo.").replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /** Every `todo.*` entity in a `/api/states` array, by name. */
    fun parseLists(body: String): List<TouchTodoListRef> = runCatching {
        val states = JSONArray(body)
        (0 until states.length()).mapNotNull { index ->
            val state = states.optJSONObject(index) ?: return@mapNotNull null
            val id = state.optString("entity_id").takeIf(::isEntityId) ?: return@mapNotNull null
            if (state.optString("state") == "unavailable") return@mapNotNull null
            TouchTodoListRef(id, cleanName(state.optJSONObject("attributes")?.optString("friendly_name"), id))
        }.sortedBy { it.name.lowercase(Locale.ROOT) }.take(MAX_LISTS)
    }.getOrDefault(emptyList())

    /**
     * Open items per requested list from a `todo.get_items` response (REST wraps it in
     * `service_response`). A list missing from the response is reported as null.
     */
    fun parseItems(body: String, entityIds: List<String>, zoneId: ZoneId): Map<String, List<TouchTodoItem>>? = runCatching {
        val root = JSONObject(body)
        val response = root.optJSONObject("service_response") ?: root
        entityIds.associateWith { id ->
            val array = response.optJSONObject(id)?.optJSONArray("items") ?: return@runCatching null
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                if (item.optString("status") == "completed") return@mapNotNull null
                val uid = item.optString("uid").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val summary = item.optString("summary").trim().filter { !it.isISOControl() }.take(MAX_SUMMARY_CHARS)
                    .takeIf(String::isNotBlank) ?: return@mapNotNull null
                TouchTodoItem(id, uid, summary, parseDue(item.optString("due").takeIf(String::isNotBlank), zoneId))
            }.take(MAX_ITEMS_PER_LIST)
        }
    }.getOrNull()

    /** A date (`2026-10-14`) or a date-time with an offset, read as the frame's local day. */
    fun parseDue(value: String?, zoneId: ZoneId): LocalDate? = value?.let {
        runCatching { if (it.length == 10) LocalDate.parse(it) else OffsetDateTime.parse(it).atZoneSameInstant(zoneId).toLocalDate() }.getOrNull()
    }
}

/** Words and order Home uses for to-do items, kept out of the view so they can be tested. */
object TouchTodoPresentation {
    enum class Tone { NONE, PLAIN, TODAY, LATE }
    data class Due(val text: String, val tone: Tone)

    fun due(date: LocalDate?, today: LocalDate, locale: Locale = Locale.getDefault()): Due = when {
        date == null -> Due("", Tone.NONE)
        date.isBefore(today) -> Due("Overdue", Tone.LATE)
        date == today -> Due("Today", Tone.TODAY)
        date == today.plusDays(1) -> Due("Tomorrow", Tone.PLAIN)
        date.isBefore(today.plusDays(7)) -> Due(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), Tone.PLAIN)
        else -> Due(date.format(DateTimeFormatter.ofPattern("MMM d", locale)), Tone.PLAIN)
    }

    /** Dated items first by date (so overdue leads), then undated items in the list's own order. */
    fun ordered(items: List<TouchTodoItem>): List<TouchTodoItem> =
        items.withIndex().sortedWith(compareBy<IndexedValue<TouchTodoItem>>({ it.value.due == null }, { it.value.due }, { it.index })).map { it.value }

    /** "Milk, eggs, coffee and 3 more": the first few names as typed, then a count. */
    fun preview(items: List<TouchTodoItem>, names: Int = 3): String = when {
        items.isEmpty() -> "Nothing on the list"
        items.size == 1 -> items[0].summary
        items.size <= names -> items.dropLast(1).joinToString(", ") { it.summary } + " and " + items.last().summary
        else -> items.take(names).joinToString(", ") { it.summary } + " and ${items.size - names} more"
    }

    fun remaining(count: Int): String = "$count left"
}
