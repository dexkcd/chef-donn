package ph.chefdonn.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun spec(lineId: String, name: String = "Sinigang", price: Long = 32000, qty: Int = 1) =
    LineSpec(lineId = lineId, menuItemId = "m-$name", name = name, priceCents = price, qty = qty, station = "MAINS")

private fun openedWithLines(vararg lines: LineSpec): AppState = reduceAll(
    AppState(),
    listOf(
        OrderOpened("o1", "t1", 2, "Ana", 1000),
        LinesAdded("o1", lines.toList(), "Ana", 1000),
    ),
)

class ReduceTest {

    @Test
    fun `open order and add lines`() {
        val state = openedWithLines(spec("l1"), spec("l2", "Halo-Halo", 15000))
        val order = state.orders.getValue("o1")
        assertEquals(2, order.lines.size)
        assertTrue(order.lines.all { it.status == LineStatus.QUEUED })
        assertEquals(47000, order.totalCents)
    }

    @Test
    fun `duplicate lineIds in LinesAdded are ignored`() {
        val state = reduce(openedWithLines(spec("l1")), LinesAdded("o1", listOf(spec("l1")), "Ana", 2000))
        assertEquals(1, state.orders.getValue("o1").lines.size)
    }

    @Test
    fun `status advances forward and jumps are allowed`() {
        var state = openedWithLines(spec("l1"))
        state = reduce(state, LineStatusSet("o1", listOf("l1"), LineStatus.READY, "Kusina", 2000))
        assertEquals(LineStatus.READY, state.line("o1", "l1")!!.status)
    }

    @Test
    fun `status never moves backwards`() {
        var state = openedWithLines(spec("l1"))
        state = reduce(state, LineStatusSet("o1", listOf("l1"), LineStatus.READY, "Kusina", 2000))
        state = reduce(state, LineStatusSet("o1", listOf("l1"), LineStatus.COOKING, "Kusina", 3000))
        assertEquals(LineStatus.READY, state.line("o1", "l1")!!.status)
    }

    @Test
    fun `void zeroes the line amount and records reason`() {
        var state = openedWithLines(spec("l1"), spec("l2"))
        state = reduce(state, LineVoided("o1", "l1", "Out of stock", "Kusina", Role.KITCHEN, 2000))
        val order = state.orders.getValue("o1")
        assertEquals(LineStatus.VOIDED, state.line("o1", "l1")!!.status)
        assertEquals("Out of stock", state.line("o1", "l1")!!.voidReason)
        assertEquals(32000, order.totalCents)
    }

    @Test
    fun `bump after void does not resurrect the line`() {
        var state = openedWithLines(spec("l1"))
        state = reduce(state, LineVoided("o1", "l1", "86'd", "Ana", Role.WAITER, 2000))
        state = reduce(state, LineStatusSet("o1", listOf("l1"), LineStatus.READY, "Kusina", 3000))
        assertEquals(LineStatus.VOIDED, state.line("o1", "l1")!!.status)
    }

    @Test
    fun `served line cannot be voided by reduce`() {
        var state = openedWithLines(spec("l1"))
        state = reduce(state, LineStatusSet("o1", listOf("l1"), LineStatus.SERVED, "Ana", 2000))
        state = reduce(state, LineVoided("o1", "l1", "too late", "Ana", Role.WAITER, 3000))
        assertEquals(LineStatus.SERVED, state.line("o1", "l1")!!.status)
    }

    @Test
    fun `billing closes the table's open order`() {
        var state = openedWithLines(spec("l1"))
        assertEquals("o1", state.openOrderForTable("t1")!!.orderId)
        state = reduce(state, OrderBilled("o1", "Cashier", 5000))
        assertNull(state.openOrderForTable("t1"))
        assertTrue(state.orders.getValue("o1").billed)
    }

    @Test
    fun `events on unknown orders are no-ops`() {
        val state = reduce(AppState(), LineStatusSet("ghost", listOf("l1"), LineStatus.READY, "x", 1000))
        assertTrue(state.orders.isEmpty())
    }

