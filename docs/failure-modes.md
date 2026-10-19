# Failure modes

This document lists, one by one, the failures ledger-relay has to prevent. For each failure it links in one line **what breaks → how it was reproduced → how the structure prevents it → what verifies it**.

- Reproduction is done with tests. Naming the test tells you which test will fail if the failure comes back.
- A test that passes from the start is checked by deliberately breaking the code (a control experiment) to see that it really fails. The control experiment results are recorded too.
- Only numbers that were actually measured are recorded. Operational metrics and experiments are filled in at the stage that covers them.

## 1. Prevented failures

F1–F10 are core rules, F11–F19 the PostgreSQL store, F21–F25 the worker, F26 the test environment, F27–F36 defects in core, the store and the Spring module found in reviews, F37–F45 were found while building the sample shop (F40 is a library defect found at that time), F46–F47 were found while adding metrics, and F48 while measuring load (a library defect).

### F1. Waiting commands of a blocked key use up the claim limit and starve other keys

- **What breaks**: while the earlier command of a key is in flight, the later commands wait. If the store also returns these waiting commands as candidates, the limit of one claim fills up with waiting commands and other keys are never claimed.
- **Reproduction**: `CommandStoreContract` › `aBlockedKeyDoesNotUseUpTheLimit`. Key a has three commands and its head is in flight; locking with limit 1 must return the command of key b.
- **Structure**: the store returns only the unfinished head command of each ordering key as a candidate. The store contract (`CommandStoreContract`) pins the per-key head rule (G4) for both the in-memory store and PG, and core does not check it again. The core gate only looks at the referenced reservation.
- **Verification**: a control experiment that removes the head filter fails 3 tests. Checked again in jdbc together with row locks (F12).

### F2. The previous worker writes a result to a command that another worker took after the lease ended

- **What breaks**: if a late result from a slow worker is applied, the result is written twice or the handler runs twice (G5).
- **Reproduction**: `LedgerRelayContract` › `theLateResultIsFenced`, `theCurrentWorkerWins`.
- **Structure**: every claim increments the lease token. Every write applies only "when the state is in flight and the token is the same". It is blocked once in the state machine and once in the store's conditional write.
- **Verification**: a control experiment where the service ignores the token fails 2 tests.

### F3. The result is recorded but the outcome handler's effect is missing

- **What breaks**: if the final state commits even after the handler threw, the app's business state (orders, wallets) never catches up with the ledger result (G6).
- **Reproduction**: `LedgerRelayContract` › `theResultIsRolledBackWithTheHandler`. On the store side, `CommandStoreContract` › `aThrowingTransactionUndoesTheWrite`.
- **Structure**: the final write and the handler run in one transaction. If the handler throws, the transaction rolls back and the failure is recorded in a new transaction.
- **Verification**: a control experiment that commits despite the handler exception fails 4 tests. Checked again on PostgreSQL with a handler that writes to the DB (`HandlerAtomicityTest`). A control experiment where the handler writes through a separate connection outside the transaction (auto-commit) fails 2 tests. If it throws after writing, a record remains in the app table (`neitherTheResultNorTheAppRowRemains`), and the retry fails again on a primary key violation and cannot end as applied (`retryingLeavesExactlyOneAppRow`). The first version of the retry test only counted records and missed this control experiment; it was fixed by also checking the final state.

### F4. Resending after a handler failure loses the original rejection reason

- **What breaks**: if a command rejected for insufficient balance is resent to TB after its handler failed, TB only returns `IdAlreadyFailed`. The original reason (`ExceedsCredits`) is lost.
- **Reproduction**: `LedgerRelayContract` › `theKnownResultComesBackWithTheNextClaim`. For the state machine, `CommandStateMachineTest` › `submittingAgainIsABug`.
- **Structure**: the received TB result is kept as `known_result`, and the next claim finishes with that result without sending to TB. The state machine refuses a resubmission as a bug.
- **Verification**: a control experiment that removes the failure record fails 3 tests.

### F5. When the handler throws an `Error`, no result is kept

- **What breaks**: an `Error` (out of memory and so on) bypasses the handler failure handling, so the original reason is lost for the same reason as F4.
- **Reproduction**: `LedgerRelayContract` › `theKnownResultIsKept`.
- **Structure**: an `Error` also rolls back through the same path and the result is kept in a new transaction; then the `Error` is rethrown, not swallowed.
- **Verification**: failed before the fix (Red). A control experiment that swallows the `Error` fails `theErrorIsRethrown`.

### F6. Calling a worker step inside an outer transaction brings F3 back

- **What breaks**: if a worker step joins the caller's transaction, the rollback of a handler failure is buried in the outer transaction and only the final state commits. This was actually reproduced in the final review.
- **Reproduction**: `LedgerRelayContract` › `completingInsideATransactionIsRefused`.
- **Structure**: there is `Transactions.isActive()`, and the service refuses claims and writes called inside an active transaction.
- **Verification**: failed before the fix (Red).

### F7. The store drops the handler failure count, so backoff does not grow

- **What breaks**: a handler that keeps failing always rests only for the first delay and is claimed again. The handler failure count that reveals a blocked key (the `handler_failures` column) also shows 0.
- **Reproduction**: `CommandStoreContract` › `everyColumnOfTheRowIsKept`, `LedgerRelayContract` › `aSecondFailureWaitsTwiceAsLong`.
- **Structure**: the two contracts pin the round trip of the whole row and the growing delay. The jdbc store has to pass the same contracts.
- **Verification**: a control experiment that drops only the handler failure count fails exactly these 2 tests.

### F8. Upgrading TB renames result codes and stored results can no longer be read

- **What breaks**: if results are stored by Java constant name (`name()`), a rename in the TB client makes `known_result` unreadable and the key is blocked forever.
- **Reproduction**: `ResultCodeWireNameTest`.
- **Structure**: for each of the 68 codes, the snake_case stored name from the TB source is pinned by hand. Java constant names are not accepted as stored names.
- **Verification**: a control experiment that deletes one line of the table fails the guard.

### F9. Within one key, a post gets an earlier sequence number than its reservation (HALT)

- **What breaks**: if the post is sent to TB before the reservation, it is wrongly rejected with `PendingTransferNotFound`, and the amount of the reservation created later stays held until its timeout.
- **Cause**: the acceptance checks prevent this order, so it happens only through a bug or a direct DB edit.
- **Limits of the first fix**: at first it only halted. The row stayed in the accepted state, was reported again on every claim and took one slot of the limit, and a status lookup showed it as "accepted", which made the cause hard to find.
- **Reproduction**: `LedgerRelayContract` › `WhenThePostIsAheadOfItsReservation`. A row with the order reversed is inserted directly into the store without the acceptance checks. Because it is a contract, the jdbc store has to pass the same chapter.
- **Structure**: when the gate halts, the command is parked as HALTED with an incremented token. HALTED is not a final state; it stays the head of the key and blocks the later commands (G4). It is not a claim candidate, so it takes no slot of the limit, and it is reported once. Other keys keep being processed. Section 3 explains why it is not moved out as in a dead letter queue.
- **Follow-up**: after fixing the cause, the operator abandons it with `abandonHalted(commandId)`. The post ends as `SKIPPED` (reason `ABANDONED_BY_OPERATOR`), and the handler receives that reason in the same transaction. The key is released, the later reservation is processed, and the app posts again with a new commandId. It never went to TB, so the ledger has no wrong movement. If the handler fails or throws an `Error`, the command stays halted and can be abandoned again. The app calls this through the public port `LedgerRelayOperator` (list of halted commands, abandon). A command that has already been sent to TB cannot be abandoned (F27).
- **Verification**: a control experiment that treats HALT as OPEN fails 10 tests, and a store control experiment that returns HALTED as a candidate fails 4. The store contract pins that a halted row has no completion time (a control experiment that sets the completion time fails). The number of halted commands is visible in health (`halted`) and the operator port (`haltedCommands`).

### F10. The reservation row referenced by a post is missing (HALT kept, decision reversed)

