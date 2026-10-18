# Rules for writing tests

The tests of ledger-relay are **a document that reads as the spec**. Reading the test tree from top to bottom should tell you what this code guarantees.

## 1. Test tree = table of contents of the spec

- A test class covers one subject. Its `@DisplayName` says what the rules are about and, if there is one, the guarantee number.
- `@Nested` classes get a table-of-contents number with `@Order(n)`. JUnit has no orderer for declaration order, so without the numbers the chapters of the result tree come out in mixed order (core sets `OrderAnnotation` as the default in `src/test/resources/junit-platform.properties`).
- A `@Nested` class is a **situation**. Its name starts with "When …", "After …" or "Given …". The shared precondition of that situation goes in the `@BeforeEach` of that `@Nested` class.
- A `@Test` is **an action and its effect**. Its name is one sentence of the form "Doing X does Y" or "Doing X does Y and Z".
- Guarantee numbers (G1–G7, [design.md](design.md)) and failure numbers (F1 onward, [failure-modes.md](failure-modes.md)) are attached once, at the outermost level that the rule is based on.

In the test results it should read like this.

```
TigerBeetle 0.17.9 result rules — the basis of the classification table
├─ After a payment of 500 is recorded
│   ├─ Resending the same payment is accepted as already recorded and moves money only once
│   └─ Sending the same id with only the amount changed is refused as a content conflict and only the first amount remains
├─ When paying from a wallet with too little balance
│   ├─ The payment is refused for insufficient balance and no money moves
│   ├─ Resending the same payment is refused because this id has already failed
│   └─ After a deposit the same id still stays failed, and only a new id makes the payment
└─ When withdrawing from an account that does not exist
    └─ The request is refused as invalid
```

**Finding empty branches**: when reading the tree as a table of contents makes you ask "what about this case?", that is a case that is not defended yet. Each time you refactor, write down the list of empty branches separately; a person decides whether to fill them.

## 2. Test body = given / when / then

```java
@Nested
@DisplayName("After a payment of 500 is recorded")
class AfterAPaymentIsRecorded {

    Account payer;
    Account payee;
    Transfer payment;

    @BeforeEach
    void givenARecordedPayment() {
        payer = ledger.openAccount("buyer");
        payee = ledger.openAccount("merchant");
        payment = ledger.recorded(transfer(500).from(payer).to(payee));
    }

    @Test
    @DisplayName("Resending the same payment is accepted as already recorded and moves money only once")
    void resendingTheSamePayment() {
        // when
        CreateTransferStatus result = ledger.submit(payment);

        // then
        assertAlreadyRecorded(result);
        assertMovedExactly(ledger, payer, payee, 500);
    }
}
```

- Separate the three sections with blank lines and `// given`, `// when`, `// then` comments. If the precondition is in `@BeforeEach`, the body has no `// given`.
- If the precondition needs explaining, add it after the comment. Example: `// given: a payment of 100 from a wallet with balance 0 has failed once`
- Aim for **a one-line when**. If there are two actions (cut → restore), add one more section, such as `// when: the connection is restored`.
- One test checks one action. If the name contains "and" more than once, that is a sign to split it.

## 3. given — turn the precondition into a sentence