    @Test
    fun `round-trips through json`() {
        val events: List<DomainEvent> = listOf(
            OrderOpened("o1", "t1", 2, "Ana", 1000),
            LinesAdded("o1", listOf(spec("l1")), "Ana", 1000),
            LineVoided("o1", "l1", "spill", "Ana", Role.WAITER, 2000),
        )
        val json = events.map { DomainJson.encodeToString(DomainEvent.serializer(), it) }
        val back = json.map { DomainJson.decodeFromString(DomainEvent.serializer(), it) }
        assertEquals(events, back)
    }
}

class DecideTest {

    private val now = 9000L

    private fun fire(orderId: String = "o1", tableId: String = "t1", vararg lines: LineSpec) =
        FireOrder("c-$orderId", "dev1", "Ana", orderId, tableId, 2, lines.toList())

    @Test
    fun `fire on a free table opens and adds`() {
        val d = decide(AppState(), fire(lines = arrayOf(spec("l1"))), now)
        val accepted = assertIs<Decision.Accepted>(d)
        assertIs<OrderOpened>(accepted.events[0])
        assertIs<LinesAdded>(accepted.events[1])
    }

    @Test
    fun `fire on an occupied table merges into the open order`() {
        val state = openedWithLines(spec("l1"))
        val d = decide(state, fire(orderId = "o2", lines = arrayOf(spec("l2"))), now)
        val accepted = assertIs<Decision.Accepted>(d)
        val added = assertIs<LinesAdded>(accepted.events.single())
        assertEquals("o1", added.orderId)
    }

    @Test
    fun `empty fire is rejected`() {
        assertIs<Decision.Rejected>(decide(AppState(), fire(), now))
    }

    @Test
    fun `bump of a voided line is rejected loudly`() {
        var state = openedWithLines(spec("l1"))
        state = reduce(state, LineVoided("o1", "l1", "86'd", "Ana", Role.WAITER, 2000))
        val d = decide(state, SetLineStatus("c9", "kds1", "Kusina", "o1", listOf("l1"), LineStatus.READY), now)
        val rejected = assertIs<Decision.Rejected>(d)
        assertTrue(rejected.reason.contains("voided"))
    }

    @Test
    fun `partial bump applies only to movable lines`() {
        var state = openedWithLines(spec("l1"), spec("l2"))
        state = reduce(state, LineVoided("o1", "l1", "x", "Ana", Role.WAITER, 2000))
        val d = decide(state, SetLineStatus("c9", "kds1", "Kusina", "o1", listOf("l1", "l2"), LineStatus.READY), now)
        val accepted = assertIs<Decision.Accepted>(d)
        val set = assertIs<LineStatusSet>(accepted.events.single())
        assertEquals(listOf("l2"), set.lineIds)
    }

    @Test
    fun `void of served line is rejected`() {
        var state = openedWithLines(spec("l1"))
        state = reduce(state, LineStatusSet("o1", listOf("l1"), LineStatus.SERVED, "Ana", 2000))
        val d = decide(state, VoidLine("c9", "dev1", "Ana", "o1", "l1", "no", Role.WAITER), now)
        assertIs<Decision.Rejected>(d)
    }

    @Test
    fun `double void is an accepted no-op`() {
        var state = openedWithLines(spec("l1"))
        state = reduce(state, LineVoided("o1", "l1", "x", "Ana", Role.WAITER, 2000))
        val d = decide(state, VoidLine("c9", "dev1", "Ana", "o1", "l1", "x", Role.WAITER), now)
        val accepted = assertIs<Decision.Accepted>(d)
        assertTrue(accepted.events.isEmpty())
    }

    @Test
    fun `add lines to billed order is rejected`() {
        var state = openedWithLines(spec("l1"))
        state = reduce(state, OrderBilled("o1", "Cashier", 3000))
        val d = decide(state, AddLines("c9", "dev1", "Ana", "o1", listOf(spec("l2"))), now)
        assertIs<Decision.Rejected>(d)
    }

    @Test
    fun `double bill is an accepted no-op`() {
        var state = openedWithLines(spec("l1"))
        state = reduce(state, OrderBilled("o1", "Cashier", 3000))
        val d = decide(state, BillOrder("c9", "dev1", "Cashier", "o1"), now)
        val accepted = assertIs<Decision.Accepted>(d)
        assertTrue(accepted.events.isEmpty())
        assertFalse(accepted.events.any { it is OrderBilled })
    }
}