- **What breaks**: if the reservation ended in a conflict (CONFLICT), TB holds **a transfer with different content** under the same transfer id. Sending the post in that state can post someone else's pending transfer for its full amount. If the reservation looked "missing" because the store could not read a locked reservation row, the post goes before the reservation and causes the same damage as F9.
- **Change of decision**: at one point the gate was switched to OPEN, on the reasoning "the reservation's TB transfer id is fixed at acceptance, so there is no way to post the wrong reservation. The archiving policy deleted a finished reservation, so let TB decide". A review showed two errors. First, even with a fixed id, there is no guarantee that the transfer TB holds under that id is ours (CONFLICT). Second, the spec's archiving policy does not delete reservations referenced by unfinished posts, so "the row is missing" is not a normal path. So the change was reverted.
- **Reproduction**: `LedgerRelayContract` › `WhenTheReservationRowIsMissing`. For the gate, `OrderingGateTest` › `haltsWhenTheReservationIsMissing`.
- **Structure**: like F9, it halts and is parked as HALTED. A halt is now cheap (reported once, takes no slot of the limit, has an abandon action), so there is no reason to switch to sending.
- **Verification**: a control experiment reverted to OPEN fails 3 tests. That `lockDue` reads the referenced reservation regardless of locks was checked in the PostgreSQL concurrency tests (F13).

### F11. A post references a reservation accepted in the same transaction

- **What breaks**: the acceptance rule requires the reservation referenced by a post or void to be "already committed". If an app accepts a reservation and a post together in the same app transaction, the fates of the two commands are tied to one transaction. If another query in that transaction fails and rolls it back, both disappear, which is fine; but if the app rolls back only the reservation with a savepoint, a post with nothing to reference remains. It then halts as in F10. The in-memory store accepted this case.
- **Reproduction**: `LedgerRelayContract` › `thePostIsRefused`. For the store flag, `CommandStoreContract` › `aRowIsMarkedAsAcceptedInThisTransactionUntilCommit`.
- **Structure**: the store only reports facts. `CommandRecord.acceptedInThisTransaction()` tells whether the current transaction inserted that row. On PostgreSQL it compares `accepted_xact xid8` with `pg_current_xact_id_if_assigned()`. The refusal is done by the service (`CommandLifecycle`) with `ReservationNotFoundException`.
- **Verification**: both tests failed before the implementation (the flag was false, and there was no refusal exception).

### F12. A locked head command is skipped and the next command of that key is returned

- **What breaks**: worker A has claimed command 1 of key order-1 and is sending it to TB. If worker B, while deciding "is this the head" in `lockDue`, also skips the locked command 1, command 2 looks like the head and goes out at the same time. The order within the key (G4) breaks. F9, a post going before its reservation, comes back through concurrency.
- **Reproduction**: `CommandStoreContract` › `aLockedHeadHidesItsWholeKey`. Another transaction (`OtherTransaction`) keeps command 1 locked while `lockDue` is called.
- **Structure**: only candidate rows are locked, with `FOR UPDATE OF c SKIP LOCKED`. The head check (`NOT EXISTS` an earlier unfinished sequence number) reads without locking, so the locked command 1 is also seen as "unfinished".
- **Verification**: as a control experiment, adding `FOR UPDATE SKIP LOCKED` to the `NOT EXISTS` subquery fails only this test.

### F13. Because the reservation row is locked, the post sees the reservation as "missing"

- **What breaks**: when the reservation row is locked because another transaction is writing it, a post candidate that tries to read the reservation with a lock skips it, and it becomes "referenced reservation missing". The gate halts according to F10 (HALTED). The money is safe, but a valid post falls into a state that needs operator action.
- **Reproduction**: `CommandStoreContract` › `aLockedReservationIsStillReported`.
- **Structure**: the reservation is read with a `LEFT JOIN` without locking. The lock is taken only on the candidate row (`OF c`).
- **Verification**: as a control experiment, reading the reservation with `LEFT JOIN LATERAL (… FOR UPDATE SKIP LOCKED)` fails only this test (the "result of reading the locked reservation" is empty). The simple mistake of widening to `FOR UPDATE SKIP LOCKED` is refused by PostgreSQL at the syntax stage (the nullable side of an outer join cannot be locked), so it could not serve as a control experiment that shows the meaning.

### F14. Acceptance commit order and sequence number order diverge

- **What breaks**: when two acceptances on the same key overlap and the sequence number is taken as `max(seq)+1`, both can see the same number, or the one that commits later can get the smaller number. While a worker believes it has processed the smaller number first, the row with that number appears later. The head check (F12) relies on the premise "no command ahead of me appears later", and that premise collapses.
- **Reproduction**: `CommandStoreContract` › `theKeyLockHoldsUntilCommit`. While the first acceptance has taken sequence number 1 and not committed, the second acceptance must be blocked, and it must get 2 after the first commits.
- **Structure**: the key row (`ledger_ordering_key`) is upserted to lock it and hand out the number. The lock is held until commit, so acceptances on the same key queue up. This turns a decision over a set with no row to lock into one row to lock (section 4, "Why the isolation level does not matter").
- **Verification**: as a control experiment, switching to `max(seq)+1` fails this test and `eachKeyCountsFromOne` (the second one is not blocked).

### F15. Concurrent acceptances with the same commandId break the app transaction (G7)

- **What breaks**: if a unique violation exception is caught and treated as "already exists", PostgreSQL puts the whole transaction in an aborted state. Every following query fails with `current transaction is aborted`, and the whole app transaction rolls back. The order the retrying user saved is lost with it.
- **Reproduction**: `CommandStoreContract` › `aConcurrentDuplicateLeavesTheAppTransactionUsable`. The late side must get back the row committed first, and must still be able to take a sequence number on another key afterwards.
- **Structure**: `INSERT … ON CONFLICT (command_id) DO NOTHING RETURNING id`. If nothing was inserted, the existing row is read in the same transaction. No error is raised, so the transaction survives.
- **Verification**: as a control experiment, removing `ON CONFLICT` and catching `DuplicateKeyException` fails this test and `aSecondInsertReturnsTheFirstRow` with `current transaction is aborted`.

### F16. In a JPA transaction, acceptance commits separately (G1)

- **What breaks**: if the app uses `JpaTransactionManager` and that transaction does not expose the JDBC connection, `JdbcClient` gets a separate connection from the DataSource and writes in auto-commit. The transaction looks active (`requireActive` passes), so there is no warning at all. Even if the app rolls back, the command remains. There is no order, but the ledger transfer goes out.
- **Reproduction**: `JpaLedgerRelayTest` › `anAppEntityAndItsCommandRollBackTogether`. The whole inherited service contract also runs on the JPA manager.
- **Structure**: `JpaTransactionManager` gets the same DataSource (`setDataSource`), and `HibernateJpaDialect` exposes the JDBC connection.
- **Verification**: a control experiment with a dialect that accepts isolation levels but does not expose the connection fails 12 tests (this control experiment predates F18; today that setup itself is refused at acceptance). They are this test (the "rolled-back command" remains) and 11 rollback-related tests of the contract (G1, G6). The control experiment planned first, `DefaultJpaDialect`, refuses to set an isolation level, so all 69 tests failed at transaction start. That failure does not show the joining problem, so it was not used as a control experiment.

### F17. The worker follows the connection pool's default isolation level and blows up on serialization failures

- **What breaks**: if the app sets the connection pool default to REPEATABLE READ, the worker steps (claim, writing the result) also open at that level. Then, when they touch the same row as another worker, they end with a serialization failure (40001) instead of waiting. The worker does not expect this failure, so a command whose result could not be written stays tied up until the lease expires.
- **Reproduction**: `JdbcTransactionsTest` › `aNewTransactionRunsAtReadCommitted`.
- **Structure**: transactions that `JdbcTransactions` opens itself set READ COMMITTED explicitly. When joining an app transaction, it does not change the app's level (`joiningKeepsTheAppsLevel`).
- **Verification**: this test failed before the isolation level was set explicitly (it read "repeatable read").

### F18. Acceptance commits separately in a transaction that the store's connection is not bound to (G1, found in review)

- **What breaks**: the situation of F16's control experiment also occurs in real setups: a JPA dialect that does not expose the JDBC connection, or a transaction manager and store holding different DataSource objects (proxies such as p6spy or datasource-proxy). Then, even though a transaction is open, `JdbcClient` auto-commits on a separate connection. The order rolls back but the transfer goes out, and the key lock does not last until commit either, so R12 breaks too. At first this was deferred as open failure O6, but because it is a path where money leaves silently, it was closed in 02b.
- **Reproduction**: `JdbcTransactionsTest` › `aJpaTransactionHidingTheConnectionIsRefused`, `aTransactionOnAnotherDataSourceIsRefused`.
- **Structure**: `JdbcTransactions(transactionManager, dataSource)` receives the same DataSource as the store. `requireActive` checks that a transaction is open and also that a connection of that DataSource is bound to the transaction (`TransactionSynchronizationManager.getResource`); otherwise it refuses with `IllegalTransactionStateException`.
- **Verification**: before the check was added, both tests failed with "no exception".