| Word | Meaning |
|---|---|
| `ledger.openAccount("buyer")` | Opens an account. The name appears as is in failure messages |
| `ledger.openAccountThatCannotOverdraw("wallet")` | An account that cannot withdraw more than its balance. The balance starts at 0 |
| `ledger.deposit(wallet, 100)` | Deposit |
| `transfer(500).from(payer).to(payee)` | One transfer (new id). It is not sent yet at this step |
| `payment.withAmount(501)` / `payment.withNewId()` | The same id with only the amount changed / the same content with a new id |
| `payment.withCreditAccount(other)` and other `with…` | The same id with only that field changed (`withDebitAccount`, `withLedger`, `withCode`, `withUserData128/64/32`) |
| `Account.notOpened("account")` / `ledger.open(account)` | An account not yet in the ledger / opens that account with the same id (if it already exists, "given setup failed") |
| `ledger.recorded(t)` | **Precondition**: this transfer is already recorded |
| `ledger.rejected(t, ExceedsCredits)` | **Precondition**: this transfer has been refused for this reason |
| `reservation(500).from(payer).to(payee)` | One reservation (only holds the amount). `.withTimeout(1)` gives it an expiry |
| `postOf(hold)` / `postOf(hold).withAmount(300)` | Posts the reservation without an amount (the full amount) / posts only part of it. Accounts, ledger and code are sent empty so they are inherited from the reservation |
| `voidOf(hold)` | Voids the reservation |
| `ledger.closeAccount(account)` | **Precondition**: this account is closed (a closing reservation of 0). If it is already closed, "given setup failed" |
| `ledger.waitUntilExpired(hold)` | **Precondition**: waits until the reservation expires and the held amount is released (at most 2 seconds) |
| `ledger.stored(t)` | The transfer TB actually stored (`StoredTransfer`): inherited accounts and user_data, the real amount of a full post, timeout |

- **A precondition method stops right there if the precondition breaks.** Its message starts with `"given setup failed: …"`. This keeps the test from reaching then and failing for an unrelated reason.
- Do not hide the key values (amount, direction, reason). Do not bury a value inside a method name, as in `recordedPaymentOf500()`.

## 4. when — the action under test

| Word | Meaning |
|---|---|
| `ledger.submit(t)` | Sends and returns the result code |
| `ledger.submitInBackground(t)` | Sends without waiting for the reply (`PendingTransfer`) |

## 5. then — check the effect that happened with one method

| Word | Effect it checks (TB code) |
|---|---|
| `assertCreated(result)` | Newly recorded (`Created`) |
| `assertAlreadyRecorded(result)` | The same transfer already exists and is accepted as that one (`Exists`) |
| `assertConflictOnAmount(result)` | Refused because the id is the same but the amount differs (`ExistsWithDifferentAmount`) |
| `assertContentConflict(result, ExistsWithDifferentX)` | Refused because the id is the same but that field differs. If the expected value is not an `ExistsWithDifferent…`, it stops as a misuse |
| `assertRejectedBecauseIdAlreadyFailed(result)` | This id has already failed, so it is not judged again (`IdAlreadyFailed`) |
| `assertRejectedFor(result, reason)` | Refused for this reason |
| `assertMovedExactly(ledger, from, to, amount)` | Exactly amount left from, and exactly amount arrived in to |
| `assertNothingMoved(ledger, account)` | That account has no debits, credits or holds at all |
| `assertReserved(ledger, from, to, amount)` | Exactly amount is held from from to to, and nothing is posted |
| `assertPostedAndReleased(ledger, from, to, posted)` | Exactly posted was posted, and no held amount remains in either account |
| `assertStillWaitingAfter(pending, 3s)` | Neither a reply nor an error arrived during that time |

- Name it `assert` + **the effect that happened**. Do not put roles in the name, as in `assertPayerPaidPayee`. Pass roles as arguments, in the order of the sentence (from where → to where → how much).
- One method checks only one effect. Do not bundle them, as in `assertCreatedAndMoved500`.
- Write the failure message with the same words as the method name, and keep the original TB code in parentheses.
  Example: `expected the transfer with the same id and body to be accepted as already recorded (Exists), but the result was Created — recorded as a new transfer`
- **Write expected values by hand.** Do not compute 500 or `ExceedsCredits` with helpers or production code.
- Concurrency tests follow the same rules. Add them at the stage where they are needed.
  Examples: `assertExactlyOneApplied(outcomes)`, `assertOthersRejectedAsStaleWrite(outcomes)`, `assertHandlerRanOnceFor(command)`

## 6. Where the vocabulary lives

- **Words shared by several test files**: `src/testFixtures` (`TigerBeetleTestLedger`, `LedgerAssertions`, value objects). This becomes the public test kit later.
- **Words used in one file only**: private methods at the top of that test file.
- **The vocabulary itself is tested.** There are tests that check that assert methods and precondition methods fail properly on wrong input (`LedgerAssertionsTest`, `TigerBeetleTestLedgerTest`). If the checking tools are wrong, every test lies.

