// Command validation. `decide` is the hub's single authority point: it validates
// a command against current state and produces the events to append.
// Deterministic — same state + command → same decision. Rejections are surfaced
// (Ack.reason), never silent.
//
// Commands are client intents. Ids (commandId, orderId, lineId) are
// client-generated UUIDs so a command queued offline is replayable and
// idempotent: the hub drops any commandId it has already applied.

import {
  canAdvanceTo,
  getLine,
  linesAdded,
  lineStatusSet,
  lineVoided,
  menuUpdated,
  openOrderForTable,
  orderBilled,
  orderOpened,
  tablesUpdated,
} from "./domain.js";

const accepted = (events) => ({ accepted: true, events });
/** The command could not apply. Never silent: the hub reports this back to the sender. */
const rejected = (reason) => ({ accepted: false, reason });

export function decide(state, cmd, now) {
  switch (cmd.type) {
    case "FireOrder": {
      if (!cmd.lines.length) return rejected("Nothing to fire — the order has no items");
      const open = openOrderForTable(state, cmd.tableId);
      // Deterministic merge: one open order per table. A concurrent FireOrder
      // from another device joins the existing tab instead of forking it.
      if (!open) {
        return accepted([
          orderOpened(cmd.orderId, cmd.tableId, cmd.covers, cmd.actor, now),
          linesAdded(cmd.orderId, cmd.lines, cmd.actor, now),
        ]);
      }
      return accepted([linesAdded(open.orderId, cmd.lines, cmd.actor, now)]);
    }

    case "AddLines": {
      const order = state.orders[cmd.orderId];
      if (!order) return rejected("Order not found");
      if (order.billed) return rejected("Table already billed — open a new order");
      if (!cmd.lines.length) return rejected("No items to add");
      return accepted([linesAdded(cmd.orderId, cmd.lines, cmd.actor, now)]);
    }

    case "SetLineStatus": {
      const order = state.orders[cmd.orderId];
      if (!order) return rejected("Order not found");
      if (cmd.status === "VOIDED") return rejected("Use Void Item, not a status change");
      const movable = order.lines.filter(
        (l) => cmd.lineIds.includes(l.spec.lineId) && canAdvanceTo(l.status, cmd.status)
      );
      if (movable.length) {
        return accepted([
          lineStatusSet(cmd.orderId, movable.map((l) => l.spec.lineId), cmd.status, cmd.actor, now),
        ]);
      }
      // Void beats a concurrent bump — surfaced, never silently dropped.
      const voidedHit = order.lines.some(
        (l) => cmd.lineIds.includes(l.spec.lineId) && l.status === "VOIDED"
      );
      return voidedHit
        ? rejected("Item was voided — status not changed")
        : rejected("Item already past that status");
    }

    case "VoidLine": {
      const line = getLine(state, cmd.orderId, cmd.lineId);
      if (!line) return rejected("Item not found");
      if (line.status === "VOIDED") return accepted([]); // idempotent: already void
      if (line.status === "SERVED") return rejected("Item already served — cannot void");
      return accepted([lineVoided(cmd.orderId, cmd.lineId, cmd.reason, cmd.actor, cmd.role, now)]);
    }

    case "BillOrder": {
      const order = state.orders[cmd.orderId];
      if (!order) return rejected("Order not found");
      if (order.billed) return accepted([]); // idempotent: already billed
      return accepted([orderBilled(cmd.orderId, cmd.actor, now)]);
    }

    case "UpdateMenu":
      return accepted([menuUpdated(cmd.items, now)]);

    case "UpdateTables":
      return accepted([tablesUpdated(cmd.tables, now)]);

    default:
      return rejected(`Unknown command: ${cmd.type}`);
  }
}

// ── Command constructors ──

export const fireOrder = ({ commandId, deviceId, actor, orderId, tableId, covers, lines }) =>
  ({ type: "FireOrder", commandId, deviceId, actor, orderId, tableId, covers, lines });
export const addLines = ({ commandId, deviceId, actor, orderId, lines }) =>
  ({ type: "AddLines", commandId, deviceId, actor, orderId, lines });
export const setLineStatus = ({ commandId, deviceId, actor, orderId, lineIds, status }) =>
  ({ type: "SetLineStatus", commandId, deviceId, actor, orderId, lineIds, status });
export const voidLine = ({ commandId, deviceId, actor, orderId, lineId, reason, role }) =>
  ({ type: "VoidLine", commandId, deviceId, actor, orderId, lineId, reason, role });
export const billOrder = ({ commandId, deviceId, actor, orderId }) =>
  ({ type: "BillOrder", commandId, deviceId, actor, orderId });
export const updateMenu = ({ commandId, deviceId, actor, items }) =>
  ({ type: "UpdateMenu", commandId, deviceId, actor, items });
export const updateTables = ({ commandId, deviceId, actor, tables }) =>
  ({ type: "UpdateTables", commandId, deviceId, actor, tables });