### F19. The cost of claim polling grows with the size of the finished history (found in review)

- **What breaks**: `lockDue` picks candidates in acceptance order (`ORDER BY c.id LIMIT n`). Without an index that supports this condition, the planner scans in primary key order and filters out finished rows one by one. Workers poll at short intervals, so the DB load grows with the size of the history. The archiving policy is off by default.
- **Reproduction**: `LockDueScanTest` › `theDueQueryDoesNotFilterThroughFinishedRows`, with 100 accepted commands behind 200,000 finished commands. The sum of "Rows Removed by Filter" in `EXPLAIN ANALYZE` of the same query must be 100 or less. With only 1 accepted command, the existing partial index (`ledger_command_unfinished`) was used and it did not reproduce. With 100, a primary key scan was chosen.
- **Structure**: partial index `ledger_command_due ON (id) WHERE state IN ('PENDING','CLAIMED')` (replaced by `ledger_command_ready` in 02e, which holds only heads and so does not read the finished history either, F32). `theDueQueryDoesNotFilterThroughFinishedRows` still passes with the new query.
- **Verification**: before the index, 200,000 rows were filtered out and execution took 21.8 ms. After the index, the test passes (reviewer's measurement: 151 ms → 0.047 ms at 1 million rows).

### F20. A string containing U+0000 breaks acceptance with an SQL error, and a lone surrogate makes hashes collide

- **What breaks**: PG `text` does not accept the 0x00 byte. If a commandId, ordering key or reservation contains U+0000, the acceptance INSERT ends with an SQL error, and in PG the whole app transaction is aborted. A lone surrogate (for example `\uD800`) becomes `?` in `getBytes(UTF_8)`. So `"order-\uD800"` and `"order-?"` get the same content hash, and different commands can be accepted as the same content (G7).
- **Reproduction**: `LedgerCommandTest` › `InvalidCommands`, the rows for U+0000 in the commandId, a lone surrogate in the ordering key, and U+0000 in a post's reservation. `aTransferWithAPairedSurrogateIsAccepted` checks that a paired surrogate (emoji) is allowed.
- **Status and abandon paths (found in review)**: blocking only acceptance leaves `status(commandId)` and `abandonHalted(commandId)` sending the same string to PG as is. `status` joins the app transaction, so an app transaction that looks up an external key (such as an idempotency key from an HTTP path) is broken entirely by a single U+0000. pgjdbc sends a lone surrogate as `?`, so `status("pay-\uD800")` returns the state of a different command, `pay-?`, and `abandonHalted` tries to abandon a different command. The commandId is not part of the hash, so the risk here is not a hash collision but matching another command.
- **Reproduction**: `LedgerCommandTest` › `InvalidCommands`, the three rows above. `LedgerRelayContract` › `aMalformedIdIsNotFoundAndTheAppTransactionStaysUsable`, `aLoneSurrogateDoesNotMatchAnotherCommand`, `aLoneSurrogateIdIsRefusedAsUnknown`. They cannot fail on the in-memory store and fail only on PG (both the DataSource and the JPA contract).
- **Structure**: `requireWellFormed` in the `LedgerCommand` constructor checks the three string fields and refuses with `InvalidCommandException`. The same check is exposed as the public static method `LedgerCommand.isAcceptableId`; for an id that could never be accepted, the service's `status` returns "not found" without asking the DB, and `abandonHalted` returns "a command that was never accepted". The decision lives in core, so the store never receives such values.
- **Verification**: before the check was added, the three constructor rows failed with "no exception", and the three contract tests failed 6 times on PG (SQL error, the state of `pay-?`, the HALTED check of a different command).

### F21. Returning slots right after the submit-timeout makes requests pile up without end while TB is down

- **What breaks**: the TB Java client has no timeout. Even if the worker stops waiting at the submit-timeout, the request stays in the client and is resent until TB comes back. If the in-flight slots are returned at that point, the worker claims and sends again in the next cycle. While TB is down, requests pile up in the client without end, and the moment TB comes back they all arrive at once.
- **Reproduction**: `RelayWorkerTest` › `aTimedOutRequestKeepsItsSlotsUntilItEnds`. With 4 slots, after two transfers are sent and time out, 2 slots must remain, and only after the held replies complete does it return to 4. `noSlotsMeansNoClaim` pins that slots are taken before claiming. Without slots there is no claim, so the commands stay accepted without a lease.
- **Structure**: `RelayWorker` takes batch-size slots before claiming. The share it does not send is returned at once, and the share it sent is returned only in `whenComplete` of the request future.
- **Verification**: two control experiments. Moving the return from `whenComplete` to "right after sending" failed `aTimedOutRequestKeepsItsSlotsUntilItEnds` and `unusedSlotsAreGivenBackAtOnce`. Turning off the slot check before claiming failed `noSlotsMeansNoClaim`.

### F22. If the number of results differs from the request, commands end with someone else's result

- **What breaks**: the worker pairs TB results with commands in request order. If one result is missing or extra, the later commands receive the result of an earlier command. A successful transfer ends as rejected, or the other way around. The handler processes the business with the wrong result.
- **Reproduction**: `RelayWorkerTest` › `aReplyWithTheWrongCountWritesNothing`. When one result comes back for two transfers, it writes nothing, releases both, and reports `malformedReply:2:1`.
- **Structure**: `RelayWorker` compares the number of results with the number of requests. If they differ, it releases all of them. A resend uses the same transfer ids, so anything TB already recorded comes back as `exists`. The adapter does not check the count; the decision lives in core.
- **Verification**: before the handling was added, `results.get(i)` went out of range and the cycle ended with an exception (test failure).

### F23. A command whose reply was lost is resent with a new transfer id and money moves twice (closes open failure O3)

- **What breaks**: TB executes each id only once. So if the transfer id changes when the same command is resent, TB records it as a new transfer. If the first request, whose reply was lost, was already recorded, the money goes out twice.
- **Reproduction**: `RelayOnTigerBeetleTest` › `aLostReplyIsRetriedWithTheSameTransferIdAndMovesMoneyOnce`. The first request is recorded in the real TB, but the reply never reaches the worker (test double `ReplyLosingLedger`). When the worker releases it on timeout and resends after the backoff, both requests have the same transfer id, the second TB result is `Exists`, and only 500 moves. A cut connection (`aCutConnectionIsRetriedAfterItIsRestored`, Toxiproxy) also ends as applied after it is restored, and money moves only once.
- **Structure**: the transfer id is issued by the generator at acceptance and stored in the row (`transfer_id`). The worker only sends the id of the claimed row and never creates a new one. A resend of the same id to TB comes back as `exists` (same body, A1·A3) or `id_already_failed` (transient failure, PRIOR_FAILURE report).
- **Verification**: as a control experiment, giving the second request a new transfer id moved 1000 and the test failed.

### F24. A store error on one command while writing results discards the other results of the same batch (found in review)

- **What breaks**: after receiving the TB results, the worker writes each command separately. If the write of one command ends with a store exception (lost DB connection and so on) and that stops the whole cycle, the later commands cannot keep the results they received and stay in flight until the lease ends. When the lease ends they are resent with the same transfer ids. A rejection TB remembers (`exceeds_credits` and so on) then comes back as `id_already_failed`, loses its original reason and ends as PRIOR_FAILURE. The outcome that F3 and F4 were meant to prevent happens by another route.
- **Reproduction**: `RelayWorkerTest` › `theOtherResultsOfTheBatchAreStillWritten`. The batch is [pay-1 Created, pay-2 ExceedsCredits], and the store throws once when the result of pay-1 is written. pay-2 must become rejected with `ExceedsCredits`, and `writeFailed:pay-1` must be reported.
- **Structure**: `RelayWorker.attempt` catches the `RuntimeException` of each write (recording the submission, skipping, the result, releasing), reports it through `WorkerEvents.writeFailed`, and moves on to the next command. The failed command is claimed again with the same transfer id after the lease expires. An Error is not swallowed.
- **Verification**: before the fix, the cycle ended with an exception and the test failed.

### F25. The lease is shorter than the wait for the reply, so another worker resends the same command (found in review)

- **What breaks**: the lease starts at the claim. So it has to cover recording the submission, waiting for the reply (submit-timeout), and writing the result of each command. If the lease is shorter than the submit-timeout, another worker claims and sends the same command again while the first worker is waiting for the reply. Fencing keeps money from moving twice. But the second request gets `id_already_failed` for a rejection TB remembers, and the first worker's real result write is blocked with 0 rows, so the original reason is lost.
- **Reproduction**: `WorkerSettingsTest` › `Invalid`, the row where the lease is shorter than twice the submit-timeout.
- **Structure**: `WorkerSettings` refuses settings unless `lease ≥ 2 × submitTimeout`. Writing the results is one transaction per command, including the handler, so it grows with batch-size. The Javadoc says a large batch-size needs a longer lease. This share depends on the handler's speed, so a settings check cannot prevent it.
- **Verification**: before the check was added, that row failed with "no exception".

### F26. The TB container panics on a TCP connection that arrives right after listening (mitigated; open failure O4 narrowed down to its cause)

- **What breaks**: the TigerBeetle 0.17.9 container prints `listening on` and dies 2–130 ms later with `thread 1 panic: reached unreachable code` (exit 139). This happens before our client connects. A whole test class fails with `initializationError`. On 2026-10-17 it happened in more than half of the runs of the tigerbeetle module.
- **Reproduction and narrowing the cause (2026-10-18, `docker run` with the same command as the fixture)**:
  - Starting without publishing the port: 0 out of 50 (20 alone, 30 with three starting at the same time).
  - Publishing the port (`-P`): 2 out of 15, even without any client.
  - Without publishing the port, making only a TCP connection with `nc -z` from the same network namespace right after listening and closing it: 3 out of 30.
  - The same probe 3 seconds later: 0 out of 20.
  - Not a memory shortage. The VM had 5.9 GiB free, and dmesg had no OOM record.
  - Conclusion: a TCP connection that arrives in a short window right after listening kills TB. In the tests, the side that makes this connection appears to be Docker Desktop's port publishing path. Our code cannot control that timing. We consider it a bug on the TB side; it was not confirmed in the TB source.
- **Structure (mitigation)**: `TigerBeetleContainer.start()` starts a new container when this attempt's log shows `listening on` but the startup failed. It makes 3 attempts in total, so at most 2 restarts. It does not matter which step saw the death first (Testcontainers' startup check, the log wait, the 1-second stability check). The first version of this check only looked at "an exception thrown by the stability check", so when TB died right after listening and Testcontainers' startup check saw it first, it did not restart (reproduced by `aDeathSeenByTheStartupCheckIsRetried`). Failures that do not reach listening (io_uring and so on) are not retried. Each attempt writes a separator line `--- TB start attempt n ---` to the log, and each restart leaves `O4: TigerBeetle exited right after listening; starting a new container (attempt n)` on standard error. The TB of the proxied setup (`ProxiedTigerBeetle`) does not publish its port. The client connects only through the proxy, so the cause has no path at all.
- **Reproduction tests**: `TigerBeetleContainerGuardTest` › `startRetriesWhenTheServerDiesRightAfterListening` (a fake server that dies once and then lives; the log has the panic of the first attempt and the separator of the second), `aDeathSeenByTheStartupCheckIsRetried` (a fake server that dies as soon as it prints listening), `startFailsWhenServerDiesRightAfterListening` (if it dies every time, it fails with "3 attempts all exited right after listening" and the panic log).
- **Verification**: a control experiment that reduces the number of attempts to 1 failed two tests. After the mitigation, the tigerbeetle module was run 5 times with 0 failures. However, those 5 runs also had 0 retries of the real TB (no natural occurrence). So the effect on a real panic has not been observed yet.

