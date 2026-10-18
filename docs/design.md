# Design summary

This page collects what ledger-relay guarantees and the concepts that keep those guarantees. `G1`–`G7` in the code and tests refer to the guarantees below, and `F1` onward refer to the prevented failures in [failure-modes.md](failure-modes.md).

## The problem

An app that keeps business data in PostgreSQL (PG) and records money movements in TigerBeetle (TB) cannot put both in one transaction. In addition:

- The TB client has no timeout, and replies can be lost. There are moments when you do not know whether a request was sent or not.
- A transfer id that ended in a transient failure in TB cannot be used again (`id_already_failed`).
- If a post arrives before its reservation (reserve), the post fails and its id is used up.

A regular outbox sends the next message based on "delivered successfully". ledger-relay decides from the **ledger result** whether to send the next command, wait, or skip it.

## Guarantees

| | Guarantee | How it is kept |
|---|---|---|
| **G1** | Atomic acceptance. If the app transaction commits, the command is stored too; if it rolls back, the command disappears too and TB is not affected. | The command is stored in the same PG transaction as the app's data. Calling `submit()` without a transaction throws an exception. |
| **G2** | A later command of the same ordering key is not submitted before the earlier command reaches a final state. Retrying while the result is still unknown does not apply anything twice. | That one command is applied at most once comes from TB's transfer id idempotency. ledger-relay extends it to the retry flow. |
| **G3** | An accepted command reaches a final state (APPLIED, REJECTED, CONFLICT, SKIPPED). A command that does not stays visible in the status lookup and the operator port and never disappears silently. | When a lease ends, another worker resends with the same transfer id. Halted (HALTED) commands are visible to the operator. |
| **G4** | Order within an ordering key. The next command is submitted only when every command with a smaller sequence number in the same key is in a final state. If the reservation referenced by a post or void is in a final state other than APPLIED, that command ends as SKIPPED. | Locking the row that hands out sequence numbers per key makes sequence order equal to commit order. Per key, only one "head" command is a claim candidate. |
| **G5** | State only moves forward. Only the worker holding the current lease can change the state, and a final state never changes again. | UPDATE conditioned on the lease token (fencing). |
| **G6** | The outcome handler's DB changes commit in the same transaction as the command's final transition. The committed handler effect happens exactly once per final transition. | The handler code can run several times (retry after a rollback). External side effects are passed on as events after commit. |
| **G7** | Content integrity. Submitting different content with the same `commandId` throws `CommandConflictException`. When TB returns `exists_with_different_*`, it is not treated as success and stays as CONFLICT. | Compared by a hash of the normalized content. |

## Concepts

- **Command** (`LedgerCommand`): one TB transfer. The kinds are plain transfer (transfer), reservation (reserve, TB's pending), post, and void. Each command maps to one TB transfer id.
- **Ordering key and sequence number**: commands whose order matters (for example an order's reserve → post) are submitted with the same ordering key. Per key, the sequence number counts up from 1. Different keys never wait for each other.
- **Head**: per key, the unfinished command with the smallest sequence number. A worker only takes head commands (F1, F32).
- **Lease**: the deadline for a worker that took a command. If it does not write the result before the deadline, another worker resends with the same transfer id. TB answers `exists`, so money does not move twice.
- **Result classification**: TB's 68 result codes are divided into applied, business rejection, conflict, and so on. Transient failures are retried; results that cannot be resent with the same id stay as a final state.
- **Halt (HALTED)**: a command that cannot be judged, such as a row that did not go through the acceptance checks (a bug, a direct DB edit), blocks only its key and halts. It is inspected and abandoned through the operator port (`LedgerRelayOperator`).
- **Reconciliation**: for each finished command, checks that the result in PG matches the transfer in TB (`LedgerRelayOperator.reconcile`).

## Modules

```
ledger-relay-core          rules (state transition table, result classification, ordering gate) and services. Uses only the JDK
   ↑            ↑
ledger-relay-jdbc   ledger-relay-tigerbeetle     store (PostgreSQL) and ledger (TB) adapters
   ↑            ↑
ledger-relay-spring-boot     auto-configuration, worker loop, health
   ↑
examples/shop              sample app that uses the library
```

ArchUnit tests keep the dependency direction. Business decisions live only in core; the adapters only do wiring and I/O.

## Further reading

- [failure-modes.md](failure-modes.md): for each prevented failure, what breaks, the reproduction test, the structure, and the verification
- [development.md](development.md): transactions and lock order, operations notes, sample app setup
- [testing.md](testing.md): rules for writing tests that read like a spec
