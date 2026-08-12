// The client sync engine, shared by all three PWAs.
//
// One long-lived client per device. `submit` never blocks on the network and
// never loses a command: it queues, persists, and the connection loop drains the
// queue whenever the hub is reachable. State is a fold of hub events — the
// identical `reduce` the hub runs, so a synced client and the hub can never
// disagree.
//
// Isomorphic on purpose: browsers and Node ≥22 both have a global WebSocket, so
// the integration tests exercise this exact file rather than a stand-in.

import { emptyState, reduce } from "./domain.js";
import { WS_PATH, hello, submit as submitMsg } from "./protocol.js";

export const SyncState = {
  /** Connected to the hub, stream live. */
  ONLINE: "ONLINE",
  /** Off mesh with actions waiting to sync — the gold banner state. */
  QUEUING: "QUEUING",
  /** Off mesh, nothing queued. */
  OFFLINE: "OFFLINE",
};

/**
 * Where unsynced commands and the sync cursor live. The PWAs pass a
 * localStorage-backed store so a queued void survives a tab crash or an app
 * kill; tests use the in-memory default.
 */
export class MemoryStore {
  #pending = new Map();
  #lastSeen = 0;
  loadPending() { return [...this.#pending.values()]; }
  addPending(cmd) { this.#pending.set(cmd.commandId, cmd); }
  removePending(commandId) { this.#pending.delete(commandId); }
  loadLastSeenSeq() { return this.#lastSeen; }
  saveLastSeenSeq(seq) { this.#lastSeen = seq; }
}

/** Survives an app kill mid-service, which is the whole point of the queue. */
export class LocalStorageStore {
  #key;
  constructor(namespace) { this.#key = `chefdonn:${namespace}`; }

  #read() {
    try {
      return JSON.parse(localStorage.getItem(this.#key)) ?? { pending: [], lastSeenSeq: 0 };
    } catch {
      return { pending: [], lastSeenSeq: 0 };
    }
  }
  #write(data) {
    try {
      localStorage.setItem(this.#key, JSON.stringify(data));
    } catch {
      // Storage full or blocked: the in-memory queue still drains this session.
    }
  }

  loadPending() { return this.#read().pending; }
  addPending(cmd) {
    const data = this.#read();
    if (!data.pending.some((c) => c.commandId === cmd.commandId)) data.pending.push(cmd);
    this.#write(data);
  }
  removePending(commandId) {
    const data = this.#read();
    data.pending = data.pending.filter((c) => c.commandId !== commandId);
    this.#write(data);
  }
  loadLastSeenSeq() { return this.#read().lastSeenSeq; }
  saveLastSeenSeq(seq) {
    const data = this.#read();
    data.lastSeenSeq = seq;
    this.#write(data);
  }
}

export class DonnClient {
  #deviceId;
  #deviceName;
  #role;
  #store;
  #reconnectDelayMs;
  #pending = new Map();
  #socket = null;
  #reconnectTimer = null;
  #closed = false;
  #url = null;
  #listeners = { state: new Set(), sync: new Set(), ack: new Set() };

  /** Authoritative state as of lastSeenSeq (no optimistic overlay). */
  state = emptyState();
  sync = SyncState.OFFLINE;
  lastSeenSeq = 0;

  constructor({ deviceId, deviceName, role, store = new MemoryStore(), reconnectDelayMs = 2000 }) {
    this.#deviceId = deviceId;
    this.#deviceName = deviceName;
    this.#role = role;
    this.#store = store;
    this.#reconnectDelayMs = reconnectDelayMs;

    for (const cmd of store.loadPending()) this.#pending.set(cmd.commandId, cmd);
    this.lastSeenSeq = store.loadLastSeenSeq();
    this.#markDisconnected();
  }

  get deviceId() { return this.#deviceId; }
  get pendingCount() { return this.#pending.size; }

  /** on("state" | "sync" | "ack", fn) → unsubscribe */
  on(event, listener) {
    this.#listeners[event].add(listener);
    return () => this.#listeners[event].delete(listener);
  }

  #emit(event, payload) {
    for (const listener of this.#listeners[event]) listener(payload);
  }

  connect(url) {
    this.#url = url;
    this.#closed = false;
    this.#open();
  }

  #open() {
    if (this.#closed || this.#socket) return;
    let socket;
    try {
      socket = new WebSocket(this.#url);
    } catch {
      this.#scheduleReconnect();
      return;
    }
    this.#socket = socket;

    socket.addEventListener("open", () => {
      socket.send(JSON.stringify(hello(this.#deviceId, this.#deviceName, this.#role, this.lastSeenSeq)));
      this.#setSync(SyncState.ONLINE);
      this.#drain();
    });

    socket.addEventListener("message", (evt) => {
      let msg;
      try {
        msg = JSON.parse(typeof evt.data === "string" ? evt.data : evt.data.toString());
      } catch {
        return;
      }
      this.#onServerMessage(msg);
    });

    const onDown = () => {
      if (this.#socket !== socket) return;
      this.#socket = null;
      this.#markDisconnected();
      this.#scheduleReconnect();
    };
    socket.addEventListener("close", onDown);
    socket.addEventListener("error", onDown);
  }

  #scheduleReconnect() {
    if (this.#closed || this.#reconnectTimer) return;
    this.#reconnectTimer = setTimeout(() => {
      this.#reconnectTimer = null;
      this.#open();
    }, this.#reconnectDelayMs);
    this.#reconnectTimer.unref?.();
  }

  #onServerMessage(msg) {
    switch (msg.type) {
      case "Welcome":
        break;
      case "Events": {
        let state = this.state;
        let changed = false;
        for (const envelope of msg.events) {
          if (envelope.seq > this.lastSeenSeq) {
            state = reduce(state, envelope.event);
            this.lastSeenSeq = envelope.seq;
            changed = true;
          }
        }
        if (changed) {
          this.state = state;
          this.#store.saveLastSeenSeq(this.lastSeenSeq);
          this.#emit("state", state);
        }
        break;
      }
      case "Ack":
        this.#pending.delete(msg.commandId);
        this.#store.removePending(msg.commandId);
        if (this.sync !== SyncState.ONLINE) this.#markDisconnected();
        this.#emit("ack", msg);
        break;
    }
  }

  /** Queue-and-forget. The ack — or the rejection reason — arrives on "ack". */
  submit(command) {
    this.#pending.set(command.commandId, command);
    this.#store.addPending(command);
    if (this.sync !== SyncState.ONLINE) this.#markDisconnected();
    this.#drain();
  }

  #drain() {
    if (this.#socket?.readyState !== 1) return;
    for (const cmd of this.#pending.values()) {
      this.#socket.send(JSON.stringify(submitMsg(cmd)));
    }
  }

  #markDisconnected() {
    this.#setSync(this.#pending.size ? SyncState.QUEUING : SyncState.OFFLINE);
  }

  #setSync(next) {
    if (this.sync === next) return;
    this.sync = next;
    this.#emit("sync", next);
  }

  close() {
    this.#closed = true;
    clearTimeout(this.#reconnectTimer);
    this.#reconnectTimer = null;
    const socket = this.#socket;
    this.#socket = null;
    socket?.close();
    this.#markDisconnected();
  }
}

export const WS_ENDPOINT = WS_PATH;
