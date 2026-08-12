// Append-only event log on SQLite via node:sqlite (built into Node ≥22.5 — the
// server has zero native dependencies). WAL keeps the log consistent through a
// power cut mid-write; synchronous=FULL fsyncs every commit so a fired ticket
// is on disk before the ack goes out.

import { DatabaseSync } from "node:sqlite";

export function openEventLog(path) {
  const db = new DatabaseSync(path);
  db.exec(`
    CREATE TABLE IF NOT EXISTS event_log (
      seq        INTEGER PRIMARY KEY AUTOINCREMENT,
      command_id TEXT    NOT NULL,
      at         INTEGER NOT NULL,
      payload    TEXT    NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_event_log_command ON event_log(command_id);
  `);
  db.exec("PRAGMA journal_mode=WAL");
  db.exec("PRAGMA synchronous=FULL");

  const insertStmt = db.prepare("INSERT INTO event_log(command_id, at, payload) VALUES (?, ?, ?)");
  const sinceStmt = db.prepare("SELECT seq, payload FROM event_log WHERE seq > ? ORDER BY seq");
  const seqStmt = db.prepare("SELECT COALESCE(MAX(seq), 0) AS seq FROM event_log");
  const cmdIdsStmt = db.prepare("SELECT DISTINCT command_id FROM event_log");

  return {
    /** Appends all events of one command atomically; returns [{seq, event}]. */
    append(commandId, events) {
      db.exec("BEGIN IMMEDIATE");
      try {
        const envelopes = events.map((event) => {
          const { lastInsertRowid } = insertStmt.run(commandId, event.at, JSON.stringify(event));
          return { seq: Number(lastInsertRowid), event };
        });
        db.exec("COMMIT");
        return envelopes;
      } catch (err) {
        db.exec("ROLLBACK");
        throw err;
      }
    },

    /** Events strictly after `afterSeq`, in order — the sync catch-up query. */
    eventsSince(afterSeq) {
      return sinceStmt.all(afterSeq).map((row) => ({ seq: Number(row.seq), event: JSON.parse(row.payload) }));
    },

    currentSeq() {
      return Number(seqStmt.get().seq);
    },

    appliedCommandIds() {
      return cmdIdsStmt.all().map((row) => row.command_id);
    },

    close() {
      db.close();
    },
  };
}
