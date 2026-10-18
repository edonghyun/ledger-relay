# Local development

## Prerequisites

- JDK 21. The Gradle toolchain downloads it if missing. On macOS, use a JDK that matches the CPU (an x86_64 JDK on Apple Silicon does not match the TB client's native library).
- Docker (Testcontainers). At least 3 GB of memory (one TB container uses about 1.5 GB). The same applies to the sample app demo.

```bash
./gradlew test
```

## TigerBeetle test container

- The tag of the image `ghcr.io/tigerbeetle/tigerbeetle` comes from the `tigerbeetle` value in `gradle/libs.versions.toml`. It is the same value as the client jar.
- io_uring requires `seccomp=unconfined`. The fixture applies it automatically.
- It runs in development mode (`--development`), so no memlock setting is needed. This differs from a production setup.
- Only a single replica is used. Multi-replica failover is not tested.

## PostgreSQL test container (ledger-relay-jdbc)

- Image `postgres:18.6`. One container per JVM starts on first use, and `schema.sql` is applied once (`PostgresStorage`).
- One connection pool (HikariCP) is created per default pool isolation level and reused. Each test empties the tables with `TRUNCATE ledger_command, ledger_ordering_key RESTART IDENTITY`. JPA tests also empty `orders`.
- Run: `./gradlew :ledger-relay-jdbc:test`. Chapters of an inherited contract have class names of the form `CommandStoreContract$…`, so a filter like `--tests '*JdbcCommandStoreTest*ChapterName*'` does not match them. Run the whole class and look for the chapter in the results.

### When the app uses JPA

- Give `JdbcTransactions(transactionManager, dataSource)`, `JdbcOrderingKeys(dataSource)` and `JdbcCommandStore(dataSource)` the same DataSource object that the transaction manager holds. Otherwise acceptance is refused (F18).
- Tell `JpaTransactionManager` about the same DataSource (`setDataSource`), and do not change the JPA dialect from `HibernateJpaDialect` (Spring's default for Hibernate). `JdbcClient` joins the same transaction only if this dialect exposes the JDBC connection to the transaction (F16).
- `DefaultJpaDialect` refuses to set an isolation level (`InvalidIsolationLevelException`). Worker steps set READ COMMITTED explicitly, so they cannot start with this dialect. JTA (`JtaTransactionManager`) is not supported. With JTA, the DataSource connection is not bound to the Spring transaction when the transaction starts, so the check before acceptance (F18) refuses it. If it becomes necessary, it will be decided separately.

### Operating the queue table

- `ledger_command` has six partial indexes: `ledger_command_unfinished`, `ledger_command_ready`, `ledger_command_one_head`, `ledger_command_finished_head`, `ledger_command_halted`, `ledger_command_waiting`. When the state changes, when the lease or next attempt time changes (`ready_at`), or when the head flag changes (`is_head`), an indexed column changes and the update is not a HOT update. Dead tuples pile up quickly in the small indexes, so set a low per-table autovacuum (for example `autovacuum_vacuum_scale_factor = 0.01` and a higher `autovacuum_vacuum_cost_limit`). `fillfactor = 80` leaves room for updates outside the indexes, such as `markSubmitted`, to stay on the same page. These values have not been measured yet.
- The head flag (`is_head`) is set on insert when the key has no head, and when a command finishes, the next claim passes it on (F32, F33). Claim picks candidates by this flag alone. **After a direct DB edit** (deleting rows, changing a state, changing a key) the flags no longer match the rows. Then the key can drop out of the candidates forever, or a command that is not the head can go first. Stop the workers, commit the transaction with the fix, run `io/github/ledgerrelay/jdbc/rebuild-heads.sql` from the `ledger-relay-jdbc` jar in one transaction (`psql -1 -f rebuild-heads.sql`), then start the workers again. The script first takes a table lock on `ledger_ordering_key`, so it does not run outside a transaction. The lock makes the app's acceptances wait until commit. The tests' direct SQL setup uses this SQL too (`PostgresStorage.rebuildHeads`).
- Rows of `ledger_ordering_key` are never deleted. If a delete overlaps an acceptance in progress, the next acceptance's sequence number goes back to 1, which reverses the order or breaks the app transaction. One row is a few dozen bytes.

### Transactions and locks (F33)

The rows each transaction locks. A locked row stays locked until commit. The order row is a row of the app's table (for example `orders`).

| Transaction | Opened by | Lock order | Does app code run inside it |
|---|---|---|---|
| Acceptance (`submit`) | App | (order row the app changed) → key row → new command row → the previous command's row if that command has finished (clears its flag) | It is the app transaction itself |
| Claim | Worker | finished head command row and its key row (skipped if someone holds them) → next unfinished command row (sets the flag; keys that already have a head are left alone) → candidate command rows (skipped if locked) | No |
| Writing the result (`complete`, `skip`, `abandonHalted`) | Worker, operator | that command row → order rows the handler changes | Yes (the handler) |
| Recording and undoing a submission (`markSubmitted`, `release`) | Worker | that command row | No |
| `rebuild-heads.sql` | Operator | whole key table → command rows | No |

- Unless a handler submits, the only transaction that locks both a key row and an order row is the app's acceptance. So the worker and the app never lock the two in opposite order and deadlock. Claim does not wait even if the next command row whose flag it would set is in the middle of writing its result, because it leaves a key alone when the key already has a head. The code structure keeps this property. Calls that lock a key row exist only in `OrderingKeys`, and `WorkerWrites`, which calls the handler, does not receive that port (`ArchitectureTest`). `KeyLockFreeStoreTest` checks that the jdbc `CommandStore` does not lock key rows internally.
- **If the handler submits** (for example, submitting a post on the same key when the reservation is applied), that result-writing transaction locks the key row. In that case, update the order row first and then submit. If you submit first and then update the order row, it can deadlock with an app transaction that updates the order row and then submits on the same key (PG aborts one side with `40P01`). Passing on that key's head is also delayed until the handler commits. The ArchUnit rule cannot see this path, because it goes through the app's `LedgerRelay`.
- The remaining deadlocks are between app transactions. When one app transaction submits on several keys, submit in order of key name (failure-modes section 4).
- If an app keeps an acceptance open for a long time, the next command of that key is not sent until the app commits. Other keys are not affected.

### Running with Spring Boot

- The worker loop is `ledger-relay.worker.concurrency` threads (`ledger-relay-worker-N`). When a cycle throws, it logs the error, sleeps for `backoff.initial` and continues. This keeps processing from stopping silently because a thread died.
- Shutdown: no new cycles start, and running cycles are waited for up to `lease-duration`. A cycle waits for the TB reply up to `submit-timeout` and then writes the results, so it usually finishes within that time. Cycles that are still running after that are interrupted. Their commands are resent by another worker with the same transfer id after the lease ends, so money does not move twice.
- The worker settings (lease ≥ submit-timeout×2, batch-size ≤ 8189, max-in-flight ≥ batch-size) are checked at startup even when the workers are disabled.
- Health (when `spring-boot-health` is present): it is UP even when TB is unreachable, and only the `ledger` detail is DOWN. Acceptance works without TB, so a DOWN health would make the load balancer block acceptance as well. It is DOWN only when the store cannot be read.
  - The TB check does not hold up the health request. The TB client has no ping, so the check looks up one transfer, and it does not wait for the answer. It is UP when the last finished check succeeded and no check in flight has been waiting for an answer for more than 5 seconds. So the verdict is correct even when health is called rarely (Kubernetes default: 10 seconds) (F40). Only one lookup is sent at a time (so they do not pile up per request).
  - Waiting commands are counted only up to 1,000 (`countsCapped`). The oldest waiting command is the first entry of `ledger_command_waiting`. Even with 100,000 waiting commands during an outage, one health call finishes within about 2,000 pages (proportional to the cap). Exact counts are the job of metrics.
- Shutdown can wait up to `lease-duration` (default 30 seconds). Together with the web server's graceful shutdown, this can exceed the orchestrator's termination grace period (for example the Kubernetes default of 30 seconds). Even if the process is killed after that, the lease and the same transfer id keep money from moving twice.

### Sample shop

- Setup (`compose.yaml` in the repository root): PostgreSQL 18.6, TigerBeetle 0.17.9 (`--development --cache-grid=128MiB`, formatted only on the first start), Toxiproxy 2.13.0, two apps (`shop-a`, `shop-b`, same image). The apps reach TB only through Toxiproxy (`toxiproxy:3001`). The TB switch in Scenarios turns that proxy off and on through the Toxiproxy admin API. The apps do not get the Docker socket.
- The app containers also run with `seccomp=unconfined`, because the TB Java client uses io_uring too (F41). The same applies when you deploy an app that uses the library in a container. In production, a seccomp profile that additionally allows only the io_uring system calls (`io_uring_setup`, `io_uring_enter`, `io_uring_register`) is better.
- The only port opened on the host is the UI (`SHOP_PORT`, default 8090). PG, TB and Toxiproxy are not opened. Because the TB port is closed, the cause of F26 has no path either. If TB dies anyway, `restart: on-failure` starts it again.
- The image is built by `examples/shop/Dockerfile` with Gradle, using the whole repository as the context (multi-stage; the Gradle cache is a BuildKit cache mount).
- The schema is applied when the app starts (a convenience of the sample app only; applied once even when both apps start together, F37). The library still does not apply it automatically.
- The app resolves the TB address to IPv4 and creates the `Client` bean itself. The TB client does not accept host names (F39). Use an IP in the library setting `ledger-relay.tigerbeetle.addresses` too.
- The demo-only endpoints (`/api/demo/*`: cutting TB, injecting a bad post, burst) exist only in the `demo` profile. compose starts the apps with this profile. The "bad post" inserts a row directly into the library's table that the normal API cannot create (`BadPostInjector`).
- Numbers on the screen: the tiles and step columns count `ledger_command` directly with one query. This is enough at demo size, but it is not an operational metric. It is the place to switch to Micrometer metrics later. The numbers read from TB (balances, orders charged twice, reconciliation) are gathered every second by one dedicated thread. Reconciliation runs over everything from the start each time (up to 50,000 commands). Continuing from the last position would skip commands that finish late (such as a post that halted and was abandoned) forever. The dedicated thread exists because the TB client has no timeout: if a screen request called TB directly, the screen would freeze while TB is cut off.
- The maximum retry interval is reduced to 2 seconds (`ledger-relay.backoff.max`, library default 30 seconds). After cutting TB for 15 seconds and reconnecting, the backlog took about 40 seconds to empty with the default and about 5 seconds with 2 seconds.
- Each instance runs 2 worker threads with a batch of 4. The batch has to be small so that one thread does not take all 50 orders of a burst, and the screen shows the two instances sharing the sends.

### Moving the DB

- In a DB moved by dump and restore or to another cluster, the `accepted_xact` of a moved row can happen to equal a transaction id of the new cluster. Then an already committed reservation looks like "a reservation accepted in the same transaction", and a valid post is refused (O7). Right after the restore, before starting the workers, run `UPDATE ledger_command SET accepted_xact = '0'`.

## Known issues

- **TB server crash (O4 → F26)**: TB 0.17.9 dies with `thread 1 panic: reached unreachable code` when a TCP connection arrives in a short window right after `listening on`. Docker Desktop's port publishing appears to make such a connection in that window. It was not a memory shortage. The reproduction and the numbers are in docs/failure-modes.md F26. If an attempt reached listening and then died, the fixture starts a new container (3 attempts in total, at most 2 restarts). Count the restarts by `O4:` in system-err of the test result XML.
- The TB client has no timeout, so if the server dies, requests wait forever. The fixture handles this as follows.
  - `TigerBeetleContainer` always keeps the container logs. A startup failure is rethrown as a `ContainerLaunchException` that contains the end of the log.
  - It checks that the server stays alive for 1 second after `listening on`. If it dies within this window, a new container is started (F26). It fails only when all three attempts die.
  - Test classes that use TB call `assertAlive()` in `@BeforeEach`. If the server dies midway, the remaining tests of that class fail at once with the panic log instead of hanging for 2 minutes each.
  - As a safety net, each test has a 2-minute limit and the whole Gradle test task a 15-minute limit.