## 6-1. Tests that copy a table as is (core)

Tables of the spec (transition table, classification table) stay in the test **in the shape of a table**.

- A table is a `static final String` text block constant, read with `@CsvSource(textBlock = CONSTANT)`. Lines starting with `#` are subheadings inside the table.
- `@ParameterizedTest` gets `quoteTextArguments = false`. JUnit 6 wraps text arguments in quotes, so they look like `"PENDING" + "TB_APPLIED"`.
- Write expected values in a human-readable notation. Examples: `CLAIMED+token`, `0 rows`, `impossible (module bug)`. The `parse` that turns the notation into objects lives in that test file.
- Each table has one **guard test**. It reads the same constant and checks that it contains every value of the enum (or every combination) exactly once. A guard passes from the start, so delete one line of the table, confirm that it fails, and restore the line.
- Separately from the tests that check the table cell by cell, rules that span the whole table (G5: a final state does not move, G6: the handler runs only on entering a final state) are checked once more. If you make a mistake while editing the table, the cell tests go wrong together with the table, but these rule tests remain.

Effect names in core tests are private methods in that file. Examples: `assertSubmits`, `assertWaits`, `assertSkips`, `assertRejectedAsInvalid`, `assertSameHash`.

## 6-2. Contract tests (core testFixtures)

Because of the hexagonal structure, there are two sets of contracts. Each store implementation provides one `StorageUnderTest`, and there is one test that inherits each of the two contracts. The in-memory store and the PostgreSQL store read the same chapters.

- `CommandStoreContract`: the meaning of the store port. Insert, find, sequence numbers, lock and read, conditional writes, transactions. Rows are built by hand. They do not go through the state machine, which shows that the store makes no decisions.
- `LedgerRelayContract`: the behavior of the service `CommandLifecycle`. Acceptance, reference checks, G1, claim and the gate, results, late workers, handler failure, order.

Rows that cannot occur on the normal path (a post in reversed order, a post without a reservation row) are created with `acceptedPastTheChecks(postRow(…))`, which inserts them directly into the store without the acceptance checks. This way halting (HALTED) and the abandon action are also checked with the same chapters for every store.

### Concurrency chapters — making the other transaction deterministic

Chapter 7 of `CommandStoreContract` covers the moments when two transactions overlap. It does not start threads at random and rely on luck. The other transaction is opened with `OtherTransaction`, and the test decides when that transaction does its work and when it ends.

| Word | Meaning |
|---|---|
| `OtherTransaction.start(transactions, () -> …)` | Opens a transaction on another thread and does the work. After the work it waits without committing, so the row locks it took stay alive |
| `awaitWork()` | Waits for the work to finish and takes the result (at most 10 seconds). If the work threw, that exception comes out |
| `assertStillWorkingAfter(BLOCKED)` | The work did not finish within 300 ms = something is blocking it. A fixed observation time is used only to check that "nothing happens" |
| `commit()` / `rollback()` | Ends that transaction and waits until it has ended |
| `try (…)` | Rolls back on close if still open, so a failed test does not leave locks behind |

