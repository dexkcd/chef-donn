// Engine tests: durability, idempotency, and the void-beats-bump rule at the
// authority boundary.

import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { openEventLog } from "../src/db.js";
import { HubEngine } from "../src/engine.js";
import { addLines, billOrder, fireOrder, setLineStatus, voidLine } from "../../shared/decide.js";
import { getLine } from "../../shared/domain.js";

const testSpec = (lineId = randomUUID(), name = "Adobo") =>
  ({ lineId, menuItemId: "m1", name, priceCents: 25000, qty: 1, station: "MAINS", modifiers: [], note: "", course: 1 });

const memEngine = () => new HubEngine(openEventLog(":memory:"));
const fileEngine = (dbPath) => new HubEngine(openEventLog(dbPath));
const tempDbPath = () => path.join(mkdtempSync(path.join(tmpdir(), "hub-")), "hub.db");

const fire = (orderId, tableId = "t1", ...lines) =>
  fireOrder({ commandId: randomUUID(), deviceId: "dev1", actor: "Ana", orderId, tableId, covers: 2, lines });

test("fire, bump, bill happy path", () => {
  const engine = memEngine();
  const line = testSpec();
  assert.ok(engine.submit(fire("o1", "t1", line)).accepted);
  assert.ok(engine.submit(setLineStatus({
    commandId: "c2", deviceId: "kds", actor: "Kusina", orderId: "o1", lineIds: [line.lineId], status: "READY",
  })).accepted);
  assert.ok(engine.submit(billOrder({ commandId: "c3", deviceId: "cash", actor: "Cashier", orderId: "o1" })).accepted);

  const order = engine.state.orders.o1;
  assert.equal(order.lines[0].status, "READY");
  assert.equal(order.billed, true);
  // OrderOpened, LinesAdded, LineStatusSet, OrderBilled
  assert.equal(engine.currentSeq(), 4);
});

test("duplicate command is applied once", () => {
  const engine = memEngine();
  const cmd = fire("o1", "t1", testSpec());
  assert.ok(engine.submit(cmd).accepted);
  assert.ok(engine.submit(cmd).accepted);
  assert.equal(Object.keys(engine.state.orders).length, 1);
  assert.equal(engine.state.orders.o1.lines.length, 1);
});

test("state survives an engine restart from the same file", () => {
  const dbPath = tempDbPath();
  const line = testSpec();
  const first = fire("o1", "t1", line);
  {
    const engine = fileEngine(dbPath);
    engine.submit(first);
    engine.submit(voidLine({
      commandId: "c2", deviceId: "dev1", actor: "Ana", orderId: "o1",
      lineId: line.lineId, reason: "Out of stock", role: "WAITER",
    }));
  }
  const reborn = fileEngine(dbPath);
  const revived = getLine(reborn.state, "o1", line.lineId);
  assert.equal(revived.status, "VOIDED");
  assert.equal(revived.voidReason, "Out of stock");

  // The idempotency set survives too: replaying the original is a no-op.
  const seqBefore = reborn.currentSeq();
  assert.ok(reborn.submit(first).accepted);
  assert.equal(reborn.currentSeq(), seqBefore);
});

test("void beats a concurrent bump and the bump is rejected loudly", () => {
  const engine = memEngine();
  const line = testSpec();
  engine.submit(fire("o1", "t1", line));
  assert.ok(engine.submit(voidLine({
    commandId: "c2", deviceId: "w1", actor: "Ana", orderId: "o1",
    lineId: line.lineId, reason: "86'd", role: "KITCHEN",
  })).accepted);

  const bump = engine.submit(setLineStatus({
    commandId: "c3", deviceId: "kds", actor: "Kusina", orderId: "o1", lineIds: [line.lineId], status: "READY",
  }));
  assert.equal(bump.accepted, false);
  assert.match(bump.reason, /voided/);
  assert.equal(getLine(engine.state, "o1", line.lineId).status, "VOIDED");
});

test("second fire on the same table merges into the open order", () => {
  const engine = memEngine();
  engine.submit(fire("o1", "t1", testSpec()));
  engine.submit(fire("o2", "t1", testSpec(randomUUID(), "Lumpia")));
  assert.equal(Object.keys(engine.state.orders).length, 1);
  assert.equal(engine.state.orders.o1.lines.length, 2);
});

test("adding lines to a billed order is rejected", () => {
  const engine = memEngine();
  engine.submit(fire("o1", "t1", testSpec()));
  engine.submit(billOrder({ commandId: "c2", deviceId: "cash", actor: "Cashier", orderId: "o1" }));
  const ack = engine.submit(addLines({
    commandId: "c3", deviceId: "dev1", actor: "Ana", orderId: "o1", lines: [testSpec()],
  }));
  assert.equal(ack.accepted, false);
  assert.match(ack.reason, /billed/);
});

test("rejected commands append nothing to the log", () => {
  const engine = memEngine();
  const before = engine.currentSeq();
  const ack = engine.submit(fire("o1", "t1")); // no lines
  assert.equal(ack.accepted, false);
  assert.equal(engine.currentSeq(), before);
});

test("eventsSince returns the catch-up slice in order", () => {
  const engine = memEngine();
  const line = testSpec();
  engine.submit(fire("o1", "t1", line));
  engine.submit(setLineStatus({
    commandId: "c2", deviceId: "kds", actor: "Kusina", orderId: "o1", lineIds: [line.lineId], status: "COOKING",
  }));
  const all = engine.eventsSince(0);
  assert.deepEqual(all.map((e) => e.seq), [1, 2, 3]);
  assert.deepEqual(engine.eventsSince(2).map((e) => e.event.type), ["LineStatusSet"]);
});

test("subscribers receive every appended envelope", () => {
  const engine = memEngine();
  const seen = [];
  engine.onEvent((envelope) => seen.push(envelope));
  engine.submit(fire("o1", "t1", testSpec()));
  assert.deepEqual(seen.map((e) => e.event.type), ["OrderOpened", "LinesAdded"]);
  assert.deepEqual(seen.map((e) => e.seq), [1, 2]);
});
