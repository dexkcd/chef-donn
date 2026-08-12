// Reducer and decide rules — the invariants the whole system rests on.

import test from "node:test";
import assert from "node:assert/strict";
import {
  emptyState, getLine, lineVoided, linesAdded, lineStatusSet, openOrderForTable,
  orderBilled, orderOpened, orderTotalCents, reduce, reduceAll,
} from "../../shared/domain.js";
import { addLines, billOrder, decide, fireOrder, setLineStatus, voidLine } from "../../shared/decide.js";

const spec = (lineId, name = "Sinigang", priceCents = 32000, qty = 1) =>
  ({ lineId, menuItemId: `m-${name}`, name, priceCents, qty, station: "MAINS", modifiers: [], note: "", course: 1 });

const openedWithLines = (...lines) =>
  reduceAll(emptyState(), [
    orderOpened("o1", "t1", 2, "Ana", 1000),
    linesAdded("o1", lines, "Ana", 1000),
  ]);

test("open order and add lines", () => {
  const state = openedWithLines(spec("l1"), spec("l2", "Halo-Halo", 15000));
  const order = state.orders.o1;
  assert.equal(order.lines.length, 2);
  assert.ok(order.lines.every((l) => l.status === "QUEUED"));
  assert.equal(orderTotalCents(order), 47000);
});

test("duplicate lineIds in LinesAdded are ignored", () => {
  const state = reduce(openedWithLines(spec("l1")), linesAdded("o1", [spec("l1")], "Ana", 2000));
  assert.equal(state.orders.o1.lines.length, 1);
});

test("status advances forward and jumps are allowed", () => {
  const state = reduce(openedWithLines(spec("l1")), lineStatusSet("o1", ["l1"], "READY", "Kusina", 2000));
  assert.equal(getLine(state, "o1", "l1").status, "READY");
});

test("status never moves backwards", () => {
  let state = openedWithLines(spec("l1"));
  state = reduce(state, lineStatusSet("o1", ["l1"], "READY", "Kusina", 2000));
  state = reduce(state, lineStatusSet("o1", ["l1"], "COOKING", "Kusina", 3000));
  assert.equal(getLine(state, "o1", "l1").status, "READY");
});

test("void zeroes the line amount and records the reason", () => {
  let state = openedWithLines(spec("l1"), spec("l2"));
  state = reduce(state, lineVoided("o1", "l1", "Out of stock", "Kusina", "KITCHEN", 2000));
  assert.equal(getLine(state, "o1", "l1").status, "VOIDED");
  assert.equal(getLine(state, "o1", "l1").voidReason, "Out of stock");
  assert.equal(orderTotalCents(state.orders.o1), 32000);
});

test("bump after void does not resurrect the line", () => {
  let state = openedWithLines(spec("l1"));
  state = reduce(state, lineVoided("o1", "l1", "86'd", "Ana", "WAITER", 2000));
  state = reduce(state, lineStatusSet("o1", ["l1"], "READY", "Kusina", 3000));
  assert.equal(getLine(state, "o1", "l1").status, "VOIDED");
});

test("served line cannot be voided by reduce", () => {
  let state = openedWithLines(spec("l1"));
  state = reduce(state, lineStatusSet("o1", ["l1"], "SERVED", "Ana", 2000));
  state = reduce(state, lineVoided("o1", "l1", "too late", "Ana", "WAITER", 3000));
  assert.equal(getLine(state, "o1", "l1").status, "SERVED");
});

test("billing closes the table's open order", () => {
  let state = openedWithLines(spec("l1"));
  assert.equal(openOrderForTable(state, "t1").orderId, "o1");
  state = reduce(state, orderBilled("o1", "Cashier", 5000));
  assert.equal(openOrderForTable(state, "t1"), null);
  assert.equal(state.orders.o1.billed, true);
});

test("events on unknown orders are no-ops", () => {
  const state = reduce(emptyState(), lineStatusSet("ghost", ["l1"], "READY", "x", 1000));
  assert.deepEqual(state.orders, {});
});

test("round-trips through json", () => {
  const events = [
    orderOpened("o1", "t1", 2, "Ana", 1000),
    linesAdded("o1", [spec("l1")], "Ana", 1000),
    lineVoided("o1", "l1", "spill", "Ana", "WAITER", 2000),
  ];
  assert.deepEqual(events.map((e) => JSON.parse(JSON.stringify(e))), events);
});

