# Chef Donn's — agent notes

Three-app restaurant ticketing (waiter / kitchen KDS / cashier) + Android hub.
Product spec v2 rules: cashier is read-only + Mark Billed only; NO payments,
discounts, split bills, receipts, BIR features, or cloud-during-service. Do not add
POS features — they are explicitly out of scope until Module 3.

## Toolchain (user-local, no sudo)

- JDK 17: `~/.local/jdk17` — every Gradle call needs `JAVA_HOME=~/.local/jdk17`
- Android SDK: `~/Android/Sdk` (platform 34, build-tools 34) via `local.properties`
- Build: `JAVA_HOME=~/.local/jdk17 ./gradlew test :hub:assembleDebug :app:assembleDebug`

## Architecture invariants

- Hub is the single authority. All state = fold of `reduce(state, event)` over the
  SQLite event log (`core/hubserver`). Clients run the *same* `reduce`.
- Command validation lives only in `decide()` (`core/domain/Commands.kt`).
  Deterministic; void beats bump; rejections are surfaced (Ack.reason), never silent.
- Commands/orders/lines carry client-generated UUIDs; the hub dedupes by commandId
  (idempotent replay after offline queueing). Never reuse or regenerate ids on retry.
- Money is `priceCents: Long` (centavos). Names/prices are snapshotted into `LineSpec`
  at order time — menu edits must not change fired tickets.
- Line status: QUEUED → COOKING → READY → SERVED forward-only (jumps ok);
  VOIDED terminal, only pre-SERVED.

## Design system

Source of truth: Claude Design project `4f624b38-8da4-471f-b3d2-387da1d795bf`
("Chef Donn's Design System") — fetch via DesignSync. Compose translation lives in
`core/designsystem`. Rules: Day scheme for waiter/cashier, Night for kitchen (by role,
not system theme); status = glyph + color + label, never color alone; tap targets
≥56dp (default 72dp); thick borders over shadows; no emoji in staff apps; Archivo for
display/buttons, Barlow body, Barlow Condensed labels, IBM Plex Mono for all numerics.

## Testing

- `./gradlew :core:domain:test` — reducer/decide rules
- `./gradlew :core:hubserver:test` — includes real-socket integration tests
  (3 role clients, void-beats-bump, hub restart replay, offline queue drain)
- Integration test fns need `(): Unit = runBlocking` (JUnit4 void check)