### F27. The operator abandons a halted command that was already sent to TB as "not sent" (found in full review)

- **What breaks**: HALTED is entered only from the accepted state (PENDING). But the accepted state also includes commands that were sent once and released (submit_count > 0), and commands that received a TB result but whose handler failed (known_result). If the referenced reservation row of such a command disappears, it halts at the next claim. If the operator abandons it with `abandonHalted`, PG becomes SKIPPED (ABANDONED_BY_OPERATOR) and the handler receives "not applied". In TB it may already be posted, so the money and the business state diverge.
- **Reproduction**: `LedgerRelayContract` › `aReleasedCommandIsRefused`, `aCommandWithAKnownResultIsRefused` (in-memory, PG DataSource, PG JPA).
- **Structure**: `CommandLifecycle.abandonHalted` refuses with `IllegalStateException` (the command has already been sent to TB and cannot be abandoned) when `submitCount > 0` or a `knownResult` exists. A person cleans up such commands after checking the transfer in TB. The public operator port surfaces this refusal as is.
- **Verification**: before it was added, six tests failed with "no exception".

### F28. Resubmitting the same post after its reservation was deleted gives "reservation missing" instead of the receipt (found in full review)

- **What breaks**: `submit` did the reference check before the same-commandId check. The archiving job deletes commands N days after they finish. A reservation finishes before its post, so it can be deleted first. If the app then resubmits a post whose reply it missed, with the same content, it gets `ReservationNotFoundException` instead of the original receipt (the promise of `LedgerRelay.submit`). A resend with different content also becomes "reservation missing" instead of `CommandConflictException`.
- **Reproduction**: `LedgerRelayContract` › `resubmittingAFinishedPostAfterItsReservationIsGoneReturnsTheReceipt`, `resubmittingItWithOtherContentIsAConflict`.
- **Structure**: after taking the sequence number, it first looks up the same commandId. If found, it ends with the receipt or a conflict and does not do the reference check. The race path (`insertIfAbsent` returns another transaction's row) still compares hashes.
- **Verification**: before the change, both tests failed with `ReservationNotFoundException`.

### F29. One row with inconsistent data makes the whole claim transaction throw and the relay stops (found in full review)

- **What breaks**: claim runs the gate and the state machine for each candidate in the same transaction. If one candidate's data violates the rules, that decision throws. For example, with a post row whose referenced reservation is empty (direct DB edit), `Optional.orElseThrow` throws. Then the whole claim rolls back, the worker ends every cycle with the same exception, and all keys stop. This contradicts "only that key is blocked".
- **Reproduction**: `LedgerRelayContract` › `aMalformedRowIsHaltedAndOtherKeysKeepFlowing`. pay-2 of another key is claimed, and the bad row is reported as a halted command.
- **Structure**: `CommandLifecycle.claim` catches the decision exception of a single candidate and parks that row as HALTED. The decision is pure Java, so the PG transaction is intact. If it cannot even park it (for example, the store returned a finished row as a candidate), it skips that candidate. Either way, the reason (the original exception) is put in `ClaimBatch.problems`, and the worker reports it through `WorkerEvents.malformedCandidate` (strengthened in the final review: at first the exception was discarded and the skip case gave no signal at all; `ClaimIsolationTest`). Problems that occur while the store **reads** a row (resolving the stored name of a result code) are outside this isolation. So `known_result` and `result_code` have a CHECK that accepts only the 68 stored names (`anUnknownResultCodeIsRefused`, `everyWireNameIsAccepted`). The other DB CHECKs that block such rows are in F31. The operator finds them in the list of halted commands.
- **Verification**: before it was added, claim ended with `NoSuchElementException` and the test failed.

### F30. A long commandId or ordering key breaks acceptance with an SQL error (found in full review)

- **What breaks**: `command_id` and `ordering_key` are `text` columns with b-tree indexes. PG refuses index entries larger than about 2.7 KB. If the app sends a longer id, the upsert for the sequence number (`lockKeyAndTakeSeq`) ends with "index row size exceeds maximum" and the whole app transaction is aborted. It is the same kind of failure as F20.
- **Reproduction**: `LedgerCommandTest` › `InvalidCommands`, the rows for a commandId of 513 UTF-8 bytes and an ordering key of 171 Hangul characters (513 bytes). 512 bytes fit (`aCommandIdOf512BytesIsAccepted`). `isAcceptableId` is also false at 513 bytes.
- **Structure**: `LedgerCommand.MAX_ID_BYTES = 512`. It applies to commandId, orderingKey and reservationCommandId, and `isAcceptableId` checks the same limit.
- **Verification**: before it was added, three tests failed.

### F31. The schema does not enforce id comparison, transfer ids and row invariants on the DB side (found in full review)

- **What breaks**:
  - **Collation**: the unique index on `command_id` keeps acceptance idempotent. But this is a problem if the column uses the DB's default collation. If the OS libc collation changes (a new Debian release in the postgres image, a glibc 2.28-style upgrade), text b-tree indexes can silently become inconsistent. Then `ON CONFLICT (command_id)` misses the existing row, a resent acceptance creates a second row with a new transfer id, and the money goes out twice.
  - **Duplicate transfer id**: if `transfer_id` values collide (a generator bug), the second command gets `exists` from TB and takes someone else's transfer as its own result.
  - **Row invariants**: a row that is in flight but has no lease expiry time is never claimed again. A post row without a referenced reservation causes F29. Online migrations are a problem too. After `ADD COLUMN`, the server-prepared statement of `SELECT c.*` fails inside an app transaction with `0A000 cached plan must not change result type`, and the whole app transaction is aborted.
- **Reproduction**: `JdbcCommandStoreTest` › `TheSchemaGuards` › `idColumnsCompareByteForByte` (4), `aDuplicateTransferIdIsRefused`, `rowsThatBreakTheInvariantsAreRefused` (3), `findSurvivesAnAddedColumn`. The last one was reproduced with a lookup inside an app transaction. Outside a transaction, pgjdbc re-prepares on its own and it did not reproduce.
- **Structure**: the id columns use `COLLATE "C"`. Named constraints: `ledger_command_transfer_id_key UNIQUE`, `ledger_command_seq_check`, `ledger_command_reference_check`, `ledger_command_claimed_lease_check`, `ledger_command_skipped_reason_check`. Queries use the shared column list (`JdbcCommandStore.COLUMNS`). The contract tests' row builders give each key a different transfer id (`TestTransferIds`).
- **Verification**: before they were added, 9 tests failed (collation 4, transfer id 1, CHECK 3, added column 1).

### F32. The cost of claim polling grows with the backlog behind a blocked key

- **What breaks**: the old `lockDue` searched, for each candidate, "is there an earlier unfinished command in the same key" (`NOT EXISTS`, a Nested Loop Anti Join in the plan). If 100,000 waiting commands pile up behind one key whose head is in backoff or in flight, one poll checks those 100,000 one by one. It took 100,000 searches for an earlier unfinished command, about 400,000 pages, 576–680 ms (baseline 0.2 ms). When there are fewer candidates than the limit, `LIMIT` cannot stop early, and the planner wrongly estimated that most rows pass. F19's check ("Rows Removed by Filter") does not see this cost. The same proportional cost appears when many keys have their head in backoff. When many sendable heads pile up right after TB recovers, they have to be sorted in acceptance order (id). `FOR UPDATE` sits between the sort and `LIMIT`, so a top-N sort cannot be used; in the experiment it took 21 ms and spilled to a disk sort.
- **Reproduction**: the three tests of `LockDueBoundedWorkTest` check, at limit 128, that the top node of the plan reads 1,000 pages or fewer. `aBacklogBehindABlockedKeyIsNotRead` (20,000 waiting behind a blocked key + 100 sendable keys), `headsWaitingForTheirRetryAreNotRead` (20,000 keys whose head is in a 1-hour backoff + 100), `manyReadyHeadsStopAtTheLimit` (20,000 sendable heads behind 50,000 finished rows). The measurements are the four scenes of `LockDueBacklogMeasurementTest` (`./gradlew :ledger-relay-jdbc:measurement`).
- **Structure**:
  - Candidates are ordered by "the time they became sendable". For an accepted command that time is the next attempt time, for an in-flight command the lease expiry time, and ties go in acceptance order. The port contract `CommandStore.lockDue` defines this, and the in-memory and PG stores pass the same contract tests. The order within a key does not change, because a key has only one head.
  - The jdbc adapter has `ledger_command.is_head` (is this the command whose turn it is in the key) and `ready_at` (the time to send, a generated column).
  - There are two indexes. `ledger_command_ready ON (ready_at, id) WHERE is_head AND state IN ('PENDING','CLAIMED')` is what claim reads. The unique `ledger_command_one_head ON (ordering_key) WHERE is_head` prevents two heads per key. `ledger_command_due` was dropped.
  - On insert, if the key has no head, the new command becomes the head. When a command finishes, the flag stays as it is, and the next claim locks that key row and passes the flag on to the next unfinished command (F33). At first, completion passed it on by updating the unfinished count on the key row, but that lock deadlocked with the handler, so it was changed.
  - After a direct DB edit, recompute with `rebuild-heads.sql` (docs/development.md).
- **Verification**:
  - With the old query, two page tests failed (backlog of 20,000: 61,097 pages, 126 ms; 20,000 heads in backoff: 1,447 pages).
  - The two new ordering contract tests (`aRetriedCommandQueuesAtItsRetryTime`, `anExpiredLeaseQueuesAtItsExpiry`) failed on both the in-memory store and PG.
  - Mutation checks:
    - Removing the `is_head` condition fails 17 jdbc tests.
    - Switching to `ORDER BY c.id` fails the 2 ordering contract tests and the recovery-burst page test (1,993 pages).
  - After every contract test, the flags are checked against the rows (`PostgresStorage.assertHeadsConsistent`).
  - Measurement (limit 128, 100,000 rows, three runs): baseline 112 pages 0.12 ms, backlog of 100,000 121 pages 0.13 ms (previously 576–680 ms), 100,000 heads in backoff 201 pages 0.10 ms, 100,000 sendable heads 194 pages 0.11 ms.
- **Remaining**:
  - If the DB is edited directly and `rebuild-heads.sql` is not run, the key can drop out of the candidates or a command that is not the head can go out. The script takes a table lock on `ledger_ordering_key` so that it does not overlap with acceptance and claim (`HeadBookkeepingTest.rebuildWaitsForAnOpenSubmit`).
  - The case where stale statistics or a switch to a generic plan make the planner choose another index and a sort instead of `ledger_command_ready` is not tested.

### F33. The result-writing transaction runs the handler while holding the key row and deadlocks with the app's acceptance

- **What breaks**: in the first implementation, completion updated and locked the key's row (`ledger_ordering_key`) inside `compareAndSet`, and called the handler while holding that lock until commit. Common app code locks in the opposite order. The app updates the order row and then, in the same transaction, submits on that order's key (acceptance locks the key row to take a sequence number). The handler receives the result and updates the same order row. The app waits for the key row, the handler for the order row, and PG aborts one side (40P01). If the app side is aborted, user requests fail intermittently. If the completion side is aborted, it runs again after a backoff as a handler failure. Also, when an app transaction kept an acceptance on some key open for a long time, the completion on that key waited, and since the worker writes results one at a time, the results of other keys behind it stalled too. This lock was hidden behind the name `compareAndSet` and was not visible in the core code.
- **Reproduction**: `HeadRaceTest` › `anAppThatMarksTheOrderThenSubmitsDoesNotDeadlockWithTheHandler`. `KeyLockFreeStoreTest` (the store's insert, find, candidate locking and result writing do not wait even when another transaction holds the key row). `HeadStatementRaceTest` (three races between statements).
- **Structure**:
  - The port was split. Calls that lock a key row exist only in `OrderingKeys` (`lockKeyAndTakeSeq`, `passOnFinishedHeads`). `CommandStore` does not lock key rows. In jdbc, `JdbcOrderingKeys` and `JdbcCommandStore` divide the work.
  - The worker writes that call the handler were moved into `WorkerWrites`, and this class does not receive `OrderingKeys`. `ArchitectureTest.theHandlerPathCannotLockAKey` keeps "a class that calls `onCompleted` does not depend on `OrderingKeys`".
  - Completion leaves the head flag on the finished command. Before locking candidates, claim locks the key rows of finished heads with `FOR UPDATE SKIP LOCKED` and passes the flag on to the next unfinished command. A key held by the app is not waited for but deferred to the next claim. A locked key row is updated with its values unchanged. This makes an app at REPEATABLE READ or higher that took its snapshot earlier end with 40001 and retry when it takes a sequence number on that key. Otherwise that app would see a head in its old snapshot, would not make the new command the head, and the key would never go out.
  - The two statements that set the head flag (acceptance's `MARK_FIRST_UNFINISHED` and passing on's `MARK_NEXT_HEADS`) do nothing if the key already has a head. READ COMMITTED takes a new snapshot per statement, so when a completion on the same key committed after acceptance cleared the flag, there were two heads and the unique index broke the acceptance (23505). Passing on had the same gap. If another worker passed on the same key and committed after the passing-on query took its snapshot, the late query still locked that key row (`SKIP LOCKED` rechecks only the key row against the latest version). When the following flag-setting then waited to update the new head row that was in the middle of writing its result, it deadlocked with the handler, and if the new head finished in the meantime, there were two heads and claim broke. Locking the finished head row as well (`FOR UPDATE OF k, c`) was not used. If someone holds that row (for example a reservation row, F13), passing on for the whole key is deferred, and the contract test `aLockedReservationIsStillReported` broke. The reviewer reproduced all three on PG 18.6, and `HeadStatementRaceTest` commits the other side between statements to check them.
  - `unfinished_count` was dropped. Keys to pass on are found in `ledger_command_finished_head` (commands that are finished but still the head). The next unfinished command is found separately per key with `min(seq)`. A `GROUP BY` over the keys read the whole backlog behind a blocked key (564 pages for 20,000 rows, now 30 pages).
  - The rows each transaction locks are listed in a table in docs/development.md, "Transactions and locks".
- **Verification**:
  - Before the fix: the deadlock test ended with `deadlock detected` (the completion side was aborted, a handler failure), and result writing and insert in `KeyLockFreeStoreTest` did not finish within 2 seconds.
  - Mutation checks:
    - Restoring the key row lock in result writing fails 6 tests (deadlock, 4 not-waiting, 1 store).
    - Emptying the passing on fails 24 tests.
    - Removing the key row update fails 2 RR and SERIALIZABLE snapshot tests.
    - Removing `SKIP LOCKED` fails the "defers only the keys held by the app" test.
    - Adding an `OrderingKeys` field to `WorkerWrites` fails the ArchUnit rule.
    - Dropping `ledger_command_finished_head` fails the passing-on page test.
    - Removing the "if the key already has a head" condition from acceptance's flag-setting fails the acceptance race of `HeadStatementRaceTest` with 23505. Removing it from passing on's flag-setting fails the two late passing-on tests (waiting, 23505).
  - Measurement (limit 128): locking the keys to pass on, with 20,000 finished heads behind 50,000 finished rows, took 519 pages, 0.19 ms.
- **Remaining**:
  - If the handler submits, its result-writing transaction locks the key row. A handler that submits and then updates the order row can deadlock with an app that does "order row → submit". This is not prevented; the order (order row first) is written in docs/development.md, "Transactions and locks". A handler that submits the post when the reservation is applied is a legitimate use.
  - If app transactions submit on several keys in different orders, the key rows deadlock with each other. This property has existed since 02b (section 4).
  - An app at REPEATABLE READ or higher can get 40001 on an acceptance that overlaps claim's passing on. The app already has to retry this failure for concurrent acceptances on the same key (section 4).
  - If an app holds a key row for a long time, the next command of that key does not go out until the app commits. Other keys are not affected.

### F34. The worker's in-flight slots leak, or it runs with slots that differ from the configured limit

- **What breaks**:
  - If `LedgerClient.submit` returned `null` instead of a future, a `NullPointerException` occurred right after the batch's slots were handed to the future, and the slots never came back. After a few repetitions the slots ran out and claiming stopped in that process.
  - `RelayWorker` received `WorkerSettings.maxInFlight` and `InFlightLimit` separately. If the two differed, changing the setting did not change the actual number of concurrent requests.
- **Reproduction**: `RelayWorkerTest` › `aNullReplyGivesTheSlotsBackAndReleasesTheBatch`, `aWorkerRefusesSlotsThatDisagreeWithMaxInFlight`.
- **Structure**: `null` takes the same path as a failure thrown at the send site (report, release, return the slots). `InFlightLimit` remembers the number of slots it was created with (`capacity()`), and `RelayWorker` cannot be created if that differs from `maxInFlight`. The Spring auto-configuration creates the slots from the same setting (`LedgerRelayAutoConfigurationTest.slotsFollowMaxInFlight`).
- **Verification**: before the fix, the null test failed with `NullPointerException` and the mismatch test with "no refusal". A mutation that fixes the slots at 1024 in the auto-configuration is caught by `slotsFollowMaxInFlight`.

### F35. If even the write that records a handler failure fails, the handler exception disappears

- **What breaks**: when the handler throws, its transaction rolls back and "the received result and the handler failure" are kept in a new transaction. If this second write failed with a DB error, the worker received only the DB error. The original cause, the handler exception, was kept nowhere, and the operator could not see the handler bug.
- **Reproduction**: `RelayWorkerTest` › `theHandlersFailureSurvivesAFailedRecord`.
- **Structure**: the handler exception is attached to the second write's exception with `addSuppressed` and thrown (`WorkerWrites.finish`). The worker event `writeFailed` receives both.
- **Verification**: before the fix, suppressed was empty and the test failed.

### F36. When TB is unreachable, health slows down and the app is taken out of service

- **What breaks**: the first health sent a lookup to TB on every request, waited up to 1 second, and counted all waiting commands. When TB was unreachable, one health call took more than 1 second. It hit the orchestrator's probe timeout (Kubernetes default 1 second), and the app was taken out of service or restarted. Then even acceptance stopped. That is the exact opposite of this library's point, accepting without TB. During a long outage the backlog grew and health got slower, and lookups that timed out kept piling up in the TB client.
- **Reproduction**: `LedgerRelayHealthIndicatorTest` › `healthDoesNotWaitForASilentLedger`, `aSilentLedgerIsProbedOnceAtATime`. `LockDueBoundedWorkTest` › `countingAHugeBacklogReadsABoundedNumberOfPages`.
- **Structure**: the TB check does not wait. Only one lookup is sent at a time, and it is UP if the last answer was within 5 seconds. Waiting commands are counted only up to 1,000 (`Backlog.countsCapped`), and the oldest waiting command is read from the first entry of the new index `ledger_command_waiting (id) WHERE state IN ('PENDING','CLAIMED')`.
- **Verification**: before the fix, when TB did not answer, one health call took as long as the probe time, and 20 requests piled up 20 lookups. With 50,000 waiting commands, the counting query read 2,856 pages. A mutation that restores uncapped counting fails with 7,191 pages at 100,000 waiting commands (cap 2,500).

### F37. When two sample apps start on an empty DB at the same time, one dies while creating the schema (sample shop)

- **What breaks**: compose starts two apps that use the same DB together. If both do "create the tables if they do not exist", their `CREATE TABLE` statements overlap at the same moment. The late one fails with `duplicate key value violates unique constraint "pg_type_typname_nsp_index"` (23505) and cannot start. Someone who just cloned the repository runs `docker compose up` and sees one instance dead (the non-goal "fails on first start").
- **Reproduction**: `ShopBootstrapTest` › `twoInstancesStartingTogetherApplyTheSchemaOnce`. Two threads call `ShopSchema.apply` together on an empty database.
- **Structure**: `ShopSchema.apply` takes `pg_advisory_xact_lock(764)` in one transaction and applies the library's `schema.sql` and `shop-schema.sql` only if `shop_order` does not exist. The late one waits on the lock until the first one commits, then sees the tables and moves on. It is applied in `SmartInitializingSingleton`, so it finishes before the worker loop starts. The library still does not apply the schema automatically; this is a convenience of the sample app only.
- **Verification**: in a control experiment with the lock line removed, the test above failed 5 out of 5 times with 23505.

### F38. Pressing the pay button twice creates two orders and holds the money twice (sample shop)

- **What breaks**: two payment requests with the same `Idempotency-Key` arrive at the same time. If the code does "look up by key, insert if missing", both transactions see "missing" and both try to insert. With a unique constraint, the late one gets 500 from 23505 (the user sees a failed payment); without it, two orders and two reservations are created and the buyer's money is held twice. The library's idempotency (the same commandId once) does not prevent this, because the two requests create different orders with different commandIds.
- **Reproduction**: `OrderFlowTest` › `aDoubleClickMakesOneOrderAndOneReservation` (two threads pay together with the same key), `aRepeatedRequestReturnsTheSameOrder` (over HTTP).
- **Structure**: `shop_order.idempotency_key` has a unique constraint, and the row is inserted with `INSERT … ON CONFLICT (idempotency_key) DO NOTHING RETURNING`. The late one waits on the unique index until the earlier transaction ends and then inserts nothing. It then reads again by key. READ COMMITTED takes a new snapshot per statement, so it sees the order the earlier one committed. If the same key comes with a different buyer or product, it is refused with 422. Only the side that inserted submits the reservation.
- **Verification**: a control experiment with `ON CONFLICT DO NOTHING` removed failed 4 tests (double click, refusing a different product, two over HTTP).

### F39. Giving the TB client a host name address keeps the app from starting (sample shop)

- **What breaks**: the TB Java client (0.17.9) accepts only `IPv4:port`. Inside compose, the Toxiproxy in front of TB can only be found by name, like `toxiproxy:3001`. Passing this value as is fails with `Replica addresses format is invalid` the moment the `Client` is created, and the app does not start.
- **Reproduction**: `ShopBootstrapTest` › `theClientRefusesAHostName` (a learning test that pins TB's behavior), `aHostNameBecomesAnIpv4Address`.
- **Structure**: at startup, the sample app converts `shop.tigerbeetle.address` to IPv4 and creates the `Client` bean itself (`TigerBeetleClientConfig`). The library auto-configuration uses the `Client` the app provides. The library's `ledger-relay.tigerbeetle.addresses` has the same constraint, so use an IP in that setting (docs/development.md).
- **Verification**: creating a client with `localhost:<port>` fails with the message above, and with the converted `127.0.0.1:<port>` lookups work (2026-10-18, test container).

### F40. When health is called less often than every 5 seconds, it reports TB DOWN even though TB is fine (library)

- **What breaks**: F36's health decided by "is TB's last answer within 5 seconds of now". But the check lookup is only sent when health is called. So when health is called less often than every 5 seconds, the previous check's answer is always older than 5 seconds and the new check's answer has not arrived yet, so it is always `ledger: DOWN` even though TB is fine. The default interval of a Kubernetes probe is 10 seconds. The operator would see TB as always cut off and could not tell a real outage apart. This was found while watching the sample app with curl every 5 seconds (UP at 1-second intervals, DOWN all the time at 6-second intervals).
- **Reproduction**: `LedgerRelayHealthIndicatorTest` › `aLedgerThatAnswersIsUpEvenWhenHealthIsCalledRarely`. A test with a fake that answers TB lookups slightly late (`FakeLedger.answerHeldLookups`) and an advancing clock calls health after 10 seconds.
- **Structure**: the decision is based on the result of the checks, not on time. It is UP when the last finished check succeeded and no check in flight has been waiting for an answer longer than `ledgerStaleAfter` (5 seconds). The properties of F36 remain: health does not wait, and only one lookup is sent at a time. A check unanswered for more than 5 seconds is DOWN (`aProbeUnansweredForTooLongIsDown`).
- **Verification**: under the rule before the fix, the test above failed with `ledger: DOWN`. The existing 5 health tests still pass.

### F41. When the app runs in a container, the TB client cannot use io_uring and the app does not start (sample shop)

- **What breaks**: it is easy to assume that only the TB server uses io_uring. But the TB Java client (0.17.9) also uses io_uring in its native library. An app container started with Docker's default seccomp profile dies, JVM and all, the moment it creates the `Client`: `error(io): io_uring is not available` → `thread 7 panic: attempt to unwrap error: PermissionDenied` (exit 133). It is a panic, not an exception, so the app cannot catch it. compose's `restart` starts it again, with the same result every time. When compose was first started, both apps kept restarting like this. In the tests the app is a host JVM, so it did not show up.
- **Reproduction**: remove `security_opt` from the app services in `compose.yaml` and run `docker compose up`. The end-to-end test `ShopComposeTest` fails while waiting for startup (`/actuator/health`).
- **Structure**: the app containers also get `security_opt: [seccomp=unconfined]` (same as the TB server container). In production, a seccomp profile that additionally allows only the io_uring system calls is better than unconfined. This is written in docs/development.md.
- **Verification**: started without the option, both apps kept restarting with the panic above; with the option, both started and health was UP (2026-10-18, Docker Desktop).

### F42. Both apps build the same image at the same time and `docker compose up` sometimes fails (sample shop)

- **What breaks**: the first compose gave both `shop-a` and `shop-b` a `build` and the same `image` name. `docker compose up --build` builds both at the same time, and the one that finishes later fails while tagging the image with `image "docker.io/library/ledger-relay-shop:local": already exists`, which ends the whole `up`. Depending on the build cache state, it happens sometimes and sometimes not. If it happens when someone starts it for the first time, the demo does not even start (non-goal).
- **Reproduction**: in a full `./gradlew check --rerun-tasks`, `ShopComposeTest` failed at the `compose up -d --build` step with the message above (2026-10-18). The two runs before it passed.
- **Structure**: only `shop-a` builds the image. `shop-b` uses the same image name and has `pull_policy: never`, so it does not look in a registry. compose finishes the builds before creating containers, so `shop-b` starts from the image that `shop-a` built.
- **Verification**: after the fix, `ShopComposeTest` was run in turn with the images deleted (the first-start situation) and with a cache, and both passed.

### F43. The screen's reconciliation skips commands that finish late forever, so the consistency panel turns green after checking only part

- **What breaks**: at first the consistency panel ran reconciliation "continuing from the last position". The library's reconciliation reads finished commands in row order (id). But commands do not finish in row order: a post that halted and was abandoned and the reservation after it (scene 3), the batches of two workers, transactions that commit late. When the position moves past a row that has not finished yet, that row is never read again after it finishes. So exactly the commands of scene 3 were left out of reconciliation. Even if there was a mismatch there, the panel was green. This contradicts the non-goal "no fake numbers".
- **Reproduction**: `DemoScenarioTest` › `theRunningReconciliationCountsCommandsThatFinishLate`. Other orders finish while a bad post is halted, and then it is abandoned. Before the fix, only 18 of 22 finished commands were reconciled.
- **Structure**: `LedgerSnapshot` reconciles everything from the start on every refresh and rewrites the totals (up to 50,000 commands; at demo size it is always everything). Reconciliation at production scale will be redesigned later with a position that does not pass "the smallest id not yet finished" (the same problem as the reconciliation cursor in the list of open failures).
- **Verification**: the test above failed before the fix with `expected: 22L but was: 18L`, and passes after the fix.

### F44. Starting again after `docker compose down` keeps only the ledger, and the screen shows orders charged twice that never were

- **What breaks**: the first compose kept TB data in a named volume (`tigerbeetle-data`), while PG used the image's anonymous volume. The most common way to shut down, `docker compose down` (without -v), discards the PG data together with the containers but keeps the TB volume. On the next `up`, PG is empty but the ledger still has the old transfers. Order ids start again from 1, so old posts (user_data = old order id) get mixed up with new orders. The "orders charged twice" count on the screen goes above 0, the merchant balance and the total of paid orders differ, and the consistency panel stays red. A demo meant to show that money moves exactly once shows the opposite.
- **Reproduction**: `ComposeFileTest` › `neitherStoreOutlivesTheOther` (compose.yaml has no top-level `volumes:`). By hand: pay and ship → `docker compose down` → `up`.
- **Structure**: TB does not use a named volume either. The data file is inside the container. PG and TB stay with their containers (`up` after Ctrl-C, `stop`/`start`, `restart: on-failure`) and go away together (`down`).
- **Verification**: the test above failed before the fix. After the fix, pay and ship → `down` → `up` left both empty, and consistency was ok (reconciliation 0/0, duplicates 0) (2026-10-18). Once during the check the old data was still visible after `down`, but it did not reproduce on retry, and the cause is unknown.

### F45. Restarting the app while the TB connection is cut leaves it starting forever (sample shop)

- **What breaks**: at startup the app creates the ledger accounts and waits for TB's answer. The TB client has no timeout. If the app restarts while the demo's Toxiproxy is still cut (scene 2, then a crash or `docker compose restart shop-a`), the account setup never ends and the web server never opens. The button that restores the connection is on that app's own screen, so there is no way back except calling the Toxiproxy API by hand.
- **Reproduction**: `DemoRestartTest` › `startupRestoresACutConnection`. Cut the connection, then start the app with the demo profile.
- **Structure**: in the demo profile, `LedgerLink` restores the connection when it is created, before `ShopBootstrap` waits for TB. A restart of either instance therefore ends an outage scene for both, since they share the one proxy. If Toxiproxy does not answer within about 10 seconds (it has only just started), the app logs a warning and continues; a fresh proxy is connected anyway.
- **Verification**: the test above failed before the fix with "did not finish starting within 60 s", and passes after the fix.

### F46. A bug in a metrics listener breaks the worker cycle

- **What breaks**: the worker reports events from inside its cycle (after each write, after each TB request). With logging, metrics and the app's own listener all attached, a listener that throws (a metrics bug, a full registry, an app listener with a bug) would end the cycle in the middle: the commands already sent stay in flight until their lease runs out, and the results already received in that cycle are not written. The same bug would repeat on every cycle, so metrics would stop the money from moving.
- **Reproduction**: `WorkerEventsFanOutTest` › `aThrowingListenerDoesNotStopTheOthers`. The first listener throws on every event; the second must still receive them and the caller must see no exception.
- **Structure**: the worker receives one `WorkerEventsFanOut` that calls every listener in turn. A listener that throws is logged (at most once a minute for the same listener and event) and skipped; the others still receive the event. Gauges that need the DB do not run on the worker or on a scrape: `BacklogSampler` reads the backlog on its own thread and the gauges read its last sample, so a slow DB makes the numbers stale, not the workers slow.
- **Verification**: control experiment — with the `try`/`catch` in `WorkerEventsFanOut` removed, the test fails with the listener's `IllegalStateException`; with it, the test passes. `BacklogSamplerTest` › `aSlowSourceDoesNotBlockReaders` checks that a 5-second DB read does not hold up a gauge read.

### F47. Starting a newer shop on a database an older version created makes every result fail forever (sample shop)

- **What breaks**: `docker compose up --build` after pulling a newer version keeps the existing PostgreSQL container, and the shop applies its schema only when the tables are missing. The 03c shop writes each command's final state into new columns of `shop_order_command`. On the old table every outcome handler call fails with "bad SQL grammar", so the result write rolls back and is retried after the backoff, forever: orders stay `accepted`, the backlog never drains, and nothing on the screen says why. Found by rebuilding the 8090 demo over the 03b database.
- **Reproduction**: `ShopBootstrapTest` › `anOlderSchemaStopsStartupWithTheFix`. Apply the schema, drop the newest column as the older version had it, apply again.
- **Structure**: when the tables exist, `ShopSchema` checks for the newest column and, if it is missing, stops startup with "The database was created by an older version of the sample shop. Start the demo from empty: docker compose down, then docker compose up --build". The demo is meant to start from an empty DB and ledger, so there are no migrations; the README says to run `down` after pulling.
- **Verification**: the test failed before the check ("Expecting code to raise a throwable") and passes after it. On the real demo, rebuilding over the 03b database now logs that message and the app does not start; after `down` and `up` it starts and processes orders.

### F48. A batch larger than the TB cluster takes in one request is resent forever and nothing finishes (library)

- **What breaks**: the startup check allows `batch-size` up to 8189, the limit of a production TB replica. A replica started with `--development` takes at most 253 transfers per request. With a larger batch, the TB client refuses every request ("Too much data was sent or requested in this batch"). The worker treated that as a transient failure: it put the commands back and resent them after the backoff, the same size, forever. No command finished, nothing was halted, and the only trace was a DEBUG line per cycle. Found by the load measurement: with a batch of 512, all 20,000 commands had been sent 32 times after 8 minutes and none had finished.
- **Reproduction**: `TigerBeetleLedgerClientTest` › `aBatchOverTheLimitIsReportedAsUnavailable`. The test container is a development replica; one request of 254 transfers.
- **Structure**: the adapter wraps TB's `TooMuchDataException` in `LedgerUnavailableException` with a message that says to lower `batch-size`, like a closed or evicted client: resending cannot fix it. The worker still puts the commands back (money does not move), but it is now reported as `ledgerUnavailable`: a WARN log, the `ledger_unavailable` problem counter. Splitting the request automatically was not chosen: the batch size is a setting, and a wrong setting should be visible, not quietly worked around.
- **Verification**: control experiment — without the `TooMuchDataException` branch the test fails with TB's own exception ("Expecting actual throwable to be an instance of LedgerUnavailableException but was: Too much data…"); with it, the test passes. A development replica has a second limit with no error at all: a ninth client gets no reply to any request (`docs/development.md`, "Known issues").

## 2. Open failures

| # | Failure | Where to prevent it |
|---|---|---|
| O2 | Does a worker recover without duplicates no matter where it dies (kill -9) | Failure experiments (none yet) |
| O7 | In a DB moved by dump and restore or to another cluster, a moved `accepted_xact` can happen to equal a new transaction id, and a valid post referencing an already committed reservation can be refused with `ReservationNotFoundException`. There is no reproduction test. Reproducing a cluster move costs more than one line of procedure | Migration procedure: right after the restore, `UPDATE ledger_command SET accepted_xact = '0'` (docs/development.md) |

Closed open failures: O1 (row lock concurrency) moved to F11–F15, O3 (resending after a lost reply) to F23, and O4 (TB startup panic) to F26 (F26 is a mitigation). O6 (acceptance not bound to the connection) was prevented by F18. O5 (no way to see halted commands) was closed by the operator port `LedgerRelayOperator.haltedCommands` in 03b (including the halt reason and time).

## 3. Why there is no dead letter queue

If a problem command is moved to a DLQ and the next command of the same key is processed, the ledger order of that key (G4) breaks. For example, if a post goes before its reservation, it is wrongly rejected. So the problem command stays where it is and **only that key** is blocked. Other keys keep being processed.

| | HALT (F9) | Handler failure (F3–F5) |
|---|---|---|
| Nature | Rule violation, a sign of a bug | Can be transient |
| Handling | Halts at once, no retry | Keeps retrying with backoff. The key is blocked meanwhile |
| Where it shows | `halted` in health, operator port `haltedCommands` | The row's `handler_failures` column, worker logs |
| What a person does | Fix the cause, then abandon with `abandonHalted` | Fix the handler |

## 4. Why the isolation level does not matter

This answers "if it is a concurrency problem, why not just raise the isolation level to SERIALIZABLE?"

**Some problems cannot be prevented with locks.** The typical one is write skew. With the rule "at least one doctor must stay on call", two doctors check at the same time "will at least one remain without me?", both leave, and zero remain. Each changed only their own row, so the row locks never collide. The decision is based on "the set of rows that satisfy the condition", and there is no row to lock for that set. Only SERIALIZABLE makes the DB catch such problems.

**Our design creates a row to lock for every decision over a set** (materializing the conflict). So locks alone are enough at READ COMMITTED.

| Decision over a set | What prevents it | Record |
|---|---|---|
| Does this commandId already exist | Unique constraint + `ON CONFLICT DO NOTHING` | F15 |
| What is my sequence number in this key | Key row lock (held until commit), plus `UNIQUE (ordering_key, seq)` | F14 |
| Am I the head command of this key (`NOT EXISTS`) | Thanks to the key row lock, no earlier sequence number can appear later | F12 |
| Who holds this key's head flag (`is_head`) | Both acceptance, which sets the flag, and claim's passing on lock the key row. Passing on updates the key row, which sends an app at a higher level that accepts with an old snapshot back with 40001 | F32, F33 |
| May I take this command (one row) | `FOR UPDATE OF c SKIP LOCKED` | F12, F13 |
| May I write the result (one row) | Write conditioned on state and token | F2 |

**Changing the DB default is not used.** `default_transaction_isolation` is only a default. The real level is set each time by whoever opens the transaction. If the library changed the setting for the whole DB, it would change the other code of the apps that use the same DB, and even then the app overrides it per transaction. So the promise is "the data does not break at any isolation level", not "there are no errors".

**The only thing that changes at higher levels is that waiting becomes an error.**

- Worker steps are transactions we open ourselves, so they set READ COMMITTED explicitly (F17).
- Acceptance joins the app transaction, so it follows the app's level. At REPEATABLE READ and SERIALIZABLE, concurrent acceptances on the same key or with the same commandId end with a serialization failure (SQLSTATE 40001) on the late side. Only one row remains and the sequence numbers do not get tangled. The app sees 40001 and retries. Verification: the three chapters of `HigherIsolationSubmitTest` at two levels, 6 tests in total (the third chapter, "the same commandId on a different ordering key", was added in 02c). The same cases at READ COMMITTED are covered by `ReadCommittedSubmitTest`. Accepting on a key whose finished head claim has passed on, with a snapshot older than that, is also 40001 (`HeadRaceTest`, F33).
- Control experiment (based on the 4 tests of the first two chapters): taking the sequence number as `max(seq)+1` without the key lock fails only 1 of the 4 (same key, REPEATABLE READ). The late side ends with a unique violation (23505) instead of a retryable 40001. The other 3 pass because `UNIQUE (ordering_key, seq)` prevents sharing a sequence number, and at SERIALIZABLE PostgreSQL turns unique violations of concurrent transactions into 40001. At higher levels there is effectively one more line of defense besides the key lock. A missing key lock at READ COMMITTED is caught by F14.

Why all transactions are not forced to SERIALIZABLE: every collision between workers would cause a retry and lower throughput. It would block, a second time and at a high cost, a problem already blocked by locks.
- Deadlock: if one app transaction submits on keys K1, K2 in that order and another transaction submits on K2, K1, the two transactions wait for each other's row locks on `ledger_ordering_key` and deadlock (40P01). The same happens at READ COMMITTED. When one transaction submits on several keys, submit the keys in a fixed order (for example string order). Also, a long app transaction makes every other acceptance on that key wait until it commits (the app sets lock_timeout).
