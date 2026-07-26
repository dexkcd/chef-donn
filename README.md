# Chef Donn's

Order-ticketing for Philippine restaurants that already have a POS but run everything
before it on paper. Three apps, one local hub, zero changes to the existing POS:
**add tablets, remove paper.**

- **Waiter app** (phone) — take orders table-side, fire to kitchen, live per-item status
- **Kitchen app / KDS** (tablet, dark) — station tickets, bump, timers
- **Cashier app** (tablet, read-only) — live running tab per table, one action: Mark Billed
- **Hub** (cheap Android box) — the source of truth: append-only order stream over local WiFi

No payments, no receipts, no BIR CAS, no cloud during service.

## Architecture

Event-sourced: clients send commands, the hub validates (`decide`), appends events to a
SQLite log (durable before ack), and broadcasts over WebSockets. Every app folds the
same `reduce` function over the stream, so state can never diverge. Offline devices
queue commands (idempotent by UUID) and reconcile on rejoin; the hub replays its log
on reboot. Void-vs-bump conflicts resolve deterministically: void wins, the bump is
rejected loudly.

## Modules

| Module | What it is |
| --- | --- |
| `core/domain` | Pure Kotlin: entities, events, commands, `reduce`, `decide` |
| `core/protocol` | WebSocket wire messages (Hello / Submit / Welcome / Events / Ack) |
| `core/hubserver` | Ktor server + SQLDelight event log (JVM-testable, Android-embeddable) |
| `core/client` | Client sync engine: pending queue, catch-up, reconnect |
| `core/designsystem` | Compose theme + components from the Chef Donn's design system |
| `hub/` | Android hub app: foreground service, NSD advertising, admin screen |
| `app/` | Single staff APK: role picked at setup → Waiter / Kitchen / Cashier UI |

## Build

Requires JDK 17 and the Android SDK (platform 34).

```bash
./gradlew test                    # domain + hub server tests (JVM, no device)
./gradlew :hub:assembleDebug :app:assembleDebug
```

Install the hub APK on one device, the staff APK on the rest; staff devices discover
the hub over WiFi (mDNS) or by typed IP. The hub seeds a sample menu and tables T1–T10
on first boot.
