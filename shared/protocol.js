// WebSocket wire protocol between the app server and the PWAs.
// Same shapes as the Kotlin `core/protocol` module (discriminator "type"), so
// Android clients from the main branch can talk to this hub unchanged.
//
// Client → server:  Hello { deviceId, deviceName, role, lastSeenSeq }
//                   Submit { command }
// Server → client:  Welcome { hubName, currentSeq }
//                   Events { events: [{ seq, event }] }   — catch-up then live,
//                     one ordered stream, no gap between the two
//                   Ack { commandId, accepted, reason? }  — duplicates ack as
//                     accepted (idempotent); reason only set when rejected

export const WS_PATH = "/ws";
export const DEFAULT_HUB_PORT = 8577;

export const hello = (deviceId, deviceName, role, lastSeenSeq) =>
  ({ type: "Hello", deviceId, deviceName, role, lastSeenSeq });
export const submit = (command) => ({ type: "Submit", command });

export const welcome = (hubName, currentSeq) => ({ type: "Welcome", hubName, currentSeq });
export const eventsMsg = (events) => ({ type: "Events", events });
export const ack = (commandId, accepted, reason = null) => ({ type: "Ack", commandId, accepted, reason });

export const encode = JSON.stringify;
export const decode = JSON.parse;