// ── decide ──────────────────────────────────────────────────────────────

const now = 9000;
const fire = (orderId = "o1", tableId = "t1", ...lines) =>
  fireOrder({ commandId: `c-${orderId}`, deviceId: "dev1", actor: "Ana", orderId, tableId, covers: 2, lines });

test("fire on a free table opens and adds", () => {
  const d = decide(emptyState(), fire("o1", "t1", spec("l1")), now);
  assert.ok(d.accepted);
  assert.equal(d.events[0].type, "OrderOpened");
  assert.equal(d.events[1].type, "LinesAdded");
});

test("fire on an occupied table merges into the open order", () => {
  const d = decide(openedWithLines(spec("l1")), fire("o2", "t1", spec("l2")), now);
  assert.ok(d.accepted);
  assert.equal(d.events.length, 1);
  assert.equal(d.events[0].type, "LinesAdded");
  assert.equal(d.events[0].orderId, "o1");
});

test("empty fire is rejected", () => {
  assert.equal(decide(emptyState(), fire(), now).accepted, false);
});

test("bump of a voided line is rejected loudly", () => {
  let state = openedWithLines(spec("l1"));
  state = reduce(state, lineVoided("o1", "l1", "86'd", "Ana", "WAITER", 2000));
  const d = decide(state, setLineStatus({
    commandId: "c9", deviceId: "kds1", actor: "Kusina", orderId: "o1", lineIds: ["l1"], status: "READY",
  }), now);
  assert.equal(d.accepted, false);
  assert.match(d.reason, /voided/);
});

test("partial bump applies only to movable lines", () => {
  let state = openedWithLines(spec("l1"), spec("l2"));
  state = reduce(state, lineVoided("o1", "l1", "x", "Ana", "WAITER", 2000));
  const d = decide(state, setLineStatus({
    commandId: "c9", deviceId: "kds1", actor: "Kusina", orderId: "o1", lineIds: ["l1", "l2"], status: "READY",
  }), now);
  assert.ok(d.accepted);
  assert.deepEqual(d.events[0].lineIds, ["l2"]);
});

test("void of a served line is rejected", () => {
  let state = openedWithLines(spec("l1"));
  state = reduce(state, lineStatusSet("o1", ["l1"], "SERVED", "Ana", 2000));
  const d = decide(state, voidLine({
    commandId: "c9", deviceId: "dev1", actor: "Ana", orderId: "o1", lineId: "l1", reason: "no", role: "WAITER",
  }), now);
  assert.equal(d.accepted, false);
});

test("double void is an accepted no-op", () => {
  let state = openedWithLines(spec("l1"));
  state = reduce(state, lineVoided("o1", "l1", "x", "Ana", "WAITER", 2000));
  const d = decide(state, voidLine({
    commandId: "c9", deviceId: "dev1", actor: "Ana", orderId: "o1", lineId: "l1", reason: "x", role: "WAITER",
  }), now);
  assert.ok(d.accepted);
  assert.equal(d.events.length, 0);
});

test("adding lines to a billed order is rejected", () => {
  let state = openedWithLines(spec("l1"));
  state = reduce(state, orderBilled("o1", "Cashier", 3000));
  const d = decide(state, addLines({
    commandId: "c9", deviceId: "dev1", actor: "Ana", orderId: "o1", lines: [spec("l2")],
  }), now);
  assert.equal(d.accepted, false);
});

test("double bill is an accepted no-op", () => {
  let state = openedWithLines(spec("l1"));
  state = reduce(state, orderBilled("o1", "Cashier", 3000));
  const d = decide(state, billOrder({ commandId: "c9", deviceId: "dev1", actor: "Cashier", orderId: "o1" }), now);
  assert.ok(d.accepted);
  assert.equal(d.events.length, 0);
});

test("a status change cannot be used to void", () => {
  const d = decide(openedWithLines(spec("l1")), setLineStatus({
    commandId: "c9", deviceId: "kds1", actor: "Kusina", orderId: "o1", lineIds: ["l1"], status: "VOIDED",
  }), now);
  assert.equal(d.accepted, false);
});
