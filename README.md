# ledger-relay

A library for Java/Spring applications that keep business data in PostgreSQL and record money movements in TigerBeetle (TB). It accepts ledger commands in **the same PG transaction** as the business data and submits them to TB in order, **per ordering key, based on the ledger result**. It classifies each result into a business state and records it atomically together with the application's follow-up work.

PG and TB cannot share one transaction. TB replies can be lost (the client has no timeout), an id that ended in a transient failure cannot be used again, and a post that arrives before its reservation uses up the post's id. A regular outbox orders messages by "delivered successfully". This module decides from the "ledger result" whether to send the next command, wait, or skip it.

> Status: in development (before v0.1). The public API may still change.

## How this was built

The code, tests and documentation in this repository were **written by Claude Code (Anthropic's AI coding tool).** I (Donghyun Lee) decided what to build and the design direction, approved the design and the implementation plan at each stage, and reviewed the results. The `Co-Authored-By: Claude` line on each commit is the record of this.

Each stage followed the same steps.

1. Write the design document and approve it.
2. Write the implementation plan and approve it.
3. Write the tests first, confirm that they fail, then implement (TDD).
4. A fresh reviewer reviews the whole branch, and the defects found are fixed together with a reproduction test.

For every change that prevents a failure, the reproduction test and the control experiment result are recorded in `docs/failure-modes.md`. The control experiment checks that the test really fails when the fix is reverted.

## Try it with the sample shop

You only need Docker (no JDK, no Node). Give Docker at least 3 GB of memory (TigerBeetle alone uses about 1.5 GB). From the repository root:

```bash
docker compose up --build        # the first run takes a few minutes to build the images
```

Open <http://localhost:8090> in a browser (to change the port: `SHOP_PORT=9000 docker compose up --build`). To shut it down: `docker compose down`. The demo starts from an empty DB and an empty ledger each time (if you stop it with Ctrl-C and run `up` again, it continues where it was).

It runs PostgreSQL, TigerBeetle, Toxiproxy between them (the cut-off switch), and two apps from the same image (`shop-a` serves the UI and runs a worker, `shop-b` runs a worker). Every number on the screen comes from the real state of PG and TB. The currency is the Thai baht (฿), and payment works like a card: **authorize (reserve) → capture on shipping (post)**.

| Scene | What you do | What you see |
|---|---|---|
| ① Normal payment | `Pay` in Store → `Ship` on the order | `authorized` (the buyer's money is held) → `paid` (moved to the merchant). The timeline on the right shows the time of each step, the transfer id, the number of sends to TB, and the balances |
| ② TB outage | In Scenarios, turn the TigerBeetle switch off, pay a few times → turn it back on | Payments are accepted at once, the backlog grows and the TB dot turns red. After you turn it on, the backlog empties within a few seconds and Consistency below stays green |
| ③ Halt and operator | `Inject bad post` in Scenarios → `Abandon` in Operator | Only that order's post halts; other orders keep flowing. Abandoning it is followed by a void, the order becomes `cancelled`, and the balances return to where they were |
| ④ Idempotency | `Double click` in Scenarios | Two requests with the same Idempotency-Key → one order (201 and 200), one reservation |
| ⑤ Cancel | `Cancel` on an authorized order | The reservation is voided and the held money is released |

`Burst ×50` places 50 orders at once. The Workers panel in Store shows the two instances sharing the sends. The end-to-end test (`./gradlew :examples-shop:composeTest`) runs these scenes as they are against the real `compose.yaml`.

## What works now

| Module | Contents |
|---|---|
| `ledger-relay-core` | Command model and validation, content hash, state transition table and result classification table, ordering gate, services (acceptance, claim, writing the result, halt and abandon), one worker cycle (`RelayWorker`: batched submission, in-flight limit, submit-timeout). Uses only the JDK |
| `ledger-relay-jdbc` | PostgreSQL store (`JdbcCommandStore`), Spring transaction adapter (`JdbcTransactions`), schema `schema.sql`. Joins the app transaction from a DataSource or JPA transaction manager |
| `ledger-relay-tigerbeetle` | Adapter for the TB 0.17.9 Java client (`TigerBeetleLedgerClient`: submission, lookups for reconciliation), time-ordered transfer id generator (`TigerBeetleTransferIds`), Testcontainers fixture |
| `ledger-relay-spring-boot` | Spring Boot 4.0 auto-configuration, worker loop and graceful shutdown, health (UP even when TB is unreachable), Micrometer metrics, operator port bean |

Operator port `LedgerRelayOperator`: list of halted commands (reason and time), abandon, reconciliation between PG and TB.

The sample app `examples/shop` (Gradle project `:examples-shop`) shows how an application uses the library. The library modules do not depend on it.

## Using it in an app (Spring Boot)

1. Add the dependency. It is not on Maven Central yet, so run `./gradlew publishToMavenLocal` in this repository to publish it to the local Maven repository, then use `mavenLocal()` and `io.github.ledgerrelay:ledger-relay-spring-boot:0.1.0-SNAPSHOT` in the app. `examples/shop` shows the wiring.
2. Apply `schema.sql` (`io/github/ledgerrelay/jdbc/schema.sql` in the jdbc jar) with the app's migration tool. The library does not apply the schema automatically.
3. Configuration:

```yaml
ledger-relay:
  tigerbeetle: { cluster-id: 0, addresses: "127.0.0.1:3000" }   # IP:port only (F39). Or a com.tigerbeetle.Client bean
  worker: { concurrency: 4, batch-size: 128, max-in-flight: 1024, lease-duration: 30s, submit-timeout: 5s }
  backoff: { initial: 200ms, max: 30s }
```

4. Register one `LedgerOutcomeHandler` bean that applies the result to the app's data when a command finishes. The handler runs in the same transaction as writing the result. When the handler submits again, **update the app's rows first, then submit** (`docs/development.md`, "Transactions and locks").
5. Submit with `LedgerRelay.submit(...)` inside an app transaction. The command commits or rolls back together with the business data.

6. Metrics turn on by themselves when the app has a Micrometer `MeterRegistry` (for example through actuator): `ledger.commands.finished`, `ledger.command.duration`, `ledger.commands.waiting`, `ledger.commands.oldest.age`, `ledger.submit.latency` and others (`MicrometerWorkerEvents`, `LedgerRelayMeters`). The backlog gauges are refreshed every `ledger-relay.metrics.backlog-interval` (5 s). To receive worker events yourself, register a `WorkerEvents` bean: it is added next to logging and metrics, not substituted for them.

When you run the app in a container, seccomp must allow io_uring, because the TB Java client uses it (F41, `docs/development.md`).

## Not done yet

- Micrometer metrics and dashboards
- Failure experiments that kill a worker in the middle of processing (open failure O2)
- Archiving and cleanup of finished commands
- Publishing to Maven Central

## Documentation

- [docs/design.md](docs/design.md): guarantees (G1–G7), core concepts, module layout
- [docs/failure-modes.md](docs/failure-modes.md): for each prevented failure, what breaks, the reproduction test, the structure, and the verification (control experiment)
- [docs/development.md](docs/development.md): transactions and lock order, operations notes, sample app setup, known issues
- [docs/testing.md](docs/testing.md): rules for writing tests (test tree = table of contents of the spec, given/when/then, asserts named after effects)

## Build and test

Requires JDK 21 (the Gradle toolchain downloads it if missing). The PG and TB tests use Docker (Testcontainers).

```bash
./gradlew test                          # everything except the end-to-end tests
./gradlew :ledger-relay-core:test       # core only, runs without Docker
./gradlew check                         # the above plus the sample app end-to-end tests (builds the images from compose.yaml)
```

The TB container runs with `seccomp=unconfined` (io_uring). The intermittent panic right after startup and its mitigation are described in `docs/failure-modes.md` F26.

## License

[Apache License 2.0](LICENSE)