A store without row locks (in-memory) skips this chapter, because `StorageUnderTest.supportsRowLocks()` is false. The in-memory store serializes all transactions with one lock, so this chapter could not fail there. For the same reason, a store whose clock moves (PostgreSQL's `now()`) has `clockStandsStill()` false and skips the "at exactly that instant" boundary tests. `OtherTransactionTest` checks the vocabulary itself (including that it fails when it reports blocked while nothing blocks it).

| Word | Meaning |
|---|---|
| `newTransfer("pay-1", "order-1", 1)`, `newReserve(…)`, `newPost("capture-1", "order-1", 2, "hold-1")` | (store contract) A row to insert. The transfer id is 100 + sequence number, the hash is `hash-of-<id>` |
| `inserted(row)` / `claimed(id, token)` / `finished(id, row)` / `released(id)` / `halted(id)` | (store contract) **Precondition**: the row was inserted / is being processed / finished / was released / halted |
| `assertDue(ids…)` / `assertDueWithLimit(n, ids…)` / `assertNothingDue()` | (store contract) The claim candidates are exactly these (in the order they became sendable, ties in acceptance order) / none |
| `transfer("pay-1", "order-1", 500)`, `reserve(…)`, `post("capture-1", "order-1", "hold-1")`, `voidOf(…)` | (service contract) A command. The key and the amount are not hidden |
| `submit(command)` | (service contract) Submits inside a transaction, as an app does |
| `acceptedPastTheChecks(postRow("capture-1", "order-1", 1, "hold-1", 102))`, `reserveRow(…)` | (service contract) **Precondition**: a row that skipped the acceptance checks was inserted |
| `assertHalted(batch, ids…)` / `assertNothingHalted(batch)` / `assertRow(id, haltedRow(1))` | Reported as halted commands / nothing reported / the stored row is exactly this |
| `claimOnly("pay-1")` | **Precondition**: only this one command is claimed. Otherwise "given setup failed" |
| `finished("hold-1", ExceedsCredits)` | (service contract) **Precondition**: this command finished with this TB result |
| `storage.elapse(LEASE.plusSeconds(1))` | Advances the store clock (lease expiry, backoff elapsed). Does not sleep |
| `handler.failNextCalls(1)` | The next handler call throws before recording |
| `handler.crashNextCall()` | The next handler call throws an `Error` before recording (simulates a JVM-level failure) |
| `assertQueryState(id, State.X)` | Status lookup result |
| `assertClaimed(batch, ids…)` / `assertNothingClaimed(batch)` | Exactly these commands were claimed, in the order they became sendable / nothing was claimed |
| `assertWritten(r)` / `assertFenced(r)` / `assertHandlerFailed(r)` | The write was applied / blocked with 0 rows / cancelled because the handler threw |
| `assertHandlerRanOnceFor(id, State.X)` / `assertHandlerNeverRanFor(id)` | The handler ran to the end once / never ran to the end |
| `assertRejection(id, category, code)` | Reason for rejection or conflict |
| `assertRejectedAsConflict(…)` / `assertRejectedAsMissingReservation(…)` | Acceptance was refused as a content conflict / because the referenced reservation does not exist |

## 6-3. Worker tests (core)

One worker cycle (`RelayWorker.runOnce`) is checked deterministically on top of the in-memory store and a fake TB. End-to-end tests that really send to TB live in the tigerbeetle module.

| Word | Meaning |
|---|---|
| `FakeLedger` | Fake TB. Transfers without a set answer end as `Created` |
| `fake.answer(transferId, ExceedsCredits)` | Gives that transfer this result |
| `fake.holdReplies()` / `fake.releaseHeldReplies()` | Holds replies back (TB does not answer) / completes all held replies |
| `fake.failNext(e)` / `fake.replyWithCount(n)` | Ends the next request with an exception / gives only n results for the next request |
| `fake.requests()` | The received requests (batches of transfers) in order |
| `RecordingWorkerEvents.events()` | What the worker reported, in the order received, such as `"halted:capture-1"`, `"submitted:3"` |

- Waiting for replies is made deterministic with `holdReplies` and futures. Give a short submit-timeout (200 ms), and use that time as the observation time only to check that "nothing happens".

## 7. Refactoring rules

- Extract and name a setup or check when it **repeats two or three times with the same meaning**. Do not bundle things only because they look alike.
- The extracted name must be a word of the domain (`recorded`, `assertMovedExactly`). Do not use technical words (`createAndCheckBatch`).
- Test refactoring does not change behavior. Check that the whole suite gives the same results before and after. Adding new cases is a separate task.

## 8. TDD discipline

- Write the failing test first and check that it fails **for the intended reason**. A compile error does not count as Red.
- Characterization tests that pin the behavior of an external system (TB) may pass from the start. Instead, deliberately change one expected value to a wrong one and check that the test fails.
- For concurrency, force the execution order with checkpoints and latches, not sleep. Use a fixed observation time only to observe that "nothing happens".
