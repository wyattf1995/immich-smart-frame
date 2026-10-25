package com.wyattfleming.touchframe.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class TouchTodoTest {
    private val pacific = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 14)
    private fun item(summary: String, due: LocalDate? = null, list: String = "todo.household") = TouchTodoItem(list, summary, summary, due)

    @Test
    fun `get_items keeps open items with their due day and drops completed or malformed ones`() {
        val body = """{"changed_states":[],"service_response":{"todo.household":{"items":[
            {"summary":"Pay the water bill","uid":"a1","status":"needs_action","due":"2026-10-14"},
            {"summary":"  Book the furnace tune-up ","uid":"a2","status":"needs_action","due":"2026-10-16T02:30:00+00:00"},
            {"summary":"Already done","uid":"a3","status":"completed"},
            {"summary":"   ","uid":"a4","status":"needs_action"},
            {"summary":"No id","status":"needs_action"},
            {"summary":"Replace the porch light","uid":"a5","status":"needs_action"}
        ]}}}"""
        val items = TouchTodoParser.parseItems(body, listOf("todo.household"), pacific)!!.getValue("todo.household")
        assertEquals(listOf("Pay the water bill", "Book the furnace tune-up", "Replace the porch light"), items.map { it.summary })
        assertEquals(LocalDate.of(2026, 10, 14), items[0].due)
        // 02:30 UTC on the 16th is the evening of the 15th on the frame.
        assertEquals(LocalDate.of(2026, 10, 15), items[1].due)
        assertNull(items[2].due)
    }

    @Test
    fun `a chosen list missing from the response is reported instead of shown empty`() {
        val body = """{"service_response":{"todo.household":{"items":[]}}}"""
        assertNull(TouchTodoParser.parseItems(body, listOf("todo.household", "todo.groceries"), pacific))
        assertNull(TouchTodoParser.parseItems("not json", listOf("todo.household"), pacific))
        assertEquals(emptyList<TouchTodoItem>(), TouchTodoParser.parseItems(body, listOf("todo.household"), pacific)!!.getValue("todo.household"))
    }

    @Test
    fun `list discovery returns only available todo entities by name`() {
        val body = """[
            {"entity_id":"todo.groceries","state":"3","attributes":{"friendly_name":"Groceries"}},
            {"entity_id":"climate.hall","state":"heat","attributes":{"friendly_name":"Hall"}},
            {"entity_id":"todo.household","state":"5","attributes":{"friendly_name":"Household"}},
            {"entity_id":"todo.gone","state":"unavailable","attributes":{"friendly_name":"Gone"}},
            {"entity_id":"todo.Bad-Id","state":"1","attributes":{}},
            {"entity_id":"todo.inbox","state":"0","attributes":{}}
        ]"""
        assertEquals(
            listOf(TouchTodoListRef("todo.groceries", "Groceries"), TouchTodoListRef("todo.household", "Household"), TouchTodoListRef("todo.inbox", "Inbox")),
            TouchTodoParser.parseLists(body),
        )
        assertEquals(emptyList<TouchTodoListRef>(), TouchTodoParser.parseLists("{}"))
    }

    @Test
    fun `nothing is shown until lists are chosen and a saved choice survives a restart`() {
        assertFalse(TouchTodoSelection.decode(null).chosen)
        assertFalse(TouchTodoSelection.decode("garbage").chosen)
        val chosen = TouchTodoSelection.of(listOf(TouchTodoListRef("todo.household", "Household")), TouchTodoListRef("todo.groceries", "Groceries"))
        assertTrue(chosen.chosen)
        assertEquals(chosen, TouchTodoSelection.decode(chosen.encode()))
        assertEquals(listOf("todo.household", "todo.groceries"), chosen.entityIds)
    }

    @Test
    fun `a list chosen as groceries is never also a task list`() {
        val groceries = TouchTodoListRef("todo.groceries", "Groceries")
        val selection = TouchTodoSelection.of(listOf(groceries, TouchTodoListRef("todo.household", "Household"), TouchTodoListRef("not.a.list", "X")), groceries)
        assertEquals(listOf("todo.household"), selection.taskLists.map { it.entityId })
        assertEquals(groceries, selection.groceries)
    }

    @Test
    fun `overdue items are labelled Overdue and today's items Today`() {
        assertEquals(TouchTodoPresentation.Due("Overdue", TouchTodoPresentation.Tone.LATE), TouchTodoPresentation.due(today.minusDays(2), today, Locale.US))
        assertEquals(TouchTodoPresentation.Due("Today", TouchTodoPresentation.Tone.TODAY), TouchTodoPresentation.due(today, today, Locale.US))
        assertEquals("Tomorrow", TouchTodoPresentation.due(today.plusDays(1), today, Locale.US).text)
        assertEquals("Fri", TouchTodoPresentation.due(today.plusDays(2), today, Locale.US).text)
        assertEquals("Oct 28", TouchTodoPresentation.due(today.plusDays(14), today, Locale.US).text)
        assertEquals(TouchTodoPresentation.Tone.NONE, TouchTodoPresentation.due(null, today, Locale.US).tone)
    }

    @Test
    fun `dated items lead by date and undated items keep the list's order`() {
        val ordered = TouchTodoPresentation.ordered(listOf(item("Porch light"), item("Furnace", today.plusDays(2)), item("Library", today.minusDays(1)),
            item("Dry cleaning"), item("Water bill", today)))
        assertEquals(listOf("Library", "Water bill", "Furnace", "Porch light", "Dry cleaning"), ordered.map { it.summary })
    }

    @Test
    fun `the groceries line names the first items and counts the rest`() {
        assertEquals("Nothing on the list", TouchTodoPresentation.preview(emptyList()))
        assertEquals("Milk", TouchTodoPresentation.preview(listOf(item("Milk"))))
        assertEquals("Milk, Eggs and Coffee", TouchTodoPresentation.preview(listOf(item("Milk"), item("Eggs"), item("Coffee"))))
        assertEquals("Milk, Eggs, Coffee and 3 more", TouchTodoPresentation.preview(listOf("Milk", "Eggs", "Coffee", "Bananas", "Tortillas", "Apples").map(::item)))
    }
}
