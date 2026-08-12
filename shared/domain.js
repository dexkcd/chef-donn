// Chef Donn's domain: entities, events, and the pure `reduce` fold.
//
// The hub (app server) is the only writer; every app's state is a fold of the
// event log via `reduce`. This module is plain ESM with no dependencies so the
// exact same code runs in Node on the server and in the browser PWAs — a synced
// client and the hub can never disagree.
//
// Wire format matches the Kotlin apps (kotlinx.serialization with
// classDiscriminator "type"), so Android clients and these PWAs can share a hub.

/** QUEUED → COOKING → READY → SERVED, forward-only (jumps allowed, e.g. a direct
 *  bump QUEUED → READY). VOIDED is terminal and reachable from any pre-SERVED state. */
export const LINE_STATUSES = ["QUEUED", "COOKING", "READY", "SERVED", "VOIDED"];

export const ROLES = ["WAITER", "KITCHEN", "CASHIER", "HUB"];

const rank = (status) => LINE_STATUSES.indexOf(status);

/** Forward-only, jumps allowed; VOIDED and SERVED are terminal. */
export function canAdvanceTo(from, to) {
  return (
    from !== "VOIDED" && from !== "SERVED" &&
    to !== "VOIDED" && rank(to) > rank(from)
  );
}

/** Void allowed any time before the plate reaches the guest. */
export function canBeVoided(status) {
  return status !== "SERVED" && status !== "VOIDED";
}

/** state shape: { menu: MenuItem[], tables: TableInfo[], orders: {[orderId]: Order} } */
export function emptyState() {
  return { menu: [], tables: [], orders: {} };
}

/**
 * Immutable snapshot of what was ordered: name/price are copied from the menu at
 * order time so later menu edits never change a fired ticket or a running tab.
 * Money is priceCents (centavos, integer) — never floating-point pesos.
 */
export function lineSpec({ lineId, menuItemId, name, priceCents, qty, station, modifiers = [], note = "", course = 1 }) {
  return { lineId, menuItemId, name, priceCents, qty, station, modifiers, note, course };
}

export function lineAmountCents(line) {
  return line.status === "VOIDED" ? 0 : line.spec.priceCents * line.spec.qty;
}

export function orderTotalCents(order) {
  return order.lines.reduce((sum, line) => sum + lineAmountCents(line), 0);
}

export function openOrderForTable(state, tableId) {
  return Object.values(state.orders).find((o) => o.tableId === tableId && !o.billed) ?? null;
}

export function getLine(state, orderId, lineId) {
  return state.orders[orderId]?.lines.find((l) => l.spec.lineId === lineId) ?? null;
}

function updateOrder(state, orderId, f) {
  const order = state.orders[orderId];
  if (!order) return state;
  return { ...state, orders: { ...state.orders, [orderId]: f(order) } };
}

/** Pure fold. Must stay deterministic: hub and every client run the same function. */
export function reduce(state, event) {
  switch (event.type) {
    case "OrderOpened":
      return {
        ...state,
        orders: {
          ...state.orders,
          [event.orderId]: {
            orderId: event.orderId,
            tableId: event.tableId,
            covers: event.covers,
            openedBy: event.openedBy,
            openedAt: event.at,
            lines: [],
            billed: false,
            billedAt: null,
          },
        },
      };

    case "LinesAdded":
      return updateOrder(state, event.orderId, (order) => {
        const existing = new Set(order.lines.map((l) => l.spec.lineId));
        const newLines = event.lines
          .filter((spec) => !existing.has(spec.lineId))
          .map((spec) => ({ spec, status: "QUEUED", statusAt: event.at, voidReason: null, voidedBy: null }));
        return { ...order, lines: [...order.lines, ...newLines] };
      });

    case "LineStatusSet":
      return updateOrder(state, event.orderId, (order) => ({
        ...order,
        lines: order.lines.map((line) =>
          event.lineIds.includes(line.spec.lineId) && canAdvanceTo(line.status, event.status)
            ? { ...line, status: event.status, statusAt: event.at }
            : line
        ),
      }));

    case "LineVoided":
      return updateOrder(state, event.orderId, (order) => ({
        ...order,
        lines: order.lines.map((line) =>
          line.spec.lineId === event.lineId && canBeVoided(line.status)
            ? { ...line, status: "VOIDED", statusAt: event.at, voidReason: event.reason, voidedBy: event.voidedBy }
            : line
        ),
      }));

    case "OrderBilled":
      return updateOrder(state, event.orderId, (order) => ({ ...order, billed: true, billedAt: event.at }));

    case "MenuUpdated":
      return { ...state, menu: event.items };

    case "TablesUpdated":
      return { ...state, tables: event.tables };

    default:
      // Unknown event from a newer hub version: skip, don't crash the fold.
      return state;
  }
}

export function reduceAll(state, events) {
  return events.reduce(reduce, state);
}

// ── Event constructors (all carry hub wall-clock millis in `at`; each
//    LineStatusSet is also the depletion signal the inventory module consumes) ──

export const orderOpened = (orderId, tableId, covers, openedBy, at) =>
  ({ type: "OrderOpened", orderId, tableId, covers, openedBy, at });
export const linesAdded = (orderId, lines, addedBy, at) =>
  ({ type: "LinesAdded", orderId, lines, addedBy, at });
export const lineStatusSet = (orderId, lineIds, status, setBy, at) =>
  ({ type: "LineStatusSet", orderId, lineIds, status, setBy, at });
export const lineVoided = (orderId, lineId, reason, voidedBy, role, at) =>
  ({ type: "LineVoided", orderId, lineId, reason, voidedBy, role, at });
export const orderBilled = (orderId, billedBy, at) =>
  ({ type: "OrderBilled", orderId, billedBy, at });
export const menuUpdated = (items, at) => ({ type: "MenuUpdated", items, at });
export const tablesUpdated = (tables, at) => ({ type: "TablesUpdated", tables, at });
