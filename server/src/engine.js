// The single authority over the order stream: decide → append to SQLite
// (durable before ack) → fold into state → broadcast. Node is single-threaded
// and submit() is fully synchronous, so writes are naturally serialized — the
// Kotlin engine's mutex has no equivalent here by construction.
// On construction it replays the whole log, so a hub reboot loses nothing.

import { emptyState, reduce } from "../../shared/domain.js";
import { decide } from "../../shared/decide.js";
import { ack } from "../../shared/protocol.js";

export class HubEngine {
  #log;
  #clock;
  #state;
  #appliedCommandIds;
  #listeners = new Set();

  constructor(log, clock = Date.now) {
    this.#log = log;
    this.#clock = clock;
    let state = emptyState();
    for (const { event } of log.eventsSince(0)) state = reduce(state, event);
    this.#state = state;
    this.#appliedCommandIds = new Set(log.appliedCommandIds());
  }

  get state() {
    return this.#state;
  }

  currentSeq() {
    return this.#log.currentSeq();
  }

  eventsSince(afterSeq) {
    return this.#log.eventsSince(afterSeq);
  }

  /** Subscribe to live envelopes; returns an unsubscribe function. */
  onEvent(listener) {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  /** Validates, persists, folds, broadcasts. Returns the Ack for the sender. */
  submit(cmd) {
    if (this.#appliedCommandIds.has(cmd.commandId)) {
      // Replayed from an offline queue after the original got through — idempotent.
      return ack(cmd.commandId, true);
    }
    const decision = decide(this.#state, cmd, this.#clock());
    if (!decision.accepted) return ack(cmd.commandId, false, decision.reason);

    const envelopes = this.#log.append(cmd.commandId, decision.events);
    for (const { event } of envelopes) this.#state = reduce(this.#state, event);
    this.#appliedCommandIds.add(cmd.commandId);
    for (const envelope of envelopes) {
      for (const listener of this.#listeners) listener(envelope);
    }
    return ack(cmd.commandId, true);
  }
}
